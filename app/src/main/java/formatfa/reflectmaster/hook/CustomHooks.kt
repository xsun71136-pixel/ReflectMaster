package formatfa.reflectmaster.hook

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import formatfa.reflectmaster.core.HookRule
import formatfa.reflectmaster.core.RmLog
import formatfa.reflectmaster.reflect.Reflect
import java.lang.reflect.Method
import java.lang.reflect.Member
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Installs the user defined method hooks described by [HookRule]s.
 *
 * Differences from 1.x:
 *  - rules are structured data, not a `"bf" + class + " " + method + " " + types`
 *    string that broke on the first space in a class name;
 *  - a rule whose class or method cannot be resolved is skipped with a log line
 *    instead of throwing out of `handleLoadPackage` (which in the old code
 *    aborted *every* remaining hook for that package);
 *  - the script runs inline on the caller's thread, so `rf.setResult()` and
 *    `rf.setArg()` actually take effect. The old after-hook spawned a new thread
 *    per invocation, which made result mutation a no-op and leaked threads;
 *  - hooks can be uninstalled again, so toggling a rule works after the next
 *    config refresh without restarting the host app.
 */
object CustomHooks {

    private val unhooks = CopyOnWriteArrayList<XC_MethodHook.Unhook>()
    private val failures = CopyOnWriteArrayList<String>()

    @Volatile
    var installedCount: Int = 0
        private set

    fun failureSummary(): List<String> = failures.toList()

    fun uninstallAll() {
        for (u in unhooks) {
            try {
                u.unhook()
            } catch (t: Throwable) {
                RmLog.w("卸载 hook 失败: " + t.message)
            }
        }
        unhooks.clear()
        installedCount = 0
    }

    fun install(lpparam: XC_LoadPackage.LoadPackageParam, rules: List<HookRule>) {
        uninstallAll()
        failures.clear()
        if (rules.isEmpty()) return

        var ok = 0
        for (rule in rules) {
            try {
                if (installOne(lpparam, rule)) ok++
            } catch (t: Throwable) {
                val msg = rule.signature + " -> " + t.javaClass.simpleName + ": " + t.message
                failures.add(msg)
                RmLog.w("安装 hook 失败: " + msg)
            }
        }
        installedCount = ok
        RmLog.i("自定义 hook 已安装 " + ok + "/" + rules.size + " 条")
    }

    private fun installOne(lpparam: XC_LoadPackage.LoadPackageParam, rule: HookRule): Boolean {
        val cls = XposedHelpers.findClassIfExists(rule.className, lpparam.classLoader)
        if (cls == null) {
            val msg = rule.signature + " -> 找不到类"
            failures.add(msg)
            RmLog.w(msg)
            return false
        }

        val member: Member? = resolveMember(cls, rule, lpparam.classLoader)
        if (member == null) {
            val msg = rule.signature + " -> 找不到方法"
            failures.add(msg)
            RmLog.w(msg)
            return false
        }

        val callback = ScriptHookCallback(rule)
        val unhook = XposedBridge.hookMethod(member, callback)
        if (unhook == null) {
            failures.add(rule.signature + " -> hookMethod 返回 null")
            return false
        }
        unhooks.add(unhook)
        RmLog.i("已挂钩 " + rule.signature + (if (rule.after) " (after)" else " (before)"))
        return true
    }

    private fun resolveMember(
        cls: Class<*>,
        rule: HookRule,
        loader: ClassLoader?
    ): Member? {
        // Reflect.findMethod resolves each declared parameter type, prefers an
        // exact type match anywhere in the hierarchy and finally falls back to
        // an arity match - which also tolerates `int` written as `Integer`.
        // Deliberately avoids XposedHelpers.findMethodExactIfExists: its
        // Object... signature would need a vararg spread of Class objects.
        val exact = Reflect.findMethod(cls, rule.methodName, rule.paramTypes, loader)
        if (exact != null) return exact

        // Last resort: when the user did not specify parameter types at all and
        // there is exactly one method with that name, hook it.
        if (rule.paramTypes.isEmpty()) {
            val all = Reflect.methods(cls, includeSuper = true, includeStatic = true)
                .filter { it.name == rule.methodName }
            if (all.size == 1) return all[0]
            if (all.size > 1) {
                failures.add(
                    rule.className + "#" + rule.methodName + " 有 " + all.size +
                        " 个重载，请补全参数类型"
                )
            }
        }
        return null
    }

    /** The XC_MethodHook that hands the invocation to FakeScript. */
    private class ScriptHookCallback(private val rule: HookRule) : XC_MethodHook() {

        override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
            if (rule.after) return
            runScript(param)
        }

        override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
            if (!rule.after) return
            runScript(param)
        }

        private fun runScript(param: XC_MethodHook.MethodHookParam) {
            try {
                ScriptState.bindHook(param)
                val ctx = ActivityTracker.bestContext()
                val out = ScriptHost.Output()
                ScriptHost.run(
                    code = rule.code,
                    entry = "hook",
                    context = ctx,
                    thisObject = param.thisObject,
                    extra = arrayOf<Any?>(param),
                    out = out
                )
                val err = out.error
                if (err != null) RmLog.w("hook 脚本 " + rule.signature + ": " + err)
            } catch (t: Throwable) {
                // A user script must never break the host application.
                RmLog.e("hook 脚本异常 " + rule.signature, t)
            } finally {
                ScriptState.unbindHook()
            }
        }
    }

    /** Exposed for the module UI: what a rule would resolve to. */
    fun describeResolution(method: Method?): String =
        if (method == null) "<未解析>" else Reflect.signatureOf(method)
}
