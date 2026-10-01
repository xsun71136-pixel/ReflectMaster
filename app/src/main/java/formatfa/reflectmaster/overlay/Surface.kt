package formatfa.reflectmaster.overlay

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import formatfa.reflectmaster.core.RmLog

/**
 * Where console views are attached.
 *
 * The 1.x module used `WindowManager.LayoutParams.TYPE_APPLICATION` for every
 * floating window. That type is only legal for sub-windows of an Activity and
 * `WindowManager.addView` rejects it with `BadTokenException` on modern Android,
 * which is why the old floating menu simply never appeared on Android 11.
 *
 * Two working strategies live here:
 *
 *  - [DecorSurface] (default) adds the console as a child of the Activity's own
 *    `decorView`. No permission is required, it cannot raise `BadTokenException`,
 *    text input works because it is part of the Activity window, and it is
 *    destroyed together with the Activity so it can never leak.
 *  - [SystemSurface] uses `TYPE_APPLICATION_OVERLAY` and needs the *host* app to
 *    hold `SYSTEM_ALERT_WINDOW`. That is rarely granted, so it stays opt-in.
 */
interface Surface {
    val context: Context
    val alive: Boolean

    fun attach(view: View, x: Int, y: Int, w: Int, h: Int): Boolean
    fun move(view: View, x: Int, y: Int)
    fun resize(view: View, w: Int, h: Int)
    fun detach(view: View)
    fun screenWidth(): Int
    fun screenHeight(): Int
    fun release()
}

class DecorSurface(private val activity: Activity) : Surface {

    override val context: Context get() = activity

    private val decor: ViewGroup?
        get() {
            val w = activity.window ?: return null
            return w.decorView as? ViewGroup
        }

    override val alive: Boolean
        get() {
            if (activity.isFinishing) return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return false
            return decor != null
        }

    private fun lpFor(x: Int, y: Int, w: Int, h: Int): FrameLayout.LayoutParams {
        val lp = FrameLayout.LayoutParams(w, h)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = x
        lp.topMargin = y
        return lp
    }

    override fun attach(view: View, x: Int, y: Int, w: Int, h: Int): Boolean {
        val d = decor ?: return false
        if (view.parent != null) {
            (view.parent as? ViewGroup)?.removeView(view)
        }
        return try {
            d.addView(view, lpFor(x, y, w, h))
            view.bringToFront()
            true
        } catch (t: Throwable) {
            RmLog.e("注入 decorView 失败", t)
            false
        }
    }

    override fun move(view: View, x: Int, y: Int) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.leftMargin = x
        lp.topMargin = y
        view.layoutParams = lp
    }

    override fun resize(view: View, w: Int, h: Int) {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.width = w
        lp.height = h
        view.layoutParams = lp
    }

    override fun detach(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        try {
            parent.removeView(view)
        } catch (t: Throwable) {
            RmLog.w("移除视图失败: " + t.message)
        }
    }

    override fun screenWidth(): Int = activity.resources.displayMetrics.widthPixels

    override fun screenHeight(): Int = activity.resources.displayMetrics.heightPixels

    override fun release() {
        // Nothing to tear down: the views die with the Activity.
    }
}

class SystemSurface(private val activity: Activity) : Surface {

    private val wm: WindowManager? =
        activity.getSystemService(Context.WINDOW_SERVICE) as? WindowManager

    override val context: Context get() = activity

    override val alive: Boolean
        get() = wm != null && !activity.isFinishing && canDraw(activity)

    private fun typeFor(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun lpFor(x: Int, y: Int, w: Int, h: Int, focusable: Boolean): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams(w, h, typeFor(), 0, PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = x
        lp.y = y
        lp.flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (focusable) 0 else WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        return lp
    }

    override fun attach(view: View, x: Int, y: Int, w: Int, h: Int): Boolean {
        val m = wm ?: return false
        if (!canDraw(activity)) return false
        if (view.parent != null) detach(view)
        return try {
            // Focusable so the script editor can receive keyboard input.
            m.addView(view, lpFor(x, y, w, h, true))
            true
        } catch (t: Throwable) {
            RmLog.e("系统悬浮窗添加失败", t)
            false
        }
    }

    override fun move(view: View, x: Int, y: Int) {
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = x
        lp.y = y
        try {
            wm?.updateViewLayout(view, lp)
        } catch (ignored: Throwable) {
        }
    }

    override fun resize(view: View, w: Int, h: Int) {
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        lp.width = w
        lp.height = h
        try {
            wm?.updateViewLayout(view, lp)
        } catch (ignored: Throwable) {
        }
    }

    override fun detach(view: View) {
        if (view.parent == null) return
        try {
            wm?.removeView(view)
        } catch (ignored: Throwable) {
        }
    }

    override fun screenWidth(): Int = activity.resources.displayMetrics.widthPixels

    override fun screenHeight(): Int = activity.resources.displayMetrics.heightPixels

    override fun release() {
        // Views are removed explicitly by ConsoleController.
    }

    companion object {
        fun canDraw(ctx: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            return try {
                Settings.canDrawOverlays(ctx)
            } catch (t: Throwable) {
                false
            }
        }
    }
}

object Surfaces {

    /**
     * Pick a working surface. Falls back to decor injection whenever the host
     * app cannot draw overlays, which is the common case.
     */
    fun best(activity: Activity, preferSystemOverlay: Boolean): Surface? {
        if (activity.isFinishing) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return null
        if (preferSystemOverlay && SystemSurface.canDraw(activity)) return SystemSurface(activity)
        val d = DecorSurface(activity)
        return if (d.alive) d else null
    }
}
