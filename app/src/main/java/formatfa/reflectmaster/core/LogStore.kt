package formatfa.reflectmaster.core

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * On-disk sink for log lines and small binary artefacts produced **inside the
 * hooked process**.
 *
 * A Xposed module cannot write into its own data directory from another app's
 * process, and Android 11 scoped storage removed `/sdcard` as a shared scratch
 * area (which is where 1.x dumped `Reflect.data` and screenshots). Everything
 * therefore travels through [formatfa.reflectmaster.provider.ConfigProvider] and
 * is persisted here, in the module's private storage, from where the UI can read
 * and export it.
 */
object LogStore {

    private const val LOG_FILE = "rm_log.txt"
    private const val MAX_LOG_BYTES = 512L * 1024L
    private const val MAX_INBOX_FILE = 8L * 1024L * 1024L
    private const val INBOX_DIR = "rm_inbox"

    private val io = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "rm-logstore")
        t.isDaemon = true
        t
    }

    private fun logFile(ctx: Context): File = File(ctx.filesDir, LOG_FILE)

    private fun inboxDir(ctx: Context): File {
        val d = File(ctx.filesDir, INBOX_DIR)
        if (!d.exists()) d.mkdirs()
        return d
    }

    // ------------------------------------------------------------------- log

    fun append(ctx: Context, line: String) {
        val app = ctx.applicationContext
        io.execute {
            try {
                val f = logFile(app)
                if (f.exists() && f.length() > MAX_LOG_BYTES) trim(f)
                f.appendText(line + "\n")
            } catch (t: Throwable) {
                // Never let logging break the host app.
            }
        }
    }

    private fun trim(f: File) {
        try {
            RandomAccessFile(f, "rw").use { raf ->
                val len = raf.length()
                val keep = MAX_LOG_BYTES / 2
                val tail = ByteArray(keep.toInt())
                raf.seek(len - keep)
                val read = raf.read(tail)
                raf.setLength(0)
                raf.seek(0)
                if (read > 0) raf.write(tail, 0, read)
            }
        } catch (t: Throwable) {
            try {
                f.delete()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** Blocking read; call from a background thread. Newest lines last. */
    fun readLines(ctx: Context, maxLines: Int): List<String> {
        return try {
            val f = logFile(ctx.applicationContext)
            if (!f.exists()) return emptyList()
            val all = f.readLines()
            if (all.size > maxLines) all.subList(all.size - maxLines, all.size) else all
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        io.execute {
            try {
                logFile(app).delete()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun logFileForExport(ctx: Context): File = logFile(ctx.applicationContext)

    // ----------------------------------------------------------------- inbox

    /**
     * Write (or append to) a file in the inbox.
     * @param name relative file name; path separators are stripped.
     * @return the stored file, or null on failure.
     */
    fun inboxWrite(ctx: Context, name: String, data: ByteArray, append: Boolean): File? {
        val safe = sanitize(name)
        if (safe.isEmpty()) return null
        val app = ctx.applicationContext
        var out: File? = null
        // Executed synchronously: the caller (a binder thread) needs the result.
        try {
            val f = File(inboxDir(app), safe)
            if (append && f.exists() && f.length() + data.size > MAX_INBOX_FILE) return null
            if (!append && data.size.toLong() > MAX_INBOX_FILE) return null
            if (append) f.appendBytes(data) else f.writeBytes(data)
            out = f
        } catch (t: Throwable) {
            out = null
        }
        return out
    }

    fun inboxList(ctx: Context): List<File> {
        val files = inboxDir(ctx.applicationContext).listFiles() ?: return emptyList()
        val out = files.toMutableList()
        out.sortByDescending { it.lastModified() }
        return out
    }

    fun inboxRead(ctx: Context, name: String): ByteArray? = try {
        val f = File(inboxDir(ctx.applicationContext), sanitize(name))
        if (f.exists()) f.readBytes() else null
    } catch (t: Throwable) {
        null
    }

    fun inboxClear(ctx: Context) {
        val app = ctx.applicationContext
        io.execute {
            try {
                inboxDir(app).listFiles()?.forEach { it.delete() }
            } catch (ignored: Throwable) {
            }
        }
    }

    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = StringBuilder(base.length)
        for (c in base) {
            if (c.isLetterOrDigit() || c == '.' || c == '_' || c == '-' || c == '(' || c == ')') {
                cleaned.append(c)
            } else {
                cleaned.append('_')
            }
        }
        val s = cleaned.toString()
        return if (s == "." || s == "..") "" else s
    }
}
