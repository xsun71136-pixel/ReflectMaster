package formatfa.reflectmaster.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

/**
 * The draggable summon bubble.
 *
 * Drawn by hand (no bitmap, no androidx) so it looks identical in every host
 * app and costs nothing to inflate. The glyph is the same "mirror" mark as the
 * launcher icon: a solid triangle, its translucent reflection and the axis
 * between them.
 *
 * Gesture model:
 *  - tap               -> open / close the console panel
 *  - drag              -> move; the owner snaps to the nearest edge on release
 *  - long press        -> hide the bubble until the next Activity resume
 */
class BubbleView(context: Context, private val pal: OUi.Palette) : View(context) {

    var onTap: (() -> Unit)? = null
    var onLongTap: (() -> Unit)? = null

    /** Absolute position the owner wants the bubble moved to. */
    var onMove: ((Int, Int) -> Unit)? = null

    /** Drag finished: the owner should snap to an edge and persist the position. */
    var onDragEnd: (() -> Unit)? = null

    /** Reads the bubble's current absolute position; set by the owner. */
    var positionProvider: (() -> IntArray)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var longFired = false
    private var pressedAt = 0L

    private val longPressTask = Runnable {
        longFired = true
        dragging = false
        val cb = onLongTap
        cb?.invoke()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val size = min(w, h)
        val k = size / 108f
        val ox = (w - size) / 2f
        val oy = (h - size) / 2f

        // Shadow-ish outer ring so the bubble stays visible on any background.
        paint.style = Paint.Style.FILL
        paint.color = if (pal.dark) 0x66000000 else 0x33000000
        canvas.drawCircle(w / 2f, h / 2f, size / 2f, paint)

        paint.color = pal.accent
        canvas.drawCircle(w / 2f, h / 2f, size / 2f - 1.5f * k, paint)

        // Left (solid) triangle.
        paint.style = Paint.Style.FILL
        paint.color = pal.accentText
        path.reset()
        path.moveTo(ox + 30f * k, oy + 42f * k)
        path.lineTo(ox + 48f * k, oy + 54f * k)
        path.lineTo(ox + 30f * k, oy + 66f * k)
        path.close()
        canvas.drawPath(path, paint)

        // Right (reflected) triangle.
        paint.color = withAlpha(pal.accentText, 140)
        path.reset()
        path.moveTo(ox + 78f * k, oy + 42f * k)
        path.lineTo(ox + 60f * k, oy + 54f * k)
        path.lineTo(ox + 78f * k, oy + 66f * k)
        path.close()
        canvas.drawPath(path, paint)

        // Mirror axis.
        paint.color = withAlpha(pal.accentText, 230)
        canvas.drawRect(
            ox + 52.6f * k, oy + 28f * k,
            ox + 55.4f * k, oy + 80f * k, paint
        )

        // Faint diamond outline, same as the adaptive icon.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * k
        paint.color = withAlpha(pal.accentText, 90)
        path.reset()
        path.moveTo(ox + 54f * k, oy + 23f * k)
        path.lineTo(ox + 85f * k, oy + 54f * k)
        path.lineTo(ox + 54f * k, oy + 85f * k)
        path.lineTo(ox + 23f * k, oy + 54f * k)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                val pos = positionProvider?.invoke()
                startX = if (pos != null && pos.size >= 2) pos[0] else 0
                startY = if (pos != null && pos.size >= 2) pos[1] else 0
                dragging = false
                longFired = false
                pressedAt = android.os.SystemClock.uptimeMillis()
                removeCallbacks(longPressTask)
                postDelayed(longPressTask, longPressMs)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    dragging = true
                    removeCallbacks(longPressTask)
                }
                if (dragging) {
                    val cb = onMove
                    cb?.invoke(startX + dx, startY + dy)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressTask)
                val wasDragging = dragging
                val wasLong = longFired
                dragging = false
                longFired = false
                if (wasLong) return true
                if (wasDragging) {
                    val cb = onDragEnd
                    cb?.invoke()
                } else if (android.os.SystemClock.uptimeMillis() - pressedAt < longPressMs) {
                    val cb = onTap
                    cb?.invoke()
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressTask)
                dragging = false
                longFired = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
