package sh.haven.mosh

import com.google.protobuf.ByteString
import com.google.protobuf.ExtensionRegistryLite
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.mosh.proto.Hostinput
import sh.haven.mosh.proto.Transportinstruction
import sh.haven.mosh.proto.Userinput

class WireFormatTest {

    private val registry = ExtensionRegistryLite.newInstance().also {
        Userinput.registerAllExtensions(it)
        Hostinput.registerAllExtensions(it)
    }

    @Test
    fun `TransportInstruction roundtrip`() {
        val original = Transportinstruction.Instruction.newBuilder()
            .setProtocolVersion(2)
            .setOldNum(100)
            .setNewNum(200)
            .setAckNum(50)
            .setThrowawayNum(25)
            .setDiff(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4)))
            .build()

        val decoded = Transportinstruction.Instruction.parseFrom(original.toByteArray())

        assertEquals(original.protocolVersion, decoded.protocolVersion)
        assertEquals(original.oldNum, decoded.oldNum)
        assertEquals(original.newNum, decoded.newNum)
        assertEquals(original.ackNum, decoded.ackNum)
        assertEquals(original.throwawayNum, decoded.throwawayNum)
        assertArrayEquals(original.diff.toByteArray(), decoded.diff.toByteArray())
    }

    @Test
    fun `TransportInstruction with no diff`() {
        val original = Transportinstruction.Instruction.newBuilder()
            .setProtocolVersion(2)
            .setOldNum(0)
            .setNewNum(0)
            .setAckNum(0)
            .build()

        val decoded = Transportinstruction.Instruction.parseFrom(original.toByteArray())

        assertEquals(2, decoded.protocolVersion)
        assertEquals(0L, decoded.oldNum)
        assertTrue(!decoded.hasDiff())
    }

    @Test
    fun `UserMessage with keystroke`() {
        val ks = Userinput.Keystroke.newBuilder()
            .setKeys(ByteString.copyFromUtf8("hello"))
            .build()
        val inst = Userinput.Instruction.newBuilder()
            .setExtension(Userinput.keystroke, ks)
            .build()
        val msg = Userinput.UserMessage.newBuilder()
            .addInstruction(inst)
            .build()

        val encoded = msg.toByteArray()
        assertTrue(encoded.isNotEmpty())

        val decoded = Userinput.UserMessage.parseFrom(encoded, registry)
        assertEquals(1, decoded.instructionCount)
        assertTrue(decoded.getInstruction(0).hasExtension(Userinput.keystroke))
        assertEquals("hello",
            decoded.getInstruction(0).getExtension(Userinput.keystroke).keys.toStringUtf8())
    }

    @Test
    fun `UserMessage with resize`() {
        val rs = Userinput.ResizeMessage.newBuilder()
            .setWidth(80)
            .setHeight(24)
            .build()
        val inst = Userinput.Instruction.newBuilder()
            .setExtension(Userinput.resize, rs)
            .build()
        val msg = Userinput.UserMessage.newBuilder()
            .addInstruction(inst)
            .build()

        val encoded = msg.toByteArray()
        assertTrue(encoded.isNotEmpty())

        val decoded = Userinput.UserMessage.parseFrom(encoded, registry)
        val decodedResize = decoded.getInstruction(0).getExtension(Userinput.resize)
        assertEquals(80, decodedResize.width)
        assertEquals(24, decodedResize.height)
    }

    @Test
    fun `HostMessage decode with HostBytes`() {
        val hb = Hostinput.HostBytes.newBuilder()
            .setHoststring(ByteString.copyFromUtf8("hello"))
            .build()
        val inst = Hostinput.Instruction.newBuilder()
            .setExtension(Hostinput.hostbytes, hb)
            .build()
        val msg = Hostinput.HostMessage.newBuilder()
            .addInstruction(inst)
            .build()

        val decoded = Hostinput.HostMessage.parseFrom(msg.toByteArray(), registry)
        assertEquals(1, decoded.instructionCount)
        assertTrue(decoded.getInstruction(0).hasExtension(Hostinput.hostbytes))
        assertEquals("hello",
            decoded.getInstruction(0).getExtension(Hostinput.hostbytes).hoststring.toStringUtf8())
    }

    @Test
    fun `HostMessage decode with EchoAck`() {
        val ea = Hostinput.EchoAck.newBuilder()
            .setEchoAckNum(42)
            .build()
        val inst = Hostinput.Instruction.newBuilder()
            .setExtension(Hostinput.echoack, ea)
            .build()
        val msg = Hostinput.HostMessage.newBuilder()
            .addInstruction(inst)
            .build()

        val decoded = Hostinput.HostMessage.parseFrom(msg.toByteArray(), registry)
        assertEquals(42L,
            decoded.getInstruction(0).getExtension(Hostinput.echoack).echoAckNum)
    }

    @Test
    fun `HostMessage decode empty message`() {
        val msg = Hostinput.HostMessage.newBuilder().build()
        val decoded = Hostinput.HostMessage.parseFrom(msg.toByteArray(), registry)
        assertEquals(0, decoded.instructionCount)
    }

    @Test
    fun `TransportInstruction large values`() {
        val original = Transportinstruction.Instruction.newBuilder()
            .setProtocolVersion(2)
            .setOldNum(Long.MAX_VALUE)
            .setNewNum(Long.MAX_VALUE - 1)
            .setAckNum(0xFFFF_FFFFL)
            .build()

        val decoded = Transportinstruction.Instruction.parseFrom(original.toByteArray())
        assertEquals(original.oldNum, decoded.oldNum)
        assertEquals(original.newNum, decoded.newNum)
        assertEquals(original.ackNum, decoded.ackNum)
    }
}
