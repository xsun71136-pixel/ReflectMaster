package formatfa.reflectmaster.core

/**
 * Activation handshake.
 *
 * Both methods return a constant here; when LSPosed loads the module into its
 * **own** process, `formatfa.reflectmaster.hook.HookEntry` replaces the return
 * values with the real ones. The UI therefore learns "am I actually hooked?"
 * without any IPC of its own.
 *
 * For this to work the user must add 反射大师 itself to the module scope in the
 * LSPosed manager (the dashboard says so when it is missing).
 *
 * `@JvmStatic` is required: XposedHelpers resolves real static Java methods.
 */
object ModuleBridge {

    @JvmStatic
    fun isModuleActive(): Boolean = false

    /** XposedBridge API version reported by the framework (0 = not hooked). */
    @JvmStatic
    fun frameworkVersion(): Int = 0

    /** Human readable framework name, e.g. "LSPosed". */
    @JvmStatic
    fun frameworkName(): String = ""

    /** Config version the hook side last read - proves prefs are readable. */
    @JvmStatic
    fun loadedConfigVersion(): Long = 0L

    /** Number of user hooks successfully installed in the module's own process. */
    @JvmStatic
    fun installedHookCount(): Int = 0
}
