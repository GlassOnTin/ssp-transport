package sh.haven.mosh.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.google.protobuf.ExtensionRegistryLite
import sh.haven.mosh.MoshLogger
import sh.haven.mosh.NoOpLogger
import sh.haven.mosh.crypto.MoshCrypto
import sh.haven.mosh.network.MoshConnection
import sh.haven.mosh.proto.Hostinput
import sh.haven.mosh.proto.Transportinstruction.Instruction as TransportInstruction
import java.io.Closeable

private const val TAG = "MoshTransport"

/**
 * Pure Kotlin mosh transport implementing the State Synchronization Protocol.
 *
 * Replaces the native mosh-client binary. Handles UDP communication,
 * AES-128-OCB encryption, SSP state tracking, and protobuf framing.
 *
 * Terminal output (VT100 sequences from the server) is delivered via
 * [onOutput] and fed directly to connectbot's termlib emulator.
 */
class MoshTransport(
    private val serverIp: String,
    private val port: Int,
    key: String,
    private val onOutput: (ByteArray, Int, Int) -> Unit,
    private val onDisconnect: ((cleanExit: Boolean) -> Unit)? = null,
    private val logger: MoshLogger = NoOpLogger,
    private val initialCols: Int = 80,
    private val initialRows: Int = 24,
) : Closeable {

    private val crypto = MoshCrypto(key)
    private val userStream = UserStream()
    private val extensionRegistry = ExtensionRegistryLite.newInstance().also {
        Hostinput.registerAllExtensions(it)
    }

    // Connection created on IO thread in start() to avoid main-thread network StrictMode
    @Volatile private var connection: MoshConnection? = null

    // SSP state tracking
    @Volatile private var remoteStateNum: Long = 0    // latest state number received from server
    @Volatile private var serverAckedOurNum: Long = 0 // server's ack of our state
    @Volatile private var lastAckSent: Long = 0       // last ack we sent to server
    @Volatile private var lastSendTimeMs: Long = 0

    // Track whether we have genuinely new data vs just retransmitting
    @Volatile private var lastSentNewNum: Long = 0
    @Volatile private var retransmitCount: Int = 0

    // Diagnostic counters
    @Volatile private var packetsSent: Long = 0
    @Volatile private var packetsReceived: Long = 0
    @Volatile private var firstOutputReceived: Boolean = false

    // Network roaming detection
    @Volatile private var lastReceiveTimeMs: Long = 0

    // Conflated channel: wakes the send loop immediately when input arrives
    private val inputNotify = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var closed = false
    private var receiveJob: Job? = null
    private var sendJob: Job? = null

    /**
     * Start the transport: opens UDP socket on IO thread, begins receive and send loops.
     */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            if (closed) return@launch
            try {
                connection = MoshConnection(serverIp, port, crypto)
                logger.d(TAG, "UDP socket connected to $serverIp:$port")
            } catch (e: Exception) {
                logger.e(TAG, "Failed to create UDP connection", e)
                onDisconnect?.invoke(false)
                return@launch
            }
            // Send initial resize — mosh-server waits for a client state change
            // (newNum > 0) before releasing the child shell process
            userStream.pushResize(initialCols, initialRows)
            logger.d(TAG, "Queued initial resize ${initialCols}x${initialRows}")

            // Only one coroutine sends — no race on nonce counter
            receiveJob = launch { receiveLoop() }
            sendJob = launch { sendLoop() }
        }
    }

    /** Enqueue user keystrokes for delivery to the server. */
    fun sendInput(data: ByteArray) {
        if (closed) return
        val prevSize = userStream.size
        userStream.pushKeystroke(data)
        logger.d(TAG, "sendInput: ${data.size} bytes, userStream ${prevSize}→${userStream.size}")
        inputNotify.trySend(Unit)
    }

    /** Enqueue a terminal resize event. */
    fun resize(cols: Int, rows: Int) {
        if (closed) return
        userStream.pushResize(cols, rows)
        inputNotify.trySend(Unit)
    }

    override fun close() {
        if (closed) return
        closed = true
        receiveJob?.cancel()
        sendJob?.cancel()
        try { connection?.close() } catch (_: Exception) {}
    }

    private suspend fun receiveLoop() {
        try {
            while (!closed) {
                val conn = connection ?: break
                val instruction = try {
                    conn.receiveInstruction(RECV_TIMEOUT_MS)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (e: Exception) {
                    if (!closed) logger.e(TAG, "Receive error: ${e.message}")
                    continue
                }

                if (instruction == null) continue // timeout
                lastReceiveTimeMs = System.currentTimeMillis()
                processInstruction(instruction)
            }
        } catch (_: CancellationException) {
            // normal shutdown
        } catch (e: Exception) {
            if (!closed) {
                logger.e(TAG, "Receive loop failed", e)
                onDisconnect?.invoke(false)
            }
        }
    }

    private fun processInstruction(inst: TransportInstruction) {
        packetsReceived++

        // Update server's acknowledgement of our state
        if (inst.ackNum > serverAckedOurNum) {
            val oldAck = serverAckedOurNum
            serverAckedOurNum = inst.ackNum
            retransmitCount = 0 // server got our data, reset backoff
            logger.d(TAG, "Server acked our state: $oldAck → ${inst.ackNum}")
        }

        // Only apply diffs whose base matches our current state. The server's
        // diff is a visual framebuffer update computed from oldNum → newNum using
        // VT100 sequences. If we've already advanced past oldNum, applying the
        // diff would re-write characters that are already on screen (doubling).
        // The server will resend with the correct base once it receives our ack.
        if (inst.newNum > remoteStateNum) {
            if (inst.oldNum == remoteStateNum) {
                if (inst.hasDiff() && !inst.diff.isEmpty) {
                    if (!firstOutputReceived) {
                        firstOutputReceived = true
                        logger.d(TAG, "First terminal output received (newNum=${inst.newNum}, diffSize=${inst.diff.size()}, packets sent=$packetsSent received=$packetsReceived)")
                    }
                    try {
                        val hostMsg = Hostinput.HostMessage.parseFrom(inst.diff, extensionRegistry)
                        for (hi in hostMsg.instructionList) {
                            if (hi.hasExtension(Hostinput.hostbytes)) {
                                val hb = hi.getExtension(Hostinput.hostbytes)
                                val bytes = hb.hoststring.toByteArray()
                                onOutput(bytes, 0, bytes.size)
                            }
                        }
                    } catch (e: Exception) {
                        logger.e(TAG, "Failed to decode HostMessage", e)
                    }
                }
                remoteStateNum = inst.newNum
            } else {
                // Base mismatch — skip diff but still advance state number so
                // our acks tell the server where we are. The server will send
                // a fresh diff from the correct base.
                logger.d(TAG, "Skipping diff: oldNum=${inst.oldNum} ≠ remoteStateNum=$remoteStateNum (newNum=${inst.newNum})")
                remoteStateNum = inst.newNum
            }
        }
    }

    private suspend fun sendLoop() {
        try {
            // Send initial keepalive immediately
            sendState()

            while (!closed) {
                val now = System.currentTimeMillis()
                val elapsed = now - lastSendTimeMs
                val currentNum = userStream.size
                val hasNewInput = currentNum != lastSentNewNum
                val hasNewAck = remoteStateNum > lastAckSent
                val needsRetransmit = currentNum > serverAckedOurNum

                // Detect network stall: no packets received despite sending keepalives.
                // The UDP socket is likely bound to a defunct interface after IP roaming.
                // Recreate the socket so it binds to the current default route.
                val recvAge = now - lastReceiveTimeMs
                if (lastReceiveTimeMs > 0 && recvAge > NETWORK_STALL_MS) {
                    logger.d(TAG, "No packets received for ${recvAge}ms, rebinding socket")
                    connection?.rebindSocket()
                    lastReceiveTimeMs = now // reset to avoid repeated rebinds
                }

                when {
                    // New keystrokes: send promptly
                    hasNewInput && elapsed >= SEND_MIN_INTERVAL_MS -> sendState()
                    // New ack to send: send soon
                    hasNewAck && elapsed >= ACK_DELAY_MS -> sendState()
                    // Retransmit unacked data: back off exponentially
                    needsRetransmit && elapsed >= retransmitInterval() -> sendState()
                    // Keepalive
                    elapsed >= KEEPALIVE_INTERVAL_MS -> sendState()
                    else -> {
                        val wait = when {
                            hasNewInput -> SEND_MIN_INTERVAL_MS - elapsed
                            hasNewAck -> ACK_DELAY_MS - elapsed
                            needsRetransmit -> retransmitInterval() - elapsed
                            // Idle: sleep until next keepalive, woken early by inputNotify
                            else -> KEEPALIVE_INTERVAL_MS - elapsed
                        }
                        withTimeoutOrNull(maxOf(5L, wait)) { inputNotify.receive() }
                    }
                }
            }
        } catch (_: CancellationException) {
            // normal shutdown
        }
    }

    /** Exponential backoff for retransmissions: 100ms, 200ms, 400ms, capped at 1000ms. */
    private fun retransmitInterval(): Long {
        val base = 100L
        val interval = base shl minOf(retransmitCount, 3) // 100, 200, 400, 800
        return minOf(interval, 1000L)
    }

    private fun sendState() {
        if (closed) return
        try {
            val currentNum = userStream.size
            val diff = userStream.diffFrom(serverAckedOurNum)
            val instruction = TransportInstruction.newBuilder()
                .setProtocolVersion(PROTOCOL_VERSION)
                .setOldNum(serverAckedOurNum)
                .setNewNum(currentNum)
                .setAckNum(remoteStateNum)
                .setThrowawayNum(serverAckedOurNum)
                .setDiff(com.google.protobuf.ByteString.copyFrom(diff))
                .build()
            connection?.sendInstruction(instruction) ?: return
            packetsSent++
            val isRetransmit = currentNum == lastSentNewNum && currentNum > serverAckedOurNum
            if (packetsSent <= 3L || diff.isNotEmpty()) {
                logger.d(TAG, "sendState #$packetsSent: oldNum=$serverAckedOurNum newNum=$currentNum ackNum=$remoteStateNum diffSize=${diff.size}${if (isRetransmit) " RETRANSMIT" else ""}")
            }
            lastSendTimeMs = System.currentTimeMillis()
            lastAckSent = remoteStateNum

            if (currentNum == lastSentNewNum && currentNum > serverAckedOurNum) {
                retransmitCount++ // same data resent
            } else {
                retransmitCount = 0
            }
            lastSentNewNum = currentNum
        } catch (e: Exception) {
            if (!closed) logger.e(TAG, "Send error", e)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = 2
        const val SEND_MIN_INTERVAL_MS = 20L
        const val ACK_DELAY_MS = 20L
        const val NETWORK_STALL_MS = 5000L
        const val KEEPALIVE_INTERVAL_MS = 3000L
        const val RECV_TIMEOUT_MS = 250
    }
}
