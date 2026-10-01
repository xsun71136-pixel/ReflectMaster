package formatfa.reflectmaster.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.ConfigStore
import formatfa.reflectmaster.core.LogStore
import formatfa.reflectmaster.core.RmLog

/**
 * Cross-process bridge between the hooked target app and the module.
 *
 * Why this exists: on Android 11 a module's `shared_prefs` file is unreadable
 * from another process (SELinux), and `MODE_WORLD_READABLE` - the trick the 1.x
 * build used - throws [SecurityException] since API 24. LSPosed normally restores
 * [de.robv.android.xposed.XSharedPreferences]; when it cannot, the hook side
 * falls back to querying `config` here.
 *
 * The same channel carries logs and binary artefacts (screenshots of a Drawable,
 * exported byte[]) back out of the target process, which scoped storage no
 * longer allows through `/sdcard`.
 *
 * Security: every call is gated on `getCallingPackage()` being one of the
 * configured targets. The module's own process is always allowed.
 */
class ConfigProvider : ContentProvider() {

    companion object {
        private const val CODE_CONFIG = 1
        private const val CODE_LOG = 2
        private const val CODE_INBOX = 3
        private const val CODE_POS = 4

        const val COL_KEY = "key"
        const val COL_VALUE = "value"
        const val COL_LINE = "line"
        const val COL_NAME = "name"
        const val COL_SIZE = "size"
        const val COL_DATA = "data"
        const val COL_MIME = "mime"
        const val COL_APPEND = "append"
        const val COL_X = "x"
        const val COL_Y = "y"

        private val MATCHER = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(Config.PROVIDER_AUTHORITY, Config.PATH_CONFIG, CODE_CONFIG)
            addURI(Config.PROVIDER_AUTHORITY, Config.PATH_LOG, CODE_LOG)
            addURI(Config.PROVIDER_AUTHORITY, Config.PATH_INBOX, CODE_INBOX)
            addURI(Config.PROVIDER_AUTHORITY, Config.PATH_POS, CODE_POS)
        }

        fun configUri(): Uri = Uri.parse(Config.uriOf(Config.PATH_CONFIG))
        fun logUri(): Uri = Uri.parse(Config.uriOf(Config.PATH_LOG))
        fun inboxUri(): Uri = Uri.parse(Config.uriOf(Config.PATH_INBOX))
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "vnd.android.cursor.dir/vnd.rm.config"

    /** true when [pkg] may talk to us. */
    private fun allowed(pkg: String?): Boolean {
        val ctx = context ?: return false
        if (pkg.isNullOrEmpty()) return false
        if (pkg == ctx.packageName) return true
        return try {
            ConfigStore.load(ctx).targets.contains(pkg)
        } catch (t: Throwable) {
            false
        }
    }

    private fun caller(): String? = try {
        callingPackage
    } catch (t: Throwable) {
        null
    }

    private fun emptyCursor(columns: Array<String>) = MatrixCursor(columns)

    override fun query(uri: Uri, projection: Array<String>?, selection: String?,
                       selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val ctx = context ?: return null
        val who = caller()
        if (!allowed(who)) {
            RmLog.w("ConfigProvider 拒绝了来自 " + (who ?: "<unknown>") + " 的查询")
            return emptyCursor(arrayOf(COL_KEY, COL_VALUE))
        }
        return when (MATCHER.match(uri)) {
            CODE_CONFIG -> {
                val sp = ConfigStore.prefs(ctx)
                val cursor = MatrixCursor(arrayOf(COL_KEY, COL_VALUE))
                for ((k, v) in sp.all) {
                    cursor.addRow(arrayOf(k, v?.toString() ?: ""))
                }
                cursor
            }
            CODE_LOG -> {
                val limit = sortOrder?.toIntOrNull() ?: 400
                val cursor = MatrixCursor(arrayOf(COL_LINE))
                for (line in LogStore.readLines(ctx, limit)) cursor.addRow(arrayOf(line))
                cursor
            }
            CODE_INBOX -> {
                val cursor = MatrixCursor(arrayOf(COL_NAME, COL_SIZE))
                for (f in LogStore.inboxList(ctx)) cursor.addRow(arrayOf(f.name, f.length()))
                cursor
            }
            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        val v = values ?: return null
        if (!allowed(caller())) return null
        return when (MATCHER.match(uri)) {
            CODE_LOG -> {
                val line = v.getAsString(COL_LINE)
                if (!line.isNullOrEmpty()) LogStore.append(ctx, line)
                uri
            }
            CODE_INBOX -> {
                val name = v.getAsString(COL_NAME) ?: return null
                val data = v.getAsByteArray(COL_DATA) ?: return null
                val append = (v.getAsInteger(COL_APPEND) ?: 0) == 1
                val f = LogStore.inboxWrite(ctx, name, data, append)
                if (f == null) null else Uri.withAppendedPath(uri, f.name)
            }
            CODE_POS -> {
                val x = v.getAsInteger(COL_X) ?: return null
                val y = v.getAsInteger(COL_Y) ?: return null
                ConfigStore.saveBubblePosSilently(ctx, x, y)
                uri
            }
            else -> null
        }
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val ctx = context ?: return 0
        if (!allowed(caller())) return 0
        return when (MATCHER.match(uri)) {
            CODE_LOG -> {
                LogStore.clear(ctx)
                1
            }
            CODE_INBOX -> {
                LogStore.inboxClear(ctx)
                1
            }
            else -> 0
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<String>?): Int = 0
}
