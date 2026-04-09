package sh.haven.mosh.transport

/**
 * Tracks the client-side reference state number for mosh's State
 * Synchronization Protocol and gates whether an incoming server diff
 * can be applied.
 *
 * Mirrors the invariants enforced by upstream mosh's
 * `Transport::recv()` in `src/network/networktransport-impl.h`:
 *
 * 1. **Diffs are idempotent.** A diff whose `newNum` is already at
 *    or below the current state is ignored.
 * 2. **The base must match.** A diff computed from
 *    `oldNum → newNum` can only be applied when the client is
 *    already at `oldNum`. Applying it from a different state would
 *    feed the client's terminal emulator a sequence of VT100
 *    rendering commands that were computed for a framebuffer the
 *    client never had — producing cumulative display corruption.
 * 3. **Do not advance on mismatch.** If the base doesn't match, the
 *    client must continue reporting its *actual* state number in
 *    subsequent acks so the server can retransmit the diff with a
 *    base the client can match. Lying about the state by advancing
 *    anyway causes the server to throw away the states the client
 *    actually needs, breaking recovery entirely.
 *
 * Rule 3 is the one Haven's original port got wrong, causing the
 * "cursor below prompt / freezes after a few Returns in bash"
 * symptoms reported as GlassOnTin/Haven#73.
 */
internal class ReceiveState {
    @Volatile
    var num: Long = 0
        private set

    /**
     * Attempt to advance the state from [oldNum] to [newNum]. Returns
     * `true` when the caller should apply the associated diff (and
     * [num] has been updated to [newNum]), `false` when the diff must
     * be skipped and the state left unchanged.
     *
     * Intentionally does **not** advance on mismatch.
     */
    fun tryAdvance(oldNum: Long, newNum: Long): Boolean {
        if (newNum <= num) return false          // idempotent duplicate or stale
        if (oldNum != num) return false          // base doesn't match current state
        num = newNum
        return true
    }

    /** Test helper — explicitly set the state number (not for production use). */
    internal fun setForTest(n: Long) {
        num = n
    }
}
