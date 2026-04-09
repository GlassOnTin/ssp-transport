package sh.haven.mosh.transport

import com.google.protobuf.ByteString
import sh.haven.mosh.proto.Userinput

/**
 * Client-side state for the State Synchronization Protocol.
 *
 * Accumulates user input (keystrokes and resize events) and computes
 * diffs between state versions. Each action increments the state number.
 */
class UserStream {
    private sealed class Action {
        data class Keystroke(val key: ByteArray) : Action()
        data class Resize(val width: Int, val height: Int) : Action()
    }

    private val actions = mutableListOf<Action>()

    val size: Long
        get() = synchronized(this) { actions.size.toLong() }

    fun pushKeystroke(bytes: ByteArray) {
        synchronized(this) {
            // Each byte is a separate UserEvent, matching mosh's C++ client behavior
            for (b in bytes) {
                actions.add(Action.Keystroke(byteArrayOf(b)))
            }
        }
    }

    fun pushResize(width: Int, height: Int) {
        synchronized(this) {
            actions.add(Action.Resize(width, height))
        }
    }

    /**
     * Compute serialized UserMessage diff from state [oldNum] to current state.
     *
     * Consecutive [Action.Keystroke] actions are coalesced into a single
     * `Keystroke` Instruction whose `keys` field contains all their bytes
     * concatenated, matching upstream mosh-client's `UserStream::diff_from`
     * in `src/statesync/user.cc`. This is load-bearing for correctness of
     * multi-byte escape sequences: without it, each byte of `ESC O A`
     * (application-mode cursor up) would land in its own Instruction, and
     * mosh-server would write each byte to the pty in a separate `write()`
     * call. ncurses-based apps such as Mutt use a short ESC-timeout
     * (~50 ms) to distinguish a bare ESC from the start of an escape
     * sequence, and microsecond-scale inter-byte gaps can still cause the
     * parser to split the sequence under load — the user sees "key is not
     * bound" for arrow keys (GlassOnTin/Haven#73).
     */
    fun diffFrom(oldNum: Long): ByteArray {
        synchronized(this) {
            val fromIdx = oldNum.toInt().coerceIn(0, actions.size)
            val newActions = if (fromIdx < actions.size) {
                actions.subList(fromIdx, actions.size).toList()
            } else {
                emptyList()
            }

            val msg = Userinput.UserMessage.newBuilder()
            var i = 0
            while (i < newActions.size) {
                val action = newActions[i]
                val inst = Userinput.Instruction.newBuilder()
                when (action) {
                    is Action.Keystroke -> {
                        // Walk forward collecting every consecutive Keystroke
                        // into one byte buffer, then emit a single Instruction.
                        val buf = java.io.ByteArrayOutputStream()
                        var j = i
                        while (j < newActions.size && newActions[j] is Action.Keystroke) {
                            buf.write((newActions[j] as Action.Keystroke).key)
                            j++
                        }
                        val ks = Userinput.Keystroke.newBuilder()
                            .setKeys(ByteString.copyFrom(buf.toByteArray()))
                            .build()
                        inst.setExtension(Userinput.keystroke, ks)
                        i = j
                    }
                    is Action.Resize -> {
                        val rs = Userinput.ResizeMessage.newBuilder()
                            .setWidth(action.width)
                            .setHeight(action.height)
                            .build()
                        inst.setExtension(Userinput.resize, rs)
                        i++
                    }
                }
                msg.addInstruction(inst.build())
            }
            return msg.build().toByteArray()
        }
    }
}
