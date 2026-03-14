package sh.haven.mosh

/**
 * Logging interface for the mosh transport library.
 * Implement this to bridge to your platform's logging (e.g. android.util.Log).
 */
interface MoshLogger {
    fun d(tag: String, msg: String)
    fun e(tag: String, msg: String, throwable: Throwable? = null)
}

/** Default no-op logger. */
object NoOpLogger : MoshLogger {
    override fun d(tag: String, msg: String) {}
    override fun e(tag: String, msg: String, throwable: Throwable?) {}
}
