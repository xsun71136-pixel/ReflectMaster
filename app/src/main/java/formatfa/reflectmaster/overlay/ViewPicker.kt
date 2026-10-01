package formatfa.reflectmaster.overlay

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import formatfa.reflectmaster.core.RmLog
import java.util.ArrayList as JArrayList

/**
 * Tap-to-select a View on screen.
 *
 * Replaces the 1.x `ViewLineView`, which pre-collected every child View and drew
 * a fixed list of rectangles. Here the highlight follows the finger and the hit
 * test walks the real view tree at touch time, so it also works for views that
 * appeared after the picker was opened.
 */
object ViewPicker {

    fun start(
        activity: Activity,
        surface: Surface,
        pal: OUi.Palette,
        onDone: (View?) -> Unit
    ) {
        val decor = try {
            activity.window?.decorView as? ViewGroup
        } catch (t: Throwable) {
            null
        }
        if (decor == null) {
            onDone(null)
            return
        }

        val overlay = PickerView(activity, pal)
        var finished = false

        val finish: (View?) -> Unit = { picked ->
            if (!finished) {
                finished = true
                surface.detach(overlay)
                onDone(picked)
            }
        }

        overlay.onHit = { x, y ->
            val target = findTopmost(decor, x, y)
            if (target == null) {
                W.toast(activity, "该位置没有命中任何 View")
            }
            finish(target)
        }
        overlay.onCancel = { finish(null) }
        overlay.onHover = { x, y ->
            overlay.highlight = findTopmost(decor, x, y)
            overlay.invalidate()
        }

        val w = surface.screenWidth()
        val h = surface.screenHeight()
        if (!surface.attach(overlay, 0, 0, w, h)) {
            W.toast(activity, "无法覆盖屏幕，改用系统悬浮窗模式试试")
            finish(null)
            return
        }
        W.toast(activity, "点选一个 View；点右上角 ✕ 取消")
    }

    /**
     * Smallest visible view under the point. Leaf views are preferred; a
     * ViewGroup is only returned when nothing else matched (e.g. the finger is
     * on a container's padding).
     */
    private fun findTopmost(root: ViewGroup, x: Int, y: Int): View? {
        val leaf = smallestContaining(root, x, y, false)
        return leaf ?: smallestContaining(root, x, y, true)
    }

    private fun smallestContaining(root: View, x: Int, y: Int, allowGroups: Boolean): View? {
        var best: View? = null
        var bestArea = Long.MAX_VALUE
        val loc = IntArray(2)

        fun walk(v: View) {
            if (v.visibility != View.VISIBLE) return
            try {
                v.getLocationOnScreen(loc)
            } catch (t: Throwable) {
                return
            }
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) return
            val inside = x >= loc[0] && x < loc[0] + w && y >= loc[1] && y < loc[1] + h
            if (inside && v !== root && (allowGroups || v !is ViewGroup)) {
                val area = w.toLong() * h.toLong()
                if (area < bestArea) {
                    bestArea = area
                    best = v
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) {
                    val c = v.getChildAt(i)
                    if (c != null) walk(c)
                }
            }
        }

        try {
            walk(root)
        } catch (t: Throwable) {
            RmLog.w("视图拾取遍历失败: " + t.message)
        }
        return best
    }

    /** Transparent full-screen touch catcher that draws the current highlight. */
    class PickerView(activity: Activity, private val pal: OUi.Palette) : View(activity) {

        var highlight: View? = null
        var onHit: ((Int, Int) -> Unit)? = null
        var onCancel: (() -> Unit)? = null
        var onHover: ((Int, Int) -> Unit)? = null

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = Rect()
        private val loc = IntArray(2)
        private val cancelSize = OUi.dp(activity, 44f)

        init {
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            // Dim everything slightly so the highlight reads clearly.
            paint.style = Paint.Style.FILL
            paint.color = if (pal.dark) 0x33000000 else 0x22000000
            canvas.drawRect(0f, 0f, w, h, paint)

            val target = highlight
            if (target != null) {
                try {
                    target.getLocationOnScreen(loc)
                    rect.set(loc[0], loc[1], loc[0] + target.width, loc[1] + target.height)
                    paint.style = Paint.Style.FILL
                    paint.color = 0x408C9EFF
                    canvas.drawRect(rect, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = OUi.dp(context, 2f).toFloat()
                    paint.color = pal.accent
                    canvas.drawRect(rect, paint)

                    val label = target.javaClass.name + "  " + target.width + "x" + target.height
                    paint.style = Paint.Style.FILL
                    paint.textSize = OUi.sp(context, 11f)
                    val tw = paint.measureText(label)
                    val ty = if (rect.top - OUi.dp(context, 22f) > 0) {
                        rect.top - OUi.dp(context, 6f)
                    } else {
                        rect.bottom + OUi.dp(context, 18f)
                    }
                    paint.color = if (pal.dark) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()
                    canvas.drawRect(rect.left.toFloat(), ty - OUi.dp(context, 14f),
                        rect.left.toFloat() + tw + OUi.dp(context, 12f), ty + OUi.dp(context, 4f), paint)
                    paint.color = if (pal.dark) Color.WHITE else Color.BLACK
                    canvas.drawText(label, rect.left.toFloat() + OUi.dp(context, 6f), ty, paint)
                } catch (t: Throwable) {
                    // A view can die between the hit test and the draw.
                }
            }

            // Cancel button, top-right.
            paint.style = Paint.Style.FILL
            paint.color = pal.surface
            val cx = w - cancelSize / 2f - OUi.dp(context, 8f)
            val cy = cancelSize / 2f + OUi.dp(context, 8f)
            canvas.drawCircle(cx, cy, cancelSize / 2f, paint)
            paint.color = pal.danger
            paint.textSize = OUi.sp(context, 16f)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("✕", cx, cy + OUi.sp(context, 6f), paint)
            paint.textAlign = Paint.Align.LEFT
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.rawX.toInt()
            val y = event.rawY.toInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onHover?.invoke(x, y)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    onHover?.invoke(x, y)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    val inCancel = x > width - cancelSize - OUi.dp(context, 8f) &&
                        y < cancelSize + OUi.dp(context, 16f)
                    if (inCancel) {
                        onCancel?.invoke()
                    } else {
                        onHover?.invoke(x, y)
                        onHit?.invoke(x, y)
                    }
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    onCancel?.invoke()
                    return true
                }
            }
            return true
        }
    }

    /** Kept so the picker can enumerate views for a future list mode. */
    fun collect(root: View): List<View> {
        val out = JArrayList<View>()
        collectInto(root, out)
        return out
    }

    private fun collectInto(v: View, out: MutableList<View>) {
        out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c != null) collectInto(c, out)
            }
        }
    }
}
