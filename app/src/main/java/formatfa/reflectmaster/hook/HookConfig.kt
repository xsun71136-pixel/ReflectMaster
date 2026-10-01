package formatfa.reflectmaster.hook

import de.robv.android.xposed.XSharedPreferences
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.MapSource
import formatfa.reflectmaster.core.PrefSource
import formatfa.reflectmaster.core.RmConfig
import formatfa.reflectmaster.core.RmLog
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.StringReader
import java.util.concurrent.Executors

/**
 * Three-tier configuration reader for the hooked process.
 *
 * The 1.x module did `getSharedPreferences(name, MODE_WORLD_READABLE)` inside
 * its own UI. That throws [SecurityException] on API 24+, the catch block fell
 * back to `MODE_PRIVATE`, and the file therefore became unreadable from the
 * target process - the module was installed, "activated", and then did absolutely
 * nothing. This is the single most important Android 11 fix in the rewrite.
 *
 * Order of attempts:
 *  1. [XSharedPreferences] - works whenever LSPosed's preference bridge is
 *     available (the normal case). Note that `canRead()` only exists from
 *     XposedBridge API 93; api-82.jar has `getFile()` / `hasFileChanged()`, so
 *     readability is probed through those.
 *  2. Direct XML parse of `/data/data/<module>/shared_prefs/rm_config.xml` -
 *     works when the file happens to be readable (rooted shell, some ROMs).
 *  3. [RemoteChannel.pullConfig] through the exported ContentProvider - always
 *     works but costs a binder round trip, so it is only ever used from a
 *     background thread.
 */
object HookConfig {

    /** Where the config actually came from; surfaced in the console + logs. */
    const val SOURCE_NONE = 0
    const val SOURCE_XSP = 1
    const val SOURCE_FILE = 2
    const val SOURCE_PROVIDER = 3

    @Volatile
    var current: RmConfig = RmConfig.EMPTY
        private set

    @Volatile
    var source: Int = SOURCE_NONE
        private set

    @Volatile
    var lastError: String? = null
        private set

    private val io = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "rm-config")
        t.isDaemon = true
        t
    }

    fun sourceName(): String = when (source) {
        SOURCE_XSP -> "XSharedPreferences"
        SOURCE_FILE -> "直接读取 XML"
        SOURCE_PROVIDER -> "ContentProvider"
        else -> "无（配置不可读）"
    }

    /**
     * Fast path - no binder, safe on the host's main thread.
     * Used on every console summon so a config change made seconds ago is picked
     * up without restarting the target app.
     */
    fun refreshSync(): RmConfig {
        val fromXsp = readViaXSharedPrefs()
        if (fromXsp != null) {
            current = fromXsp
            source = SOURCE_XSP
            return fromXsp
        }
        val fromFile = readViaFile()
        if (fromFile != null) {
            current = fromFile
            source = SOURCE_FILE
            return fromFile
        }
        // Keep whatever we already had; a background provider read may be in flight.
        if (source == SOURCE_NONE) lastError = "配置暂时不可读，正在尝试 ContentProvider"
        return current
    }

    /** Full path including the ContentProvider fallback. Never blocks the caller. */
    fun refreshAsync(onDone: ((RmConfig) -> Unit)? = null) {
        io.execute {
            try {
                refreshSync()
            } catch (t: Throwable) {
                RmLog.w("refreshSync 失败: " + t.message)
            }
            if (source == SOURCE_NONE || source == SOURCE_PROVIDER) {
                val map = RemoteChannel.pullConfig()
                if (map != null && map.isNotEmpty()) {
                    current = Config.parse(MapSource(map))
                    source = SOURCE_PROVIDER
                    lastError = null
                }
            }
            if (onDone != null) {
                try {
                    onDone.invoke(current)
                } catch (t: Throwable) {
                    RmLog.w("配置回调失败: " + t.message)
                }
            }
        }
    }

    // --------------------------------------------------------------- tier 1

    private fun readViaXSharedPrefs(): RmConfig? {
        return try {
            val xsp = XSharedPreferences(Config.MODULE_PACKAGE, Config.PREFS_NAME)
            xsp.reload()
            val file = try {
                xsp.file
            } catch (t: Throwable) {
                null
            }
            if (file != null && !file.canRead()) {
                lastError = "XSharedPreferences 文件不可读（SELinux）"
                return null
            }
            val all = try {
                xsp.all
            } catch (t: Throwable) {
                null
            }
            if (all.isNullOrEmpty()) {
                lastError = "XSharedPreferences 为空或未授权"
                return null
            }
            lastError = null
            Config.parse(XspSource(xsp))
        } catch (t: Throwable) {
            lastError = "XSharedPreferences 失败: " + t.javaClass.simpleName + " " + t.message
            null
        }
    }

    private class XspSource(private val xsp: XSharedPreferences) : PrefSource {
        override fun str(key: String, def: String?): String? = xsp.getString(key, def)
        override fun bool(key: String, def: Boolean): Boolean = xsp.getBoolean(key, def)
        override fun int(key: String, def: Int): Int = xsp.getInt(key, def)
        override fun long(key: String, def: Long): Long = xsp.getLong(key, def)
    }

    // --------------------------------------------------------------- tier 2

    private fun prefsFilePath(): String =
        "/data/data/" + Config.MODULE_PACKAGE + "/shared_prefs/" + Config.PREFS_NAME + ".xml"

    private fun readViaFile(): RmConfig? {
        return try {
            val f = File(prefsFilePath())
            if (!f.exists() || !f.canRead()) {
                lastError = "配置文件不可读: " + prefsFilePath()
                return null
            }
            val map = parsePrefsXml(f.readText())
            if (map.isEmpty()) {
                lastError = "配置文件为空"
                return null
            }
            lastError = null
            Config.parse(MapSource(map))
        } catch (t: Throwable) {
            lastError = "读取配置文件失败: " + t.message
            null
        }
    }

    /**
     * Minimal `shared_prefs` XML reader.
     *
     * Layout produced by Android:
     * `<string name="k">text</string>` (value is the element text) versus
     * `<boolean name="k" value="true" />` / `<int .../>` / `<long .../>` /
     * `<float .../>` (value is an attribute) and
     * `<set name="k"><string>a</string></set>` (ignored - not used by this app).
     */
    fun parsePrefsXml(xml: String): Map<String, String> {
        val out = HashMap<String, String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var pendingKey: String? = null
            var pendingTag: String? = null
            val text = StringBuilder()

            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        val tag = parser.name
                        if (tag != "map") {
                            val key = parser.getAttributeValue(null, "name")
                            if (key != null) {
                                if (tag == "string") {
                                    pendingKey = key
                                    pendingTag = tag
                                    text.setLength(0)
                                } else {
                                    val v = parser.getAttributeValue(null, "value")
                                    if (v != null) out[key] = v
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (pendingKey != null) text.append(parser.text ?: "")
                    }
                    XmlPullParser.END_TAG -> {
                        val tag = parser.name
                        if (pendingKey != null && tag == pendingTag) {
                            out[pendingKey!!] = text.toString()
                            pendingKey = null
                            pendingTag = null
                            text.setLength(0)
                        }
                    }
                }
                ev = parser.next()
            }
        } catch (t: Throwable) {
            RmLog.w("解析配置 XML 失败: " + t.message)
        }
        return out
    }
}
