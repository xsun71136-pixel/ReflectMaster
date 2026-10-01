package formatfa.reflectmaster.overlay

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import formatfa.reflectmaster.core.RmLog

/** One selectable row in a console list. */
class Row(
    val title: String,
    val subtitle: String?,
    val payload: Any?,
    val accent: Boolean = false
)

/**
 * Programmatic two-line adapter.
 *
 * The console cannot inflate `R.layout.*`: inside the hooked process the
 * resources belong to the host application, so every view has to be built from
 * code. This is also why the 1.x module looked the way it did - but it built
 * views with the host's default widget styling, which is what made it feel
 * unfinished. Here the palette is ours.
 */
class RowAdapter(
    private val ctx: Context,
    private val pal: OUi.Palette,
    private var rows: List<Row>
) : BaseAdapter() {

    fun setRows(next: List<Row>) {
        rows = next
        notifyDataSetChanged()
    }

    fun rows(): List<Row> = rows

    override fun getCount(): Int = rows.size
    override fun getItem(position: Int): Row = rows[position]
    override fun getItemId(position: Int): Long = position.toLong()
    override fun hasStableIds(): Boolean = false

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val row = rows[position]
        val holder: Holder = if (convertView != null && convertView.tag is Holder) {
            convertView.tag as Holder
        } else {
            Holder(build(ctx, pal))
        }
        holder.title.text = row.title
        holder.title.setTextColor(if (row.accent) pal.accent else pal.text)
        val sub = row.subtitle
        if (sub.isNullOrEmpty()) {
            holder.subtitle.visibility = View.GONE
        } else {
            holder.subtitle.visibility = View.VISIBLE
            holder.subtitle.text = sub
        }
        return holder.root
    }

    private class Holder(val root: LinearLayout) {
        val title: TextView = root.getChildAt(0) as TextView
        val subtitle: TextView = root.getChildAt(1) as TextView
    }

    private fun build(ctx: Context, pal: OUi.Palette): LinearLayout {
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        val padH = OUi.dp(ctx, 12f)
        val padV = OUi.dp(ctx, 8f)
        root.setPadding(padH, padV, padH, padV)
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        root.setBackgroundColor(Color.TRANSPARENT)

        val title = TextView(ctx)
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
        title.setTextColor(pal.text)
        title.setSingleLine(false)
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(ctx)
        subtitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        subtitle.setTextColor(pal.textSecondary)
        subtitle.setSingleLine(false)
        subtitle.typeface = android.graphics.Typeface.MONOSPACE
        root.addView(
            subtitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return root
    }
}

/** Small dialog / toast helpers that are safe to call from any thread. */
object W {

    private val main = Handler(Looper.getMainLooper())

    fun ui(body: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            safe(body)
        } else {
            main.post { safe(body) }
        }
    }

    private fun safe(body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            RmLog.e("控制台 UI 操作失败", t)
        }
    }

    fun toast(ctx: Context?, msg: String) {
        if (ctx == null) return
        ui {
            try {
                Toast.makeText(ctx.applicationContext ?: ctx, msg, Toast.LENGTH_SHORT).show()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun alert(ctx: Context, title: String, message: String) = ui {
        try {
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("关闭", null)
                .show()
        } catch (t: Throwable) {
            RmLog.w("对话框显示失败: " + t.message)
        }
    }

    /** Scrollable, selectable text (used for long signatures and stack traces). */
    fun showText(ctx: Context, title: String, text: String) = ui {
        try {
            val scroll = ScrollView(ctx)
            val tv = TextView(ctx)
            tv.text = text
            tv.typeface = android.graphics.Typeface.MONOSPACE
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            tv.setTextIsSelectable(true)
            val pad = OUi.dp(ctx, 16f)
            tv.setPadding(pad, pad, pad, pad)
            scroll.addView(
                tv,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(scroll)
                .setNeutralButton("复制") { _: DialogInterface, _: Int -> copy(ctx, title, text) }
                .setPositiveButton("关闭", null)
                .show()
        } catch (t: Throwable) {
            RmLog.w("文本对话框显示失败: " + t.message)
        }
    }

    fun sheet(ctx: Context, title: String, items: List<String>, onPick: (Int) -> Unit) = ui {
        if (items.isEmpty()) return@ui
        try {
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setItems(items.toTypedArray()) { _: DialogInterface, which: Int -> onPick(which) }
                .show()
        } catch (t: Throwable) {
            RmLog.w("选择框显示失败: " + t.message)
        }
    }

    fun confirm(ctx: Context, title: String, message: String, onYes: () -> Unit) = ui {
        try {
            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _: DialogInterface, _: Int -> onYes() }
                .show()
        } catch (t: Throwable) {
            RmLog.w("确认框显示失败: " + t.message)
        }
    }

    /**
     * Single field input dialog. Returns the entered text through [onOk].
     *
     * Note: a single-argument callback on purpose. An earlier draft also had an
     * optional second "note" field, which made every call site pass a two
     * parameter lambda; the arity mismatch is exactly the kind of thing no
     * compiler is available to catch here.
     */
    fun prompt(
        ctx: Context,
        title: String,
        hint: String,
        initial: String,
        multiline: Boolean = false,
        onOk: (String) -> Unit
    ) = ui {
        try {
            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            val pad = OUi.dp(ctx, 16f)
            box.setPadding(pad, OUi.dp(ctx, 8f), pad, 0)

            val input = EditText(ctx)
            input.hint = hint
            input.setText(initial)
            input.setSingleLine(!multiline)
            if (multiline) {
                input.typeface = android.graphics.Typeface.MONOSPACE
                input.gravity = Gravity.TOP or Gravity.START
                input.minLines = 8
            }
            val current = input.text
            if (current != null) input.setSelection(current.length)
            box.addView(
                input,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )

            AlertDialog.Builder(ctx)
                .setTitle(title)
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _: DialogInterface, _: Int ->
                    val text = input.text
                    onOk(text?.toString() ?: "")
                }
                .show()
        } catch (t: Throwable) {
            RmLog.w("输入框显示失败: " + t.message)
        }
    }

    fun copy(ctx: Context, label: String, text: String) {
        try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText(label, text))
            toast(ctx, "已复制: " + label)
        } catch (t: Throwable) {
            RmLog.w("复制失败: " + t.message)
        }
    }

    /** Vertical stack helper used by the console screens. */
    fun stack(ctx: Context, padDp: Float = 0f): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        if (padDp > 0f) {
            val p = OUi.dp(ctx, padDp)
            l.setPadding(p, p, p, p)
        }
        return l
    }
}
