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
            for (action in newActions) {
                val inst = Userinput.Instruction.newBuilder()
                when (action) {
                    is Action.Keystroke -> {
                        val ks = Userinput.Keystroke.newBuilder()
                            .setKeys(ByteString.copyFrom(action.key))
                            .build()
                        inst.setExtension(Userinput.keystroke, ks)
                    }
                    is Action.Resize -> {
                        val rs = Userinput.ResizeMessage.newBuilder()
                            .setWidth(action.width)
                            .setHeight(action.height)
                            .build()
                        inst.setExtension(Userinput.resize, rs)
                    }
                }
                msg.addInstruction(inst.build())
            }
            return msg.build().toByteArray()
        }
    }
}
