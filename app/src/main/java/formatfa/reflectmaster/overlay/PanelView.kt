package formatfa.reflectmaster.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The console window: a draggable, resizable card with a header and one
 * swappable content view.
 *
 * Structure is built in code (see [RowAdapter] for why) and every visual comes
 * from [OUi.Palette], so it is readable on top of both light and dark host apps.
 */
class PanelView(
    private val ctx: Context,
    private val pal: OUi.Palette,
    private val surface: Surface
) {

    val root: LinearLayout = LinearLayout(ctx)

    private val header: LinearLayout = LinearLayout(ctx)
    private val titleView: TextView = TextView(ctx)
    private val backBtn: TextView
    private val content: FrameLayout = FrameLayout(ctx)
    private val statusView: TextView = TextView(ctx)
    private val resizeHandle: TextView = TextView(ctx)

    var x = 0
    var y = 0
    var w = 0
    var h = 0
        private set

    var visible: Boolean = false
        private set

    var onClose: (() -> Unit)? = null
    var onBack: (() -> Unit)? = null
    var onMoved: ((Int, Int, Int, Int) -> Unit)? = null

    var backEnabled: Boolean = false
        set(value) {
            field = value
            backBtn.visibility = if (value) View.VISIBLE else View.GONE
        }

    init {
        root.orientation = LinearLayout.VERTICAL
        root.background = OUi.rounded(pal.bg, 14f, ctx, pal.divider, 1f)
        root.elevation = OUi.dp(ctx, 10f).toFloat()
        root.clipToOutline = true

        // ---- header -------------------------------------------------------
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        header.background = OUi.rounded(pal.surface, 14f, ctx)
        val padH = OUi.dp(ctx, 6f)
        header.setPadding(padH, OUi.dp(ctx, 2f), padH, OUi.dp(ctx, 2f))

        titleView.text = "反射大师"
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        titleView.setTextColor(pal.text)
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setSingleLine(true)
        titleView.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        header.addView(
            titleView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        backBtn = OUi.iconButton(ctx, pal, "← 返回") {
            val cb = onBack
            cb?.invoke()
        }
        backBtn.visibility = View.GONE
        header.addView(backBtn)

        header.addView(OUi.iconButton(ctx, pal, "—") {
            val cb = onCollapse
            cb?.invoke()
        })
        header.addView(OUi.iconButton(ctx, pal, "✕") {
            val cb = onClose
            cb?.invoke()
        })

        header.setOnTouchListener(DragListener())

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            OUi.divider(ctx, pal),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, OUi.dp(ctx, 1f))
            )
        )

        // ---- content ------------------------------------------------------
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        // ---- status + resize handle ---------------------------------------
        val footer = LinearLayout(ctx)
        footer.orientation = LinearLayout.HORIZONTAL
        footer.gravity = Gravity.CENTER_VERTICAL
        footer.setPadding(OUi.dp(ctx, 10f), OUi.dp(ctx, 2f), OUi.dp(ctx, 4f), OUi.dp(ctx, 2f))

        statusView.text = ""
        statusView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
        statusView.setTextColor(pal.textSecondary)
        statusView.setSingleLine(true)
        statusView.ellipsize = android.text.TextUtils.TruncateAt.END
        footer.addView(
            statusView,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        resizeHandle.text = "◢"
        resizeHandle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        resizeHandle.setTextColor(pal.accent)
        resizeHandle.gravity = Gravity.CENTER
        val hp = OUi.dp(ctx, 6f)
        resizeHandle.setPadding(hp, hp, hp, hp)
        resizeHandle.setOnTouchListener(ResizeListener())
        footer.addView(resizeHandle)

        root.addView(
            footer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    /** Invoked by the "—" button; the owner hides the panel and keeps the bubble. */
    var onCollapse: (() -> Unit)? = null

    fun setTitle(s: String) {
        titleView.text = s
    }

    fun setStatus(s: String) {
        statusView.text = s
    }

    fun setContent(view: View) {
        content.removeAllViews()
        content.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun applyAlpha(percent: Int) {
        root.alpha = (percent.coerceIn(30, 100)) / 100f
    }

    fun show(nx: Int, ny: Int, nw: Int, nh: Int) {
        w = nw
        h = nh
        x = nx
        y = ny
        if (!visible) {
            if (!surface.attach(root, x, y, w, h)) return
            visible = true
        } else {
            surface.move(root, x, y)
            surface.resize(root, w, h)
        }
    }

    fun hide() {
        if (!visible) return
        surface.detach(root)
        visible = false
    }

    fun isAttached(): Boolean = visible && root.parent != null

    // ---------------------------------------------------------------- drag

    private inner class DragListener : View.OnTouchListener {
        private var rawX = 0f
        private var rawY = 0f
        private var originX = 0
        private var originY = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = e.rawX
                    rawY = e.rawY
                    originX = x
                    originY = y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    x = originX + (e.rawX - rawX).toInt()
                    y = originY + (e.rawY - rawY).toInt()
                    x = clampX(x)
                    y = clampY(y)
                    surface.move(root, x, y)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    notifyMoved()
                    return true
                }
            }
            return false
        }
    }

    private inner class ResizeListener : View.OnTouchListener {
        private var rawX = 0f
        private var rawY = 0f
        private var originW = 0
        private var originH = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = e.rawX
                    rawY = e.rawY
                    originW = w
                    originH = h
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val minW = OUi.dp(ctx, 220f)
                    val minH = OUi.dp(ctx, 180f)
                    val newW = originW + (e.rawX - rawX).toInt()
                    val newH = originH + (e.rawY - rawY).toInt()
                    w = newW.coerceIn(minW, surface.screenWidth())
                    h = newH.coerceIn(minH, surface.screenHeight())
                    surface.resize(root, w, h)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    notifyMoved()
                    return true
                }
            }
            return false
        }
    }

    private fun notifyMoved() {
        val cb = onMoved
        cb?.invoke(x, y, w, h)
    }

    private fun clampX(v: Int): Int =
        v.coerceIn(-w / 4, (surface.screenWidth() - w / 4).coerceAtLeast(0))

    private fun clampY(v: Int): Int =
        v.coerceIn(0, (surface.screenHeight() - OUi.dp(ctx, 48f)).coerceAtLeast(0))

    /** Default placement: centred horizontally, upper third vertically. */
    fun defaultGeometry(sizeDp: Int): IntArray {
        val sw = surface.screenWidth()
        val sh = surface.screenHeight()
        val base = OUi.dp(ctx, sizeDp.toFloat())
        val portrait = sh >= sw
        val nw = if (portrait) {
            base.coerceAtMost(sw - OUi.dp(ctx, 16f))
        } else {
            (base * 1.35f).toInt().coerceAtMost(sw - OUi.dp(ctx, 16f))
        }
        val nh = if (portrait) {
            (base * 1.35f).toInt().coerceAtMost(sh - OUi.dp(ctx, 80f))
        } else {
            base.coerceAtMost(sh - OUi.dp(ctx, 40f))
        }
        val nx = ((sw - nw) / 2).coerceAtLeast(0)
        val ny = ((sh - nh) / 3).coerceAtLeast(0)
        return intArrayOf(nx, ny, nw, nh)
    }

    companion object {
        /** Transparent placeholder used while a screen is being rebuilt. */
        fun blank(ctx: Context): View {
            val v = View(ctx)
            v.setBackgroundColor(Color.TRANSPARENT)
            return v
        }
    }
}
