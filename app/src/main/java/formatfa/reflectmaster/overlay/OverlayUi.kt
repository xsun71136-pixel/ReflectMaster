package formatfa.reflectmaster.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Design system for the floating console.
 *
 * Deliberately built on **framework widgets only** - no androidx, no Material
 * Components. The console runs inside somebody else's process where the module
 * class loader delegates to the host's; using the host's (possibly older, or
 * possibly absent) AndroidX artifacts is a classic source of
 * `NoSuchMethodError` in Xposed modules. Everything here also builds its own
 * backgrounds, so the console looks identical no matter what theme the host app
 * uses.
 */
object OUi {

    class Palette(
        val dark: Boolean,
        val bg: Int,
        val surface: Int,
        val surfaceAlt: Int,
        val text: Int,
        val textSecondary: Int,
        val accent: Int,
        val accentText: Int,
        val divider: Int,
        val danger: Int,
        val ok: Int
    )

    private val DARK = Palette(
        dark = true,
        bg = 0xF01B1D2A.toInt(),
        surface = 0xFF252839.toInt(),
        surfaceAlt = 0xFF2E3247.toInt(),
        text = 0xFFE6E8F2.toInt(),
        textSecondary = 0xFF9AA0B5.toInt(),
        accent = 0xFF8C9EFF.toInt(),
        accentText = 0xFF121428.toInt(),
        divider = 0x22FFFFFF,
        danger = 0xFFFF7A7A.toInt(),
        ok = 0xFF69F0AE.toInt()
    )

    private val LIGHT = Palette(
        dark = false,
        bg = 0xF7F4F5FB.toInt(),
        surface = 0xFFFFFFFF.toInt(),
        surfaceAlt = 0xFFEDEFF8.toInt(),
        text = 0xFF1B1D2A.toInt(),
        textSecondary = 0xFF5A6072.toInt(),
        accent = 0xFF4F5BD5.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        divider = 0x1F000000,
        danger = 0xFFC62828.toInt(),
        ok = 0xFF2E7D32.toInt()
    )

    fun palette(dark: Boolean): Palette = if (dark) DARK else LIGHT

    fun dp(ctx: Context, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value, ctx.resources.displayMetrics
        ).toInt()

    fun sp(ctx: Context, value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, value, ctx.resources.displayMetrics
        )

    fun rounded(color: Int, radiusDp: Float, ctx: Context, strokeColor: Int = 0, strokeWidthDp: Float = 0f): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.RECTANGLE
        d.setColor(color)
        d.cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeColor != 0 && strokeWidthDp > 0f) {
            d.setStroke(dp(ctx, strokeWidthDp), strokeColor)
        }
        return d
    }

    fun circle(color: Int, strokeColor: Int = 0, strokeWidthDp: Int = 0): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        if (strokeColor != 0 && strokeWidthDp > 0) d.setStroke(strokeWidthDp, strokeColor)
        return d
    }

    // ---------------------------------------------------------------- views

    fun text(ctx: Context, pal: Palette, s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = s
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        if (bold) t.setTypeface(null, Typeface.BOLD)
        return t
    }

    fun title(ctx: Context, pal: Palette, s: String): TextView =
        text(ctx, pal, s, 14f, pal.text, bold = true)

    fun body(ctx: Context, pal: Palette, s: String): TextView =
        text(ctx, pal, s, 12.5f, pal.text)

    fun secondary(ctx: Context, pal: Palette, s: String): TextView =
        text(ctx, pal, s, 11f, pal.textSecondary)

    fun mono(ctx: Context, pal: Palette, s: String, sizeSp: Float = 11.5f): TextView {
        val t = TextView(ctx)
        t.text = s
        t.typeface = Typeface.MONOSPACE
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(pal.text)
        t.setTextIsSelectable(true)
        return t
    }

    /** Compact pill button; a TextView keeps the metrics predictable in a foreign theme. */
    fun button(
        ctx: Context,
        pal: Palette,
        label: String,
        filled: Boolean = false,
        danger: Boolean = false,
        onClick: () -> Unit
    ): TextView {
        val b = TextView(ctx)
        b.text = label
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        b.setSingleLine(true)
        b.gravity = Gravity.CENTER
        b.includeFontPadding = false
        val padH = dp(ctx, 10f)
        val padV = dp(ctx, 6f)
        b.setPadding(padH, padV, padH, padV)
        if (filled) {
            val bg = if (danger) pal.danger else pal.accent
            b.background = rounded(bg, 8f, ctx)
            b.setTextColor(if (danger) Color.WHITE else pal.accentText)
        } else {
            val stroke = if (danger) pal.danger else pal.accent
            b.background = rounded(pal.surfaceAlt, 8f, ctx, stroke, 1f)
            b.setTextColor(if (danger) pal.danger else pal.text)
        }
        b.setOnClickListener { onClick() }
        b.isClickable = true
        return b
    }

    fun iconButton(ctx: Context, pal: Palette, label: String, onClick: () -> Unit): TextView {
        val b = TextView(ctx)
        b.text = label
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        b.gravity = Gravity.CENTER
        b.setTextColor(pal.textSecondary)
        val pad = dp(ctx, 8f)
        b.setPadding(pad, pad, pad, pad)
        b.background = rippleOrPlain(pal, ctx)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun rippleOrPlain(pal: Palette, ctx: Context): android.graphics.drawable.Drawable {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(pal.divider),
                rounded(Color.TRANSPARENT, 8f, ctx),
                null
            )
        }
        return rounded(Color.TRANSPARENT, 8f, ctx)
    }

    fun divider(ctx: Context, pal: Palette, vertical: Boolean = false): View {
        val v = View(ctx)
        v.setBackgroundColor(pal.divider)
        v.layoutParams = if (vertical) {
            LinearLayout.LayoutParams(dp(ctx, 1f), ViewGroup.LayoutParams.MATCH_PARENT)
        } else {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1f)))
        }
        return v
    }

    fun vertical(ctx: Context, pal: Palette, padDp: Float = 10f): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        val p = dp(ctx, padDp)
        l.setPadding(p, p, p, p)
        return l
    }

    fun horizontal(ctx: Context, pal: Palette, padDp: Float = 0f): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        if (padDp > 0f) {
            val p = dp(ctx, padDp)
            l.setPadding(p, p, p, p)
        }
        return l
    }

    fun scroll(ctx: Context, content: View): ScrollView {
        val s = ScrollView(ctx)
        s.isFillViewport = true
        s.addView(
            content,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        s.setBackgroundColor(Color.TRANSPARENT)
        return s
    }

    fun scrollX(ctx: Context, content: View): HorizontalScrollView {
        val s = HorizontalScrollView(ctx)
        s.isHorizontalScrollBarEnabled = false
        s.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return s
    }

    fun input(ctx: Context, pal: Palette, hint: String, initial: String, multiline: Boolean = false): EditText {
        val e = EditText(ctx)
        e.hint = hint
        e.setText(initial)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (multiline) 12f else 13f)
        e.setTextColor(pal.text)
        e.setHintTextColor(pal.textSecondary)
        if (multiline) {
            e.typeface = Typeface.MONOSPACE
            e.gravity = Gravity.TOP or Gravity.START
            e.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            e.minLines = 6
            e.isSingleLine = false
        } else {
            e.isSingleLine = true
            e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val padH = dp(ctx, 10f)
        val padV = dp(ctx, 8f)
        e.setPadding(padH, padV, padH, padV)
        e.background = rounded(pal.surfaceAlt, 8f, ctx, pal.divider, 1f)
        return e
    }

    /** Weighted row: label on the left, value on the right. */
    fun twoColumnRow(ctx: Context, pal: Palette, left: String, right: String, onClick: (() -> Unit)? = null,
                     onLongClick: (() -> Boolean)? = null): LinearLayout {
        val row = horizontal(ctx, pal, 0f)
        row.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val padV = dp(ctx, 7f)
        val padH = dp(ctx, 4f)
        row.setPadding(padH, padV, padH, padV)
        row.background = rippleOrPlain(pal, ctx)

        val l = body(ctx, pal, left)
        l.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        l.setSingleLine(false)
        row.addView(l)

        val r = secondary(ctx, pal, right)
        r.gravity = Gravity.END
        r.maxWidth = dp(ctx, 150f)
        row.addView(r)

        if (onClick != null) row.setOnClickListener { onClick() }
        if (onLongClick != null) row.setOnLongClickListener { onLongClick() }
        return row
    }

    /**
     * Tab strip; redraws selection state in place.
     *
     * Every helper is called through `OUi.` explicitly: this is a *nested*
     * (not inner) class, so the enclosing object's members are not in scope by
     * simple name.
     */
    class TabStrip(
        private val ctx: Context,
        private val pal: Palette,
        private val labels: List<String>,
        private val onSelect: (Int) -> Unit
    ) {
        val view: LinearLayout = OUi.horizontal(ctx, pal, 0f)
        private val items = ArrayList<TextView>()
        private var selected = 0

        init {
            for (i in labels.indices) {
                val t = TextView(ctx)
                t.text = labels[i]
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                t.gravity = Gravity.CENTER
                t.setSingleLine(true)
                t.includeFontPadding = false
                val padH = OUi.dp(ctx, 10f)
                val padV = OUi.dp(ctx, 8f)
                t.setPadding(padH, padV, padH, padV)
                t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                t.setOnClickListener { select(i) }
                items.add(t)
                view.addView(t)
            }
            apply()
        }

        fun select(index: Int) {
            if (index < 0 || index >= items.size) return
            selected = index
            apply()
            onSelect(index)
        }

        /**
         * Update the visual selection **without** invoking [onSelect].
         * Needed because the owner's tab-change handler calls back into the
         * strip; using [select] there would recurse forever.
         */
        fun highlight(index: Int) {
            if (index < 0 || index >= items.size) return
            selected = index
            apply()
        }

        fun current(): Int = selected

        private fun apply() {
            for (i in items.indices) {
                val t = items[i]
                if (i == selected) {
                    t.setTextColor(pal.accentText)
                    t.background = OUi.rounded(pal.accent, 8f, ctx)
                    t.setTypeface(null, Typeface.BOLD)
                } else {
                    t.setTextColor(pal.textSecondary)
                    t.background = OUi.rounded(Color.TRANSPARENT, 8f, ctx)
                    t.setTypeface(null, Typeface.NORMAL)
                }
            }
        }
    }
}
