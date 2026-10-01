package formatfa.reflectmaster.core

import android.content.Context
import android.content.SharedPreferences

/** [PrefSource] backed by a real [SharedPreferences] (module process only). */
class SharedPrefsSource(private val sp: SharedPreferences) : PrefSource {
    override fun str(key: String, def: String?): String? = sp.getString(key, def)
    override fun bool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    override fun int(key: String, def: Int): Int = sp.getInt(key, def)
    override fun long(key: String, def: Long): Long = sp.getLong(key, def)
}

/** [PrefSource] backed by a plain String map (used by the ContentProvider path). */
class MapSource(private val map: Map<String, String>) : PrefSource {
    override fun str(key: String, def: String?): String? = map[key] ?: def
    // Deliberately avoids kotlin.text.toBooleanStrictOrNull / String.lowercase:
    // both are Kotlin 1.5+ stdlib additions, and inside a hooked process the
    // kotlin-stdlib that wins may be the host app's (older) copy.
    override fun bool(key: String, def: Boolean): Boolean {
        val v = map[key] ?: return def
        if ("true".equals(v, ignoreCase = true)) return true
        if ("false".equals(v, ignoreCase = true)) return false
        return def
    }
    override fun int(key: String, def: Int): Int = map[key]?.toIntOrNull() ?: def
    override fun long(key: String, def: Long): Long = map[key]?.toLongOrNull() ?: def
}

/**
 * Single write path for every setting.
 *
 * Every mutation bumps [Config.KEY_VERSION] and uses `apply()`. The 1.x code
 * mixed `commit()`/`apply()`, wrote the target list in two different formats and
 * used `MODE_WORLD_READABLE`, which throws [SecurityException] on API 24+ - the
 * catch-then-fallback there is exactly why the module silently stopped working
 * on modern Android.
 */
object ConfigStore {

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): RmConfig = Config.parse(SharedPrefsSource(prefs(ctx)))

    private fun write(ctx: Context, body: (SharedPreferences.Editor) -> Unit) {
        val editor = prefs(ctx).edit()
        body(editor)
        editor.putLong(Config.KEY_VERSION, Config.nextVersion())
        editor.apply()
    }

    fun saveTargets(ctx: Context, targets: List<String>) = write(ctx) {
        it.putString(Config.KEY_TARGETS, Config.writeStringList(targets))
    }

    fun saveScripts(ctx: Context, items: List<ScriptItem>) = write(ctx) {
        it.putString(Config.KEY_SCRIPTS, Config.writeScripts(items))
    }

    fun saveHooks(ctx: Context, items: List<HookRule>) = write(ctx) {
        it.putString(Config.KEY_HOOKS, Config.writeHooks(items))
    }

    fun saveConsole(
        ctx: Context,
        enabled: Boolean,
        trigger: Int,
        panelSizeDp: Int,
        alphaPct: Int,
        dark: Boolean,
        rememberPos: Boolean,
        systemOverlay: Boolean
    ) = write(ctx) {
        it.putBoolean(Config.KEY_CONSOLE_ENABLED, enabled)
        it.putInt(Config.KEY_TRIGGER, trigger)
        it.putInt(Config.KEY_PANEL_SIZE, panelSizeDp)
        it.putInt(Config.KEY_PANEL_ALPHA, alphaPct)
        it.putBoolean(Config.KEY_DARK_PANEL, dark)
        it.putBoolean(Config.KEY_REMEMBER_POS, rememberPos)
        it.putBoolean(Config.KEY_SYSTEM_OVERLAY, systemOverlay)
    }

    fun saveBubblePos(ctx: Context, x: Int, y: Int) = write(ctx) {
        it.putInt(Config.KEY_BUBBLE_X, x)
        it.putInt(Config.KEY_BUBBLE_Y, y)
    }

    /**
     * Position is cosmetic. Writing it must **not** bump [Config.KEY_VERSION],
     * otherwise every bubble drag would make the hook side believe the rule set
     * changed and reinstall all user hooks on the next Activity resume.
     */
    fun saveBubblePosSilently(ctx: Context, x: Int, y: Int) {
        prefs(ctx).edit()
            .putInt(Config.KEY_BUBBLE_X, x)
            .putInt(Config.KEY_BUBBLE_Y, y)
            .apply()
    }

    /** UI-only flags: they never need to reach the hook side but still live here. */
    fun saveUiFlag(ctx: Context, key: String, value: Boolean) = write(ctx) {
        it.putBoolean(key, value)
    }

    fun uiFlag(ctx: Context, key: String, def: Boolean): Boolean =
        prefs(ctx).getBoolean(key, def)
}
