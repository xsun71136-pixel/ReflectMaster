package formatfa.reflectmaster.hook

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.RmLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hook-side client for `formatfa.reflectmaster.provider.ConfigProvider`.
 *
 * Runs inside the **target** application process. Everything here is defensive:
 * a module must never be the reason a host app crashes or ANRs, so every binder
 * call is wrapped, throttled and executed on a private worker thread.
 *
 * Column names are duplicated on purpose instead of referencing
 * [formatfa.reflectmaster.provider.ConfigProvider]: keeping this class free of
 * module-only types means the hook side never has to resolve a class that pulls
 * in a ContentProvider subclass inside a foreign process.
 */
object RemoteChannel {

    private const val COL_KEY = "key"
    private const val COL_VALUE = "value"
    private const val COL_LINE = "line"
    private const val COL_NAME = "name"
    private const val COL_DATA = "data"
    private const val COL_APPEND = "append"

    /** Binder transactions are limited to ~1 MB; stay well below it. */
    private const val CHUNK = 400 * 1024

    @Volatile
    private var appContext: Context? = null

    private val started = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<String>(512)

    private val worker = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()
    ).apply { allowCoreThreadTimeOut(true) }

    @Volatile
    var reachable: Boolean? = null
        private set

    fun init(context: Context?) {
        if (context == null) return
        appContext = context.applicationContext ?: context
    }

    private fun uri(path: String): Uri = Uri.parse(Config.uriOf(path))

    /** Queue a log line; drops silently when the queue is full. */
    fun pushLog(line: String) {
        if (!started.get()) startWorker()
        queue.offer(line)
    }

    private fun startWorker() {
        if (!started.compareAndSet(false, true)) return
        worker.execute {
            try {
                while (true) {
                    val line = try {
                        queue.poll(2, TimeUnit.SECONDS)
                    } catch (t: InterruptedException) {
                        null
                    }
                    // Idle for 2s -> let the thread go away. A module must not
                    // leave a permanently parked thread inside the host app.
                    if (line == null) break
                    doPushLog(line)
                }
            } finally {
                started.set(false)
                if (!queue.isEmpty()) startWorker()
            }
        }
    }

    private fun doPushLog(line: String) {
        val ctx = appContext ?: return
        try {
            val values = ContentValues()
            values.put(COL_LINE, line)
            ctx.contentResolver.insert(uri(Config.PATH_LOG), values)
            reachable = true
        } catch (t: Throwable) {
            reachable = false
        }
    }

    /** Blocking; call from a worker thread only. */
    fun pullConfig(): Map<String, String>? {
        val ctx = appContext ?: return null
        var cursor: android.database.Cursor? = null
        return try {
            cursor = ctx.contentResolver.query(uri(Config.PATH_CONFIG), null, null, null, null)
            if (cursor == null) {
                reachable = false
                null
            } else {
                val ki = cursor.getColumnIndex(COL_KEY)
                val vi = cursor.getColumnIndex(COL_VALUE)
                if (ki < 0 || vi < 0) {
                    emptyMap()
                } else {
                    val out = HashMap<String, String>()
                    while (cursor.moveToNext()) {
                        val k = cursor.getString(ki) ?: continue
                        out[k] = cursor.getString(vi) ?: ""
                    }
                    reachable = true
                    out
                }
            }
        } catch (t: Throwable) {
            reachable = false
            null
        } finally {
            try {
                cursor?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /**
     * Store a binary artefact in the module inbox (chunked).
     * Blocking; call from a worker thread.
     */
    fun pushFile(name: String, data: ByteArray): Boolean {
        val ctx = appContext ?: return false
        var offset = 0
        var first = true
        return try {
            while (offset < data.size) {
                val end = if (offset + CHUNK > data.size) data.size else offset + CHUNK
                val piece = data.copyOfRange(offset, end)
                val values = ContentValues()
                values.put(COL_NAME, name)
                values.put(COL_DATA, piece)
                values.put(COL_APPEND, if (first) 0 else 1)
                val r = ctx.contentResolver.insert(uri(Config.PATH_INBOX), values)
                if (r == null) return false
                first = false
                offset = end
            }
            reachable = true
            data.isNotEmpty()
        } catch (t: Throwable) {
            reachable = false
            false
        }
    }

    fun clearRemoteLog() {
        val ctx = appContext ?: return
        worker.execute {
            try {
                ctx.contentResolver.delete(uri(Config.PATH_LOG), null, null)
            } catch (t: Throwable) {
                RmLog.w("清理远端日志失败: " + t.message)
            }
        }
    }

    /** Persist the bubble position. Blocking; call from a worker thread. */
    fun pushPos(x: Int, y: Int) {
        val ctx = appContext ?: return
        worker.execute {
            try {
                val values = ContentValues()
                values.put("x", x)
                values.put("y", y)
                ctx.contentResolver.insert(uri(Config.PATH_POS), values)
            } catch (t: Throwable) {
                // Purely cosmetic state; ignore.
            }
        }
    }

    /** Blocking listing of the module inbox; call from a worker thread. */
    fun listInbox(): List<Pair<String, Long>>? {
        val ctx = appContext ?: return null
        var cursor: android.database.Cursor? = null
        return try {
            cursor = ctx.contentResolver.query(uri(Config.PATH_INBOX), null, null, null, null)
            if (cursor == null) {
                reachable = false
                null
            } else {
                val ni = cursor.getColumnIndex("name")
                val si = cursor.getColumnIndex("size")
                val out = ArrayList<Pair<String, Long>>()
                while (cursor.moveToNext()) {
                    val n = if (ni >= 0) cursor.getString(ni) else null
                    if (n != null) out.add(Pair(n, if (si >= 0) cursor.getLong(si) else 0L))
                }
                reachable = true
                out
            }
        } catch (t: Throwable) {
            reachable = false
            null
        } finally {
            try {
                cursor?.close()
            } catch (ignored: Throwable) {
            }
        }
    }
}
