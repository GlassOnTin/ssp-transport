package sh.haven.mosh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.mosh.transport.ReceiveState

/**
 * Regression tests for mosh client-side state tracking
 * (GlassOnTin/Haven#73). Pre-fix behaviour advanced the state
 * number even when the diff's base did not match — causing the
 * client to send acks that lied about which states it had actually
 * rendered, and producing the "cursor several lines below the
 * prompt / freezes after a few Returns" symptoms in bash and Mutt
 * reported by @gitcodeerrors.
 *
 * Upstream mosh's `Transport::recv` in
 * `src/network/networktransport-impl.h` returns early on base
 * mismatch without touching the state collection. These tests
 * enforce the same semantics at the unit level.
 */
class ReceiveStateTest {

    @Test
    fun `initial state number is zero`() {
        val state = ReceiveState()
        assertEquals(0L, state.num)
    }

    @Test
    fun `advance from matching base returns true and updates number`() {
        val state = ReceiveState()
        assertTrue(state.tryAdvance(oldNum = 0, newNum = 5))
        assertEquals(5L, state.num)
    }

    @Test
    fun `advance with stale newNum returns false and leaves state unchanged`() {
        val state = ReceiveState()
        state.setForTest(10)
        assertFalse(state.tryAdvance(oldNum = 0, newNum = 5))
        assertEquals(10L, state.num)
    }

    @Test
    fun `advance with equal newNum returns false (idempotent duplicate)`() {
        val state = ReceiveState()
        state.setForTest(7)
        assertFalse(state.tryAdvance(oldNum = 0, newNum = 7))
        assertEquals(7L, state.num)
    }

    /**
     * REGRESSION TEST for GlassOnTin/Haven#73.
     *
     * When a diff arrives whose base does not match our current
     * state (e.g. because the server sent it before our last ack
     * reached it, and it's reordered past a matching diff), the
     * code MUST NOT advance the state number. Lying about the
     * state causes cumulative display corruption.
     */
    @Test
    fun `advance with mismatched base returns false and leaves state unchanged`() {
        val state = ReceiveState()
        state.setForTest(5)

        // Server sends diff 0 → 10 (its latest), but we're already at 5.
        // Base 0 doesn't match our current 5 — cannot apply.
        val applied = state.tryAdvance(oldNum = 0, newNum = 10)

        assertFalse(
            "mismatched base must not apply diff — state would be wrong " +
                "if bytes from a 0→10 VT100 rendering sequence were fed " +
                "to a termlib already at state 5 (#73)",
            applied,
        )
        assertEquals(
            "state number must NOT advance on mismatch — the caller's " +
                "next ack has to keep reporting the true state so the " +
                "server can retransmit with a matching base (#73)",
            5L,
            state.num,
        )
    }

    @Test
    fun `mismatched base with base higher than current also returns false`() {
        val state = ReceiveState()
        state.setForTest(3)
        // Pathological: server sent diff based on state 7 but we're at 3
        assertFalse(state.tryAdvance(oldNum = 7, newNum = 10))
        assertEquals(3L, state.num)
    }

    @Test
    fun `matching advance after a mismatch still works`() {
        val state = ReceiveState()
        state.setForTest(5)

        // First attempt: mismatched base, rejected
        assertFalse(state.tryAdvance(oldNum = 0, newNum = 10))
        assertEquals(5L, state.num)

        // Server eventually retransmits with correct base 5 → apply
        assertTrue(state.tryAdvance(oldNum = 5, newNum = 10))
        assertEquals(10L, state.num)
    }

    @Test
    fun `sequential advances from matching bases`() {
        val state = ReceiveState()
        assertTrue(state.tryAdvance(0, 5))
        assertTrue(state.tryAdvance(5, 10))
        assertTrue(state.tryAdvance(10, 11))
        assertEquals(11L, state.num)
    }
}
