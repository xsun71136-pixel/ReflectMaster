package formatfa.reflectmaster.overlay

import android.app.Activity
import formatfa.reflectmaster.core.Config
import formatfa.reflectmaster.core.RmConfig
import formatfa.reflectmaster.core.RmLog
import formatfa.reflectmaster.hook.ActivityTracker
import formatfa.reflectmaster.hook.HookConfig
import formatfa.reflectmaster.hook.RemoteChannel
import java.lang.ref.WeakReference

/**
 * Owns the floating console inside the hooked process: which Activity it lives
 * on, the bubble, the panel and the current [Inspector].
 *
 * All entry points must be called on the host's main thread; [toggle] and
 * [inspect] are already routed there by [formatfa.reflectmaster.hook.KeyTrigger]
 * and `bridge.rf`.
 */
object ConsoleController {

    private var activityRef: WeakReference<Activity>? = null
    private var surfaceRef: Surface? = null
    private var bubble: BubbleView? = null
    private var panel: PanelView? = null
    private var inspector: Inspector? = null

    private var pal: OUi.Palette = OUi.palette(true)
    private var cfg: RmConfig = RmConfig.EMPTY

    private var bubbleX = -1
    private var bubbleY = -1
    private var bubbleHidden = false

    private var geo: IntArray? = null

    // ---------------------------------------------------------------- public

    /** Main entry point: volume gesture, bubble tap, or `rf.summon()`. */
    fun toggle() {
        val activity = ActivityTracker.top()
        if (activity == null) {
            RmLog.w("呼出失败：当前没有存活的 Activity")
            return
        }
        if (!prepare(activity)) return
        val p = panel
        if (p != null && p.visible) {
            p.hide()
            if (cfg.trigger == Config.TRIGGER_BUBBLE) showBubble()
        } else {
            showPanel()
        }
    }

    /** Called by `rf.inspect(obj)` from a script. */
    fun inspect(target: Any?) {
        val activity = ActivityTracker.top()
        if (activity == null) {
            RmLog.w("inspect 失败：当前没有存活的 Activity")
            return
        }
        if (!prepare(activity)) return
        showPanel()
        inspector?.focusOn(target)
    }

    /** Called from the Activity resume hook when the trigger is the bubble. */
    fun attachBubble(activity: Activity, config: RmConfig) {
        cfg = config
        if (!prepare(activity)) return
        if (bubbleHidden) return
        showBubble()
    }

    fun detachBubble() {
        val s = surfaceRef ?: return
        val b = bubble ?: return
        s.detach(b)
    }

    /** Called when the process is no longer a configured target. */
    fun detachAll() {
        val s = surfaceRef
        try {
            bubble?.let { b -> if (s != null) s.detach(b) }
            panel?.let { p -> if (s != null) s.detach(p.root) }
        } catch (t: Throwable) {
            RmLog.w("移除控制台视图失败: " + t.message)
        }
        bubble = null
        panel = null
        inspector = null
        try {
            s?.release()
        } catch (ignored: Throwable) {
        }
        surfaceRef = null
        activityRef = null
    }

    // --------------------------------------------------------------- internals

    /**
     * Make sure the console is bound to [activity] and its configuration is up to
     * date. Returns false when no usable surface could be created.
     */
    private fun prepare(activity: Activity): Boolean {
        cfg = HookConfig.current
        if (!cfg.consoleEnabled) {
            RmLog.d("控制台已在设置中关闭")
            return false
        }

        val same = activityRef?.get() === activity
        val existing = surfaceRef
        if (same && existing != null && existing.alive) {
            // Config may have changed while the panel was closed.
            inspector?.updateConfig(cfg)
            panel?.applyAlpha(cfg.panelAlphaPct)
            return true
        }

        // Different Activity (or a dead surface): drop everything and rebuild.
        detachAll()

        val surface = Surfaces.best(activity, cfg.systemOverlay)
        if (surface == null) {
            RmLog.w(
                "无法创建悬浮层：Activity 已销毁" +
                    if (cfg.systemOverlay) "，或宿主没有悬浮窗权限" else ""
            )
            return false
        }
        surfaceRef = surface
        activityRef = WeakReference(activity)
        pal = OUi.palette(cfg.darkPanel)

        if (cfg.bubbleX >= 0 && cfg.bubbleY >= 0) {
            bubbleX = cfg.bubbleX
            bubbleY = cfg.bubbleY
        }
        return true
    }

    private fun currentSurface(): Surface? = surfaceRef

    private fun currentActivity(): Activity? = activityRef?.get()

    private fun showBubble() {
        val surface = currentSurface() ?: return
        val activity = currentActivity() ?: return

        // `created` (a val) is captured by the listeners below; a mutable local
        // would not smart-cast to non-null inside the lambdas.
        var b = bubble
        if (b == null) {
            val created = BubbleView(activity, pal)
            created.positionProvider = { intArrayOf(bubbleX, bubbleY) }
            created.onMove = { nx, ny ->
                bubbleX = nx
                bubbleY = ny
                surfaceRef?.move(created, nx, ny)
            }
            created.onDragEnd = { snapBubbleToEdge() }
            created.onTap = { showPanel() }
            created.onLongTap = {
                bubbleHidden = true
                surfaceRef?.detach(created)
                W.toast(activity, "悬浮球已隐藏，切换 Activity 后恢复")
            }
            bubble = created
            b = created
        }

        val size = OUi.dp(activity, 46f)
        if (bubbleX < 0 || bubbleY < 0) {
            bubbleX = surface.screenWidth() - size - OUi.dp(activity, 8f)
            bubbleY = (surface.screenHeight() * 0.38f).toInt()
        }
        bubbleX = bubbleX.coerceIn(0, (surface.screenWidth() - size).coerceAtLeast(0))
        bubbleY = bubbleY.coerceIn(0, (surface.screenHeight() - size).coerceAtLeast(0))

        if (b.parent == null) {
            if (!surface.attach(b, bubbleX, bubbleY, size, size)) {
                RmLog.w("悬浮球添加失败")
                return
            }
        } else {
            surface.move(b, bubbleX, bubbleY)
        }
    }

    private fun snapBubbleToEdge() {
        val surface = currentSurface() ?: return
        val b = bubble ?: return
        val size = b.width
        if (size <= 0) return
        val mid = surface.screenWidth() / 2
        val margin = OUi.dp(b.context, 6f)
        bubbleX = if (bubbleX + size / 2 < mid) margin else (surface.screenWidth() - size - margin)
        bubbleY = bubbleY.coerceIn(margin, (surface.screenHeight() - size - margin).coerceAtLeast(margin))
        surface.move(b, bubbleX, bubbleY)
        if (cfg.rememberPos) RemoteChannel.pushPos(bubbleX, bubbleY)
    }

    private fun showPanel() {
        val surface = currentSurface() ?: return
        val activity = currentActivity() ?: return

        if (panel == null || inspector == null) {
            val created = PanelView(activity, pal, surface)
            val insp = Inspector(activity, pal, surface, created, cfg)
            created.onClose = {
                created.hide()
                if (cfg.trigger == Config.TRIGGER_BUBBLE) showBubble()
            }
            created.onCollapse = {
                created.hide()
                if (cfg.trigger == Config.TRIGGER_BUBBLE) {
                    showBubble()
                } else {
                    W.toast(activity, "已收起，长按音量下重新呼出")
                }
            }
            created.setContent(insp.view())
            panel = created
            inspector = insp
        }

        val p = panel ?: return
        p.applyAlpha(cfg.panelAlphaPct)

        // Hide the bubble while the panel is open so they do not overlap.
        val b = bubble
        if (b != null && b.parent != null) surface.detach(b)

        val g = geo
        if (g != null && g.size == 4) {
            p.show(g[0], g[1], g[2], g[3])
        } else {
            val d = p.defaultGeometry(cfg.panelSizeDp)
            p.show(d[0], d[1], d[2], d[3])
        }
        p.onMoved = { nx, ny, nw, nh -> geo = intArrayOf(nx, ny, nw, nh) }
    }
}
