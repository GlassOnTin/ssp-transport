package sh.haven.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.mosh.transport.ReceiveState

/**
 * Scenario tests driving [ReceiveState] through sequences of simulated
 * server packets under network conditions, to characterise the exact
 * behaviour of Haven's strict base-match receive logic before any
 * rewrite. The goal is to pin down — with empirical tests, not
 * handwaving — which failure modes the current code has and which it
 * does not, so that a rewrite for GlassOnTin/Haven#92 can be judged
 * against concrete assertions instead of hoped-for behaviour.
 *
 * Key finding: **the current strict check converges under all the
 * benign scenarios modelled here.** It lags behind the server by the
 * ack round-trip gap but it does not permanently stall. That means
 * the #92 "permanent freeze after a while" symptom reported by users
 * is NOT reproduced by a simple strict-check-under-lag model. The
 * root cause is either in a path this harness does not model (input
 * coalescer, network stall detection, socket rebind) or in a server
 * behaviour this harness does not simulate (history window eviction,
 * retransmit base selection).
 *
 * Whatever the real root cause, these tests lock in the current
 * behaviour so a planned history-based rewrite can be verified not
 * to regress it.
 */
class ReceivePipelineScenarioTest {

    /**
     * Minimal model of a mosh server state machine. It tracks a
     * monotonically increasing host state number and remembers the
     * latest ack it has *received* from the client. Each [send]
     * emits one `(oldNum, newNum)` tuple whose base is the last
     * acknowledged client state — a simplification of upstream
     * mosh-server which also keeps a sent-states history the client
     * can select from. The model is intentionally pessimistic: it
     * always picks the *lowest* safe base, so any gap between
     * server sends and client acks shows up as base/num mismatch on
     * the client side.
     */
    private class ServerModel {
        var currentState: Long = 0
            private set
        var lastAckedByClient: Long = 0
            private set

        fun advanceHostState(by: Long = 1) {
            currentState += by
        }

        fun send(): Packet = Packet(oldNum = lastAckedByClient, newNum = currentState)

        fun receiveAck(ack: Long) {
            if (ack > lastAckedByClient) lastAckedByClient = ack
        }
    }

    private data class Packet(val oldNum: Long, val newNum: Long)

    private data class ScenarioResult(
        val clientFinalNum: Long,
        val serverFinalState: Long,
        val rejectedPackets: Int,
        val acceptedPackets: Int,
    )

    /**
     * Run [rounds] iterations: server bumps one host state, sends, the
     * packet is delivered through [network] (which may drop or
     * rewrite), the client applies via `ReceiveState.tryAdvance`, and
     * the client's current num is queued to reach the server after
     * [ackRoundTripGap] further server sends — i.e. one ack round
     * trip measured in send events.
     */
    private fun runScenario(
        rounds: Int,
        ackRoundTripGap: Int = 0,
        network: (Packet, Int) -> Packet? = { p, _ -> p },
    ): ScenarioResult {
        val server = ServerModel()
        val client = ReceiveState()
        var rejected = 0
        var accepted = 0
        val pendingAcks: ArrayDeque<Long> = ArrayDeque()

        repeat(rounds) { round ->
            server.advanceHostState()
            val packet = network(server.send(), round) ?: return@repeat
            val applied = client.tryAdvance(packet.oldNum, packet.newNum)
            if (applied) accepted++ else rejected++

            pendingAcks.addLast(client.num)
            while (pendingAcks.size > ackRoundTripGap) {
                server.receiveAck(pendingAcks.removeFirst())
            }
        }

        return ScenarioResult(
            clientFinalNum = client.num,
            serverFinalState = server.currentState,
            rejectedPackets = rejected,
            acceptedPackets = accepted,
        )
    }

    @Test
    fun `zero lag and ideal network reaches server state with no rejections`() {
        val result = runScenario(rounds = 20, ackRoundTripGap = 0)
        assertEquals(20L, result.serverFinalState)
        assertEquals(20L, result.clientFinalNum)
        assertEquals(0, result.rejectedPackets)
        assertEquals(20, result.acceptedPackets)
    }

    /**
     * One in-flight ack: the server's "last acked by client" is always
     * one send behind the client's true position, so every other
     * packet arrives with a stale base and is rejected. The client's
     * num lags the server by exactly one state at the end of the run.
     *
     * This is the cost of the strict base-match check under any
     * realistic latency, and is the first pressure-point a
     * history-based rewrite would relieve.
     */
    @Test
    fun `one in-flight ack halves throughput but still converges`() {
        val result = runScenario(rounds = 20, ackRoundTripGap = 1)
        // Client ends one state behind the server (last packet was
        // rejected because its base lagged).
        assertEquals(20L, result.serverFinalState)
        assertEquals(19L, result.clientFinalNum)
        // Exactly half of the packets rejected.
        assertEquals(10, result.rejectedPackets)
        assertEquals(10, result.acceptedPackets)
    }

    /**
     * Two in-flight acks: the server lags further behind, and the
     * rejection pattern is 2-out-of-every-3 packets.
     */
    @Test
    fun `two in-flight acks rejects two thirds of packets`() {
        val result = runScenario(rounds = 30, ackRoundTripGap = 2)
        assertEquals(30L, result.serverFinalState)
        // Client is several states behind due to the ack lag.
        assertTrue(
            "client should fall significantly behind with 2-RTT lag",
            result.clientFinalNum < result.serverFinalState,
        )
        // Majority of packets rejected.
        assertTrue(
            "expected most packets rejected with 2-RTT lag, got ${result.rejectedPackets}/${result.rejectedPackets + result.acceptedPackets}",
            result.rejectedPackets > result.acceptedPackets,
        )
    }

    /**
     * Single-packet drop with zero ack lag. The next packet's base is
     * still the client's current num (the drop didn't advance the
     * ack), so recovery is immediate and the client still converges
     * to the server state.
     */
    @Test
    fun `single drop with zero lag recovers on next packet`() {
        var dropped = false
        val result = runScenario(rounds = 20, ackRoundTripGap = 0) { p, round ->
            if (round == 10 && !dropped) { dropped = true; null } else p
        }
        assertEquals(20L, result.serverFinalState)
        assertEquals(20L, result.clientFinalNum)
        assertEquals(0, result.rejectedPackets)
    }

    /**
     * Regression pin for #73: client must never advance its num past
     * a state it has not actually rendered. If a pathological packet
     * with a future base (`oldNum > num`) arrives, it must be
     * rejected outright with no state change. Duplicates
     * [ReceiveStateTest] coverage but left here so a history-based
     * rewrite has a visible "do not regress this" marker in the
     * scenario file as well as the unit file.
     */
    @Test
    fun `future-base diff is rejected and state is untouched`() {
        val client = ReceiveState()
        assertTrue(client.tryAdvance(0, 5))
        assertTrue(!client.tryAdvance(7, 10))
        assertEquals(5L, client.num)
    }

    /**
     * Not reproduced here: a *permanent* stall. With the pessimistic
     * server model above, every scenario eventually converges — the
     * client falls behind under lag but keeps making progress. This
     * is a deliberate negative result: if #92 is a permanent freeze,
     * its root cause lies in a path this harness does not model
     * (InputCoalescer, network stall detection, socket rebind, or a
     * server history-window eviction that this simplified ServerModel
     * does not simulate).
     *
     * A history-based rewrite of [ReceiveState] would flatten the
     * `one in-flight ack halves throughput` penalty documented above,
     * which is worth doing on its own merits, but it would NOT by
     * itself fix a permanent stall. Capture a verbose transport log
     * from a real repro before deciding.
     */
    @Test
    fun `characterisation harness does not reproduce permanent stall`() {
        // Run a long scenario under heavy rejection (2-RTT lag) and
        // assert that the client still makes some progress, locking
        // in the "not a permanent stall" property of the current code
        // at this layer.
        val result = runScenario(rounds = 50, ackRoundTripGap = 2)
        assertTrue(
            "client should still make progress even under sustained lag",
            result.clientFinalNum > 0,
        )
        assertTrue(
            "client should have accepted some packets",
            result.acceptedPackets > 0,
        )
    }
}
