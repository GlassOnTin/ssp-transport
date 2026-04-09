package sh.haven.mosh

import com.google.protobuf.ExtensionRegistryLite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.mosh.proto.Userinput
import sh.haven.mosh.transport.UserStream

/**
 * Regression tests for [UserStream] protobuf serialization, in particular
 * the diff-from coalescing behaviour that upstream mosh-client relies on.
 *
 * The bug fixed here (GlassOnTin/Haven#73): Haven's original port pushed
 * each byte as a separate `Action.Keystroke` and then serialized each
 * action to its own `Instruction`, producing one 1-byte keystroke per
 * instruction. Upstream mosh coalesces consecutive UserByte actions into
 * a single Keystroke instruction containing all bytes concatenated. When
 * bytes were fragmented one-per-instruction, mosh-server wrote them to
 * the pty in separate write() calls with microsecond gaps, which ncurses-
 * based apps like Mutt (with a short ESC-timeout) interpreted as "bare
 * ESC followed by two other keys" rather than `ESC O A` (application
 * cursor up). Vim's own ESC-timeout was more forgiving so it worked; the
 * same sequence over SSH also worked because TCP delivered all 3 bytes
 * in one burst. Coalescing fixes the symptom by guaranteeing the bytes
 * land in a single mosh-server pty write().
 */
class UserStreamTest {

    private val registry = ExtensionRegistryLite.newInstance().also {
        Userinput.registerAllExtensions(it)
    }

    private fun parseDiff(diff: ByteArray): Userinput.UserMessage =
        Userinput.UserMessage.parseFrom(diff, registry)

    @Test
    fun `three-byte keystroke is serialized as a single instruction`() {
        val stream = UserStream()
        // Application-cursor-mode "up arrow" — ESC O A
        stream.pushKeystroke(byteArrayOf(0x1B, 0x4F, 0x41))

        val msg = parseDiff(stream.diffFrom(0))

        assertEquals(
            "pushing a 3-byte arrow sequence must produce exactly one " +
                "Keystroke Instruction (was multiple → mosh-server writes " +
                "bytes with microsecond gaps → ncurses ESC-timeout triggers " +
                "in Mutt and the sequence is seen as literal ESC + O + A)",
            1,
            msg.instructionCount,
        )
        val keystroke = msg.getInstruction(0).getExtension(Userinput.keystroke)
        assertArrayEquals(
            byteArrayOf(0x1B, 0x4F, 0x41),
            keystroke.keys.toByteArray(),
        )
    }

    @Test
    fun `consecutive keystrokes across multiple pushes are coalesced`() {
        val stream = UserStream()
        // Simulate rapid typing: three separate pushKeystroke calls between
        // server acks. All should end up in a single Keystroke Instruction
        // matching upstream mosh's diff_from coalescing.
        stream.pushKeystroke(byteArrayOf('a'.code.toByte()))
        stream.pushKeystroke(byteArrayOf('b'.code.toByte()))
        stream.pushKeystroke(byteArrayOf('c'.code.toByte()))

        val msg = parseDiff(stream.diffFrom(0))

        assertEquals(1, msg.instructionCount)
        val keystroke = msg.getInstruction(0).getExtension(Userinput.keystroke)
        assertArrayEquals(
            "abc".toByteArray(),
            keystroke.keys.toByteArray(),
        )
    }

    @Test
    fun `resize breaks keystroke coalescing boundary`() {
        val stream = UserStream()
        stream.pushKeystroke(byteArrayOf('a'.code.toByte(), 'b'.code.toByte()))
        stream.pushResize(100, 40)
        stream.pushKeystroke(byteArrayOf('c'.code.toByte()))

        val msg = parseDiff(stream.diffFrom(0))

        // Expected: one keystroke("ab"), one resize, one keystroke("c").
        assertEquals(3, msg.instructionCount)

        assertTrue(msg.getInstruction(0).hasExtension(Userinput.keystroke))
        assertArrayEquals(
            "ab".toByteArray(),
            msg.getInstruction(0).getExtension(Userinput.keystroke).keys.toByteArray(),
        )

        assertTrue(msg.getInstruction(1).hasExtension(Userinput.resize))
        val resize = msg.getInstruction(1).getExtension(Userinput.resize)
        assertEquals(100, resize.width)
        assertEquals(40, resize.height)

        assertTrue(msg.getInstruction(2).hasExtension(Userinput.keystroke))
        assertArrayEquals(
            "c".toByteArray(),
            msg.getInstruction(2).getExtension(Userinput.keystroke).keys.toByteArray(),
        )
    }

    @Test
    fun `diffFrom at current state produces empty message`() {
        val stream = UserStream()
        stream.pushKeystroke(byteArrayOf(0x0D)) // return
        val firstDiff = stream.diffFrom(0)
        val firstMsg = parseDiff(firstDiff)
        assertEquals(1, firstMsg.instructionCount)

        // After the server has acked at stream.size, diffFrom should emit nothing
        val emptyDiff = stream.diffFrom(stream.size)
        val emptyMsg = parseDiff(emptyDiff)
        assertEquals(0, emptyMsg.instructionCount)
    }

    @Test
    fun `diffFrom handles single-byte return`() {
        val stream = UserStream()
        stream.pushKeystroke(byteArrayOf(0x0D)) // CR

        val msg = parseDiff(stream.diffFrom(0))

        assertEquals(1, msg.instructionCount)
        val keystroke = msg.getInstruction(0).getExtension(Userinput.keystroke)
        assertArrayEquals(byteArrayOf(0x0D), keystroke.keys.toByteArray())
    }
}
