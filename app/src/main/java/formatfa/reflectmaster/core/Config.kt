package formatfa.reflectmaster.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Abstract preference view.
 *
 * The very same parsing code has to run in three different places:
 *  - the module UI process (real [android.content.SharedPreferences])
 *  - the hooked target process ([de.robv.android.xposed.XSharedPreferences])
 *  - the hooked target process reading through [formatfa.reflectmaster.provider.ConfigProvider]
 *    (plain String map)
 *
 * Keeping the parser behind this tiny interface means there is exactly one
 * definition of "what the configuration looks like", which the 1.x code base
 * never had (keys were duplicated as string literals in 6 different classes).
 *
 * Deliberately free of any androidx / Xposed import so it is safe to touch from
 * inside a foreign process.
 */
interface PrefSource {
    fun str(key: String, def: String?): String?
    fun bool(key: String, def: Boolean): Boolean
    fun int(key: String, def: Int): Int
    fun long(key: String, def: Long): Long
}

/** A FakeScript snippet stored by the user. */
data class ScriptItem(
    val name: String,
    val code: String
)

/**
 * One user defined method hook.
 *
 * Replaces the 1.x "script2" format which smuggled class name, method name and
 * parameter types into a single space separated string prefixed by "bf"/"af".
 * That format broke on every class name containing a space and could not express
 * an empty parameter list unambiguously.
 */
data class HookRule(
    val name: String,
    val pkg: String,
    val className: String,
    val methodName: String,
    val paramTypes: List<String>,
    val after: Boolean,
    val code: String,
    val enabled: Boolean
) {
    /** Signature used for hooking, e.g. `com.foo.Bar#doIt(int,java.lang.String)`. */
    val signature: String
        get() {
            val sb = StringBuilder()
            sb.append(className).append('#').append(methodName).append('(')
            for (i in paramTypes.indices) {
                if (i > 0) sb.append(',')
                sb.append(paramTypes[i])
            }
            return sb.append(')').toString()
        }
}

/** Immutable snapshot of every setting the hook side needs. */
data class RmConfig(
    val version: Long,
    val targets: List<String>,
    val consoleEnabled: Boolean,
    val trigger: Int,
    val panelSizeDp: Int,
    val panelAlphaPct: Int,
    val darkPanel: Boolean,
    val rememberPos: Boolean,
    val systemOverlay: Boolean,
    val bubbleX: Int,
    val bubbleY: Int,
    val scripts: List<ScriptItem>,
    val hooks: List<HookRule>
) {
    fun isTarget(pkg: String?): Boolean = pkg != null && targets.contains(pkg)

    fun enabledHooks(pkg: String): List<HookRule> =
        hooks.filter { it.enabled && (it.pkg.isEmpty() || it.pkg == pkg) }

    companion object {
        val EMPTY = RmConfig(
            version = 0L,
            targets = emptyList(),
            consoleEnabled = Config.DEFAULT_CONSOLE_ENABLED,
            trigger = Config.DEFAULT_TRIGGER,
            panelSizeDp = Config.DEFAULT_PANEL_SIZE_DP,
            panelAlphaPct = Config.DEFAULT_PANEL_ALPHA,
            darkPanel = Config.DEFAULT_DARK_PANEL,
            rememberPos = Config.DEFAULT_REMEMBER_POS,
            systemOverlay = Config.DEFAULT_SYSTEM_OVERLAY,
            bubbleX = -1,
            bubbleY = -1,
            scripts = emptyList(),
            hooks = emptyList()
        )
    }
}

/**
 * Every preference key, default value and serialisation rule in one place.
 */
object Config {

    const val MODULE_PACKAGE = "formatfa.reflectmaster"
    const val PROVIDER_AUTHORITY = "formatfa.reflectmaster.config"

    /**
     * Preference file name.
     *
     * NOTE: this must stay the plain default name because the hook side reads it
     * through `XSharedPreferences(MODULE_PACKAGE, PREFS_NAME)`, which resolves to
     * `/data/data/<pkg>/shared_prefs/<name>.xml`.
     */
    const val PREFS_NAME = "rm_config"

    const val KEY_VERSION = "version"
    const val KEY_TARGETS = "targets"
    const val KEY_CONSOLE_ENABLED = "console_enabled"
    const val KEY_TRIGGER = "trigger"
    const val KEY_PANEL_SIZE = "panel_size"
    const val KEY_PANEL_ALPHA = "panel_alpha"
    const val KEY_DARK_PANEL = "dark_panel"
    const val KEY_REMEMBER_POS = "remember_pos"
    const val KEY_SYSTEM_OVERLAY = "system_overlay"
    const val KEY_BUBBLE_X = "bubble_x"
    const val KEY_BUBBLE_Y = "bubble_y"
    const val KEY_SHOW_SYSTEM_APPS = "show_system_apps"
    const val KEY_ONLY_SELECTED = "only_selected"
    const val KEY_SCRIPTS = "scripts"
    const val KEY_HOOKS = "hooks"

    /** URI of a provider path; shared with the hook side (`hook.RemoteChannel`). */
    fun uriOf(path: String): String = "content://" + PROVIDER_AUTHORITY + "/" + path

    /** ContentProvider paths, shared with `hook.RemoteChannel` (hook side). */
    const val PATH_CONFIG = "config"
    const val PATH_LOG = "log"
    const val PATH_INBOX = "inbox"
    const val PATH_POS = "pos"

    /** Console summon triggers. */
    const val TRIGGER_NONE = 0
    const val TRIGGER_VOLUME_LONG = 1
    const val TRIGGER_VOLUME_COMBO = 2
    const val TRIGGER_BUBBLE = 3

    const val DEFAULT_CONSOLE_ENABLED = true
    const val DEFAULT_TRIGGER = TRIGGER_VOLUME_LONG
    const val DEFAULT_PANEL_SIZE_DP = 340
    const val DEFAULT_PANEL_ALPHA = 96
    const val DEFAULT_DARK_PANEL = true
    const val DEFAULT_REMEMBER_POS = true
    const val DEFAULT_SYSTEM_OVERLAY = false
    const val MIN_PANEL_SIZE_DP = 220
    const val MAX_PANEL_SIZE_DP = 620

    /** Bumped on every write so the hook side can cheaply detect staleness. */
    fun nextVersion(): Long = System.currentTimeMillis()

    // ---------------------------------------------------------------- parse

    fun parse(src: PrefSource): RmConfig = RmConfig(
        version = src.long(KEY_VERSION, 0L),
        targets = parseStringList(src.str(KEY_TARGETS, null)),
        consoleEnabled = src.bool(KEY_CONSOLE_ENABLED, DEFAULT_CONSOLE_ENABLED),
        trigger = src.int(KEY_TRIGGER, DEFAULT_TRIGGER),
        panelSizeDp = src.int(KEY_PANEL_SIZE, DEFAULT_PANEL_SIZE_DP)
            .coerceIn(MIN_PANEL_SIZE_DP, MAX_PANEL_SIZE_DP),
        panelAlphaPct = src.int(KEY_PANEL_ALPHA, DEFAULT_PANEL_ALPHA).coerceIn(30, 100),
        darkPanel = src.bool(KEY_DARK_PANEL, DEFAULT_DARK_PANEL),
        rememberPos = src.bool(KEY_REMEMBER_POS, DEFAULT_REMEMBER_POS),
        systemOverlay = src.bool(KEY_SYSTEM_OVERLAY, DEFAULT_SYSTEM_OVERLAY),
        bubbleX = src.int(KEY_BUBBLE_X, -1),
        bubbleY = src.int(KEY_BUBBLE_Y, -1),
        scripts = parseScripts(src.str(KEY_SCRIPTS, null)),
        hooks = parseHooks(src.str(KEY_HOOKS, null))
    )

    fun parseStringList(json: String?): List<String> {
        val out = ArrayList<String>()
        if (json.isNullOrEmpty()) return out
        // The 1.x format was a bare "a,b,c" string; still accepted on read so an
        // upgrade does not silently drop the user's target list.
        if (!json.startsWith("[")) {
            for (part in json.split(',')) {
                val t = part.trim()
                if (t.isNotEmpty()) out.add(t)
            }
            return out
        }
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val t = arr.optString(i, "").trim()
                if (t.isNotEmpty()) out.add(t)
            }
            out
        } catch (e: Exception) {
            out
        }
    }

    fun parseScripts(json: String?): List<ScriptItem> {
        val out = ArrayList<ScriptItem>()
        if (json.isNullOrEmpty()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name", "")
                if (name.isEmpty()) continue
                out.add(ScriptItem(name, o.optString("code", "")))
            }
        } catch (e: Exception) {
            // Corrupt payload: keep whatever parsed so far instead of crashing
            // inside the target process.
        }
        return out
    }

    fun parseHooks(json: String?): List<HookRule> {
        val out = ArrayList<HookRule>()
        if (json.isNullOrEmpty()) return out
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val cls = o.optString("cls", "")
                val method = o.optString("method", "")
                if (cls.isEmpty() || method.isEmpty()) continue
                val params = ArrayList<String>()
                val pa = o.optJSONArray("params")
                if (pa != null) {
                    for (k in 0 until pa.length()) {
                        val t = pa.optString(k, "").trim()
                        if (t.isNotEmpty()) params.add(t)
                    }
                }
                out.add(
                    HookRule(
                        name = o.optString("name", method),
                        pkg = o.optString("pkg", ""),
                        className = cls,
                        methodName = method,
                        paramTypes = params,
                        after = o.optInt("timing", 0) == 1,
                        code = o.optString("code", ""),
                        enabled = o.optBoolean("enabled", true)
                    )
                )
            }
        } catch (e: Exception) {
            // see parseScripts
        }
        return out
    }

    // -------------------------------------------------------------- serialise

    fun writeStringList(values: List<String>): String {
        val arr = JSONArray()
        for (v in values) arr.put(v)
        return arr.toString()
    }

    fun writeScripts(items: List<ScriptItem>): String {
        val arr = JSONArray()
        for (s in items) {
            val o = JSONObject()
            o.put("name", s.name)
            o.put("code", s.code)
            arr.put(o)
        }
        return arr.toString()
    }

    fun writeHooks(items: List<HookRule>): String {
        val arr = JSONArray()
        for (h in items) {
            val o = JSONObject()
            o.put("name", h.name)
            o.put("pkg", h.pkg)
            o.put("cls", h.className)
            o.put("method", h.methodName)
            val pa = JSONArray()
            for (p in h.paramTypes) pa.put(p)
            o.put("params", pa)
            o.put("timing", if (h.after) 1 else 0)
            o.put("code", h.code)
            o.put("enabled", h.enabled)
            arr.put(o)
        }
        return arr.toString()
    }
}
