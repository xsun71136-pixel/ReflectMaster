package formatfa.reflectmaster.core

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Logging that is safe to call from **both** the module process and a hooked
 * foreign process.
 *
 * The 1.x code called `XposedBridge.log()` from classes that are also loaded in
 * the module's own UI process, where the Xposed classes do not exist - a
 * guaranteed [NoClassDefFoundError]. Here the Xposed bridge is never referenced
 * directly; the hook side installs a sink instead (see
 * `formatfa.reflectmaster.hook.HookLog`).
 */
object RmLog {

    const val TAG = "ReflectMaster"

    /** Bounded in-memory tail, newest last. Read by the floating console. */
    private const val MEMORY_LIMIT = 400
    private val memory = ConcurrentLinkedDeque<String>()

    private val sinks = CopyOnWriteArrayList<(String) -> Unit>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun addSink(sink: (String) -> Unit) {
        sinks.addIfAbsent(sink)
    }

    fun removeSink(sink: (String) -> Unit) {
        sinks.remove(sink)
    }

    fun clearMemory() = memory.clear()

    /** Snapshot of the in-memory tail (oldest first). */
    fun snapshot(): List<String> = memory.toList()

    fun d(msg: String) = emit("D", msg, null)
    fun i(msg: String) = emit("I", msg, null)
    fun w(msg: String) = emit("W", msg, null)
    fun e(msg: String, t: Throwable? = null) = emit("E", msg, t)

    private fun emit(level: String, msg: String, t: Throwable?) {
        val line = buildLine(level, msg, t)
        memory.addLast(line)
        while (memory.size > MEMORY_LIMIT) memory.pollFirst()
        try {
            when (level) {
                "E" -> Log.e(TAG, msg, t)
                "W" -> Log.w(TAG, msg, t)
                else -> Log.d(TAG, msg, t)
            }
        } catch (ignored: Throwable) {
            // Some hosts replace android.util.Log; never let logging kill a hook.
        }
        for (s in sinks) {
            try {
                s(line)
            } catch (ignored: Throwable) {
                // A broken sink must not break the caller.
            }
        }
    }

    private fun buildLine(level: String, msg: String, t: Throwable?): String {
        val sb = StringBuilder()
        sb.append(timeFormat.format(Date())).append(' ').append(level).append("/RM: ").append(msg)
        if (t != null) {
            sb.append(" | ").append(t.javaClass.name).append(": ").append(t.message)
            val st = t.stackTrace
            val n = if (st.size > 4) 4 else st.size
            for (i in 0 until n) sb.append("\n    at ").append(st[i].toString())
        }
        return sb.toString()
    }
}
