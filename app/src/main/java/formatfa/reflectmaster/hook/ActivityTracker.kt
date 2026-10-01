package formatfa.reflectmaster.hook

import android.app.Activity
import android.app.Application
import android.app.Dialog
import android.app.Service
import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import formatfa.reflectmaster.core.RmLog
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Knows "what is on screen right now" inside the hooked process.
 *
 * The 1.x module created a brand new floating window from
 * `Activity.onCreate`'s after-hook, i.e. one window per Activity ever created,
 * with no way to dismiss the pile short of killing the app. Here the lifecycle
 * hooks only *record* state; showing anything is an explicit user action
 * (see [KeyTrigger] and the bubble).
 *
 * Only weak references are kept so the module can never leak a host Activity.
 */
object ActivityTracker {

    private const val MAX_STACK = 16

    private val stack = CopyOnWriteArrayList<WeakReference<Activity>>()
    private val services = CopyOnWriteArrayList<WeakReference<Service>>()
    private val dialogs = CopyOnWriteArrayList<WeakReference<Dialog>>()

    @Volatile
    var application: Application? = null
        private set

    @Volatile
    var installed: Boolean = false
        private set

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        val cl = lpparam.classLoader
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", cl, "onCreate", Bundle::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val a = param.thisObject as? Activity ?: return
                        push(a)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Activity", cl, "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val a = param.thisObject as? Activity ?: return
                        push(a)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Activity", cl, "onPause",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        // The Activity stays on the stack (it is still resumable),
                        // but it is no longer "the one on screen".
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Activity", cl, "onDestroy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val a = param.thisObject as? Activity ?: return
                        remove(a)
                        pruneDialogs(a)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Dialog", cl, "show",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val d = param.thisObject as? Dialog ?: return
                        rememberDialog(d)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Dialog", cl, "dismiss",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val d = param.thisObject as? Dialog ?: return
                        forgetDialog(d)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Application", cl, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        application = param.thisObject as? Application
                        RemoteChannel.init(application)
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Service", cl, "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val s = param.thisObject as? Service ?: return
                        services.add(0, WeakReference(s))
                        trimServices()
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.app.Service", cl, "onDestroy",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        val s = param.thisObject as? Service ?: return
                        removeService(s)
                    }
                }
            )

            installed = true
            RmLog.i("生命周期追踪已安装")
        } catch (t: Throwable) {
            RmLog.e("安装生命周期追踪失败", t)
        }
    }

    // CopyOnWriteArrayList iterators do not support remove(), and every mutation
    // below happens on the host's main thread, so each edit rebuilds the list.

    private fun trimServices() {
        while (services.size > MAX_STACK) services.removeAt(services.size - 1)
    }

    private fun push(a: Activity) {
        val rebuilt = ArrayList<WeakReference<Activity>>(stack.size + 1)
        rebuilt.add(WeakReference(a))
        for (ref in stack) {
            val v = ref.get()
            if (v != null && v !== a) rebuilt.add(WeakReference(v))
        }
        while (rebuilt.size > MAX_STACK) rebuilt.removeAt(rebuilt.size - 1)
        stack.clear()
        stack.addAll(rebuilt)
    }

    private fun remove(a: Activity) {
        val rebuilt = ArrayList<WeakReference<Activity>>(stack.size)
        for (ref in stack) {
            val v = ref.get()
            if (v != null && v !== a) rebuilt.add(WeakReference(v))
        }
        stack.clear()
        stack.addAll(rebuilt)
    }

    private fun rememberDialog(d: Dialog) {
        val rebuilt = ArrayList<WeakReference<Dialog>>(dialogs.size + 1)
        rebuilt.add(WeakReference(d))
        for (ref in dialogs) {
            val v = ref.get()
            if (v != null && v !== d) rebuilt.add(WeakReference(v))
        }
        while (rebuilt.size > 8) rebuilt.removeAt(rebuilt.size - 1)
        dialogs.clear()
        dialogs.addAll(rebuilt)
    }

    private fun forgetDialog(d: Dialog) {
        val rebuilt = ArrayList<WeakReference<Dialog>>(dialogs.size)
        for (ref in dialogs) {
            val v = ref.get()
            if (v != null && v !== d) rebuilt.add(WeakReference(v))
        }
        dialogs.clear()
        dialogs.addAll(rebuilt)
    }

    private fun pruneDialogs(a: Activity) {
        val rebuilt = ArrayList<WeakReference<Dialog>>(dialogs.size)
        for (ref in dialogs) {
            val d = ref.get() ?: continue
            var ownedByA = false
            try {
                ownedByA = d.ownerActivity === a
            } catch (ignored: Throwable) {
            }
            if (!ownedByA) rebuilt.add(WeakReference(d))
        }
        dialogs.clear()
        dialogs.addAll(rebuilt)
    }

    private fun removeService(s: Service) {
        val rebuilt = ArrayList<WeakReference<Service>>(services.size)
        for (ref in services) {
            val v = ref.get()
            if (v != null && v !== s) rebuilt.add(WeakReference(v))
        }
        services.clear()
        services.addAll(rebuilt)
    }

    /** Most recently resumed Activity that is still alive, or null. */
    fun top(): Activity? {
        for (ref in stack) {
            val a = ref.get()
            if (a != null && !a.isFinishing) return a
        }
        for (ref in stack) {
            val a = ref.get()
            if (a != null) return a
        }
        return null
    }

    fun activities(): List<Activity> {
        val out = ArrayList<Activity>()
        for (ref in stack) ref.get()?.let { out.add(it) }
        return out
    }

    fun dialogs(): List<Dialog> {
        val out = ArrayList<Dialog>()
        for (ref in dialogs) ref.get()?.let { out.add(it) }
        return out
    }

    fun services(): List<Service> {
        val out = ArrayList<Service>()
        for (ref in services) ref.get()?.let { out.add(it) }
        return out
    }

    /**
     * Best available [android.content.Context]: the live Activity, else the
     * Application. Returns null only when the process has no Application yet.
     */
    fun bestContext(): android.content.Context? = top() ?: application
}
