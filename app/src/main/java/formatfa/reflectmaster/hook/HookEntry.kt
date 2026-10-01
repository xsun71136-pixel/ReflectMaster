package formatfa.reflectmaster.hook

import android.app.Activity
import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.ModuleBridge
import formatfa.reflectmaster.core.RmConfig
import formatfa.reflectmaster.core.RmLog
import formatfa.reflectmaster.overlay.ConsoleController

/**
 * Module entry point, listed in `assets/xposed_init`.
 *
 * LSPosed instantiates this once per process whose package is inside the module
 * scope. Every step is wrapped, because an exception escaping
 * `handleLoadPackage` aborts the whole hook installation for that process - the
 * 1.x entry class had no such guard.
 *
 * Activation contract: a process is instrumented only when it is listed in the
 * module's own target list. That list doubles as the access control list for
 * [formatfa.reflectmaster.provider.ConfigProvider], so the module never exposes
 * its configuration (which contains user scripts) to an app the user did not
 * explicitly opt in to.
 */
class HookEntry : IXposedHookLoadPackage {

    @Volatile
    private var logBridgeInstalled = false

    private var seenVersion = -1L

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            route(lpparam)
        } catch (t: Throwable) {
            safeLog("handleLoadPackage 失败: " + t)
        }
    }

    private fun route(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName

        // Our own process: only publish the activation handshake so the module UI
        // can tell the user whether the framework really loaded us.
        if (pkg == Config.MODULE_PACKAGE) {
            installLogBridge()
            installActivationBridge()
            return
        }

        // "android" is the system server. The console is useless there and the
        // lifecycle hooks would fire for framework internals.
        if (pkg == "android") return

        installLogBridge()
        RemoteChannel.init(currentApplication())

        // Tier 1/2 are a local file read (no binder), safe to do inline.
        val initial = HookConfig.refreshSync()
        if (initial.isTarget(pkg)) {
            activate(lpparam, initial)
            // Keep the slower tier-3 path warm for the next refresh.
            HookConfig.refreshAsync(null)
            return
        }

        if (HookConfig.source == HookConfig.SOURCE_NONE) {
            // Could not read the configuration at all: ask the module through its
            // ContentProvider before deciding, on a worker thread.
            HookConfig.refreshAsync { cfg ->
                if (cfg.isTarget(pkg)) activate(lpparam, cfg)
                else safeLog("配置可读但 " + pkg + " 不在目标列表，跳过")
            }
        } else {
            safeLog(pkg + " 不在目标列表，跳过（配置来源 " + HookConfig.sourceName() + "）")
        }
    }

    private fun activate(lpparam: XC_LoadPackage.LoadPackageParam, cfg: RmConfig) {
        safeLog(
            "目标进程已激活: " + lpparam.packageName +
                " 配置来源=" + HookConfig.sourceName() +
                " 配置版本=" + cfg.version
        )
        RmLog.i("模块已在 " + lpparam.packageName + " 中激活 (来源: " + HookConfig.sourceName() + ")")

        ActivityTracker.install(lpparam)

        KeyTrigger.setMode(cfg.trigger)
        KeyTrigger.onSummon = { ConsoleController.toggle() }
        KeyTrigger.install(lpparam)

        CustomHooks.install(lpparam, cfg.enabledHooks(lpparam.packageName))

        installConfigRefresher(lpparam)
    }

    /**
     * Publishes the real activation state into [ModuleBridge].
     *
     * Only works when the user put 反射大师 itself into the module scope; the
     * dashboard explains this while the state still reads "inactive".
     */
    private fun installActivationBridge() {
        try {
            hookReturning(ModuleBridge::class.java, "isModuleActive") { true }
            hookReturning(ModuleBridge::class.java, "frameworkVersion") { frameworkVersion() }
            hookReturning(ModuleBridge::class.java, "frameworkName") { frameworkName() }
            hookReturning(ModuleBridge::class.java, "loadedConfigVersion") {
                HookConfig.refreshSync().version
            }
            hookReturning(ModuleBridge::class.java, "installedHookCount") {
                CustomHooks.installedCount
            }
            safeLog(
                "模块自身进程：激活桥接已安装，" + frameworkName() +
                    " (XposedBridge API " + frameworkVersion() + ")"
            )
        } catch (t: Throwable) {
            safeLog("安装激活桥接失败: " + t)
        }
    }

    private fun hookReturning(cls: Class<*>, method: String, value: () -> Any) {
        XposedHelpers.findAndHookMethod(
            cls, method,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    param.result = value()
                }
            }
        )
    }

    private fun frameworkVersion(): Int = try {
        XposedBridge.getXposedVersion()
    } catch (t: Throwable) {
        0
    }

    private fun frameworkName(): String {
        val api = frameworkVersion()
        return when {
            api >= 93 -> "LSPosed / EdXposed 系"
            api > 0 -> "Xposed 兼容框架"
            else -> "未知框架"
        }
    }

    /** Route [RmLog] into XposedBridge.log and into the module's log inbox. */
    private fun installLogBridge() {
        if (logBridgeInstalled) return
        logBridgeInstalled = true
        RmLog.addSink { line ->
            safeLog(line)
            RemoteChannel.pushLog(line)
        }
    }

    /**
     * Re-reads the configuration whenever an Activity resumes.
     *
     * A local file read, cheap enough to run on the main thread, and it is what
     * makes "toggle a rule in the module UI, switch back to the target app"
     * work without a restart.
     */
    private fun installConfigRefresher(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", lpparam.classLoader, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        onActivityResumed(lpparam, activity)
                    }
                }
            )
        } catch (t: Throwable) {
            safeLog("安装配置刷新失败: " + t)
        }
    }

    private fun onActivityResumed(
        lpparam: XC_LoadPackage.LoadPackageParam,
        activity: Activity
    ) {
        try {
            val cfg = HookConfig.refreshSync()
            if (!cfg.isTarget(lpparam.packageName)) {
                ConsoleController.detachAll()
                return
            }
            KeyTrigger.setMode(cfg.trigger)
            if (cfg.version != seenVersion) {
                seenVersion = cfg.version
                CustomHooks.install(lpparam, cfg.enabledHooks(lpparam.packageName))
            }
            if (cfg.consoleEnabled && cfg.trigger == Config.TRIGGER_BUBBLE) {
                ConsoleController.attachBubble(activity, cfg)
            } else {
                ConsoleController.detachBubble()
            }
        } catch (t: Throwable) {
            safeLog("onResume 刷新失败: " + t)
        }
    }

    private fun currentApplication(): Application? = try {
        val at = Class.forName("android.app.ActivityThread")
        val m = at.getMethod("currentApplication")
        m.invoke(null) as? Application
    } catch (t: Throwable) {
        null
    }

    private fun safeLog(msg: String) {
        try {
            XposedBridge.log(msg)
        } catch (ignored: Throwable) {
        }
    }
}
