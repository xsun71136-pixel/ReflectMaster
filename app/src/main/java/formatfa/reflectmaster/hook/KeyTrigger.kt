package formatfa.reflectmaster.hook

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.RmLog

/**
 * Summon gesture.
 *
 * Long-press VOLUME_DOWN (default 600 ms) opens the console. The 1.x module
 * hooked `Activity.onKeyDown` and opened a window for *every* press, which also
 * lowered the volume. Here the first DOWN is swallowed, and if the user releases
 * before the threshold the original DOWN/UP pair is replayed through
 * `Activity.dispatchKeyEvent` with [bypass] set, so a normal short press still
 * adjusts the volume exactly once.
 *
 * Limitation (documented in the UI): an app that overrides
 * `dispatchKeyEvent` without calling `super` never reaches this hook. Such apps
 * should use the "常驻悬浮球" trigger instead.
 */
object KeyTrigger {

    private const val LONG_PRESS_MS = 600L

    private val handler = Handler(Looper.getMainLooper())

    /** Guards the replay path against re-entering our own hook. */
    @Volatile
    private var bypass = false

    private var armedDown: KeyEvent? = null
    private var pending: Runnable? = null
    private var fired = false
    private var installed = false

    @Volatile
    var mode: Int = Config.DEFAULT_TRIGGER
        private set

    /** Invoked on the main thread when the gesture completes. */
    var onSummon: (() -> Unit)? = null

    fun install(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (installed) return
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", lpparam.classLoader, "dispatchKeyEvent",
                KeyEvent::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        try {
                            handle(param)
                        } catch (t: Throwable) {
                            RmLog.e("按键触发处理失败", t)
                        }
                    }
                }
            )
            installed = true
            RmLog.i("按键触发已安装 (dispatchKeyEvent)")
        } catch (t: Throwable) {
            RmLog.e("安装按键触发失败", t)
        }
    }

    fun setMode(newMode: Int) {
        mode = newMode
        if (newMode != Config.TRIGGER_VOLUME_LONG) cancelPending()
    }

    /** Programmatic summon (used by the bubble and by the script bridge). */
    fun summon() {
        val cb = onSummon
        if (cb == null) {
            RmLog.w("收到呼出请求但没有可用的宿主 Activity")
            return
        }
        handler.post {
            try {
                cb.invoke()
            } catch (t: Throwable) {
                RmLog.e("呼出控制台失败", t)
            }
        }
    }

    private fun cancelPending() {
        val p = pending
        if (p != null) handler.removeCallbacks(p)
        pending = null
        armedDown = null
        fired = false
    }

    private fun handle(param: XC_MethodHook.MethodHookParam) {
        if (bypass) return
        if (mode != Config.TRIGGER_VOLUME_LONG) return
        val args = param.args
        if (args == null || args.isEmpty()) return
        val event = args[0] as? KeyEvent ?: return
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return
        val activity = param.thisObject as? Activity ?: return

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount != 0) {
                    // Auto repeat: swallow so holding the key does not ramp the
                    // volume all the way down while the console opens.
                    param.result = true
                    return
                }
                cancelPending()
                armedDown = KeyEvent(event)
                fired = false
                param.result = true
                val task = Runnable {
                    pending = null
                    fired = true
                    armedDown = null
                    summon()
                }
                pending = task
                handler.postDelayed(task, LONG_PRESS_MS)
            }

            KeyEvent.ACTION_UP -> {
                // Capture state *before* disarming, otherwise the "was the long
                // press already fired?" answer is lost and the press gets
                // replayed a second time.
                val wasArmed = armedDown
                val wasFired = fired
                val p = pending
                if (p != null) handler.removeCallbacks(p)
                pending = null
                armedDown = null
                param.result = true
                if (wasFired) {
                    fired = false
                } else if (wasArmed != null) {
                    replay(activity, wasArmed, event)
                }
            }
        }
    }

    private fun replay(activity: Activity, down: KeyEvent, up: KeyEvent) {
        bypass = true
        try {
            activity.dispatchKeyEvent(down)
            activity.dispatchKeyEvent(up)
        } catch (t: Throwable) {
            RmLog.w("回放音量键失败: " + t.message)
        } finally {
            bypass = false
        }
    }
}
