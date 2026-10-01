package formatfa.reflectmaster.overlay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import formatfa.reflectmaster.core.RmLog
import formatfa.reflectmaster.hook.RemoteChannel
import formatfa.reflectmaster.reflect.Reflect
import formatfa.reflectmaster.reflect.Values
import java.io.ByteArrayOutputStream
import java.lang.reflect.Array as ReflectArray
import java.util.concurrent.Executors

/**
 * Type-aware quick actions.
 *
 * Replaces the 1.x `ClassHandle` hierarchy (seven classes, each hard-wired to a
 * single widget type and each adding its buttons straight onto a horizontal
 * strip). Here one function returns a flat list of labelled actions, so the
 * inspector can present them in an action sheet and new types are a `when`
 * branch instead of a new class.
 */
object ObjectActions {

    class Action(val label: String, val run: () -> Unit)

    /** Everything the caller needs in order to react to an action. */
    interface Host {
        fun inspect(target: Any?)
        fun showText(title: String, text: String)
        fun choose(title: String, items: List<String>, payloads: List<Any?>, onPick: (Int) -> Unit)
        fun prompt(title: String, hint: String, initial: String, multiline: Boolean, onOk: (String) -> Unit)
        fun toast(msg: String)
        fun copy(label: String, text: String)
    }

    private val bg = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "rm-actions")
        t.isDaemon = true
        t
    }

    /** Page size for long collections, so the sheet stays usable. */
    private const val PAGE = 60

    fun actionsFor(ctx: Context, obj: Any, host: Host): List<Action> {
        val out = ArrayList<Action>()

        when (obj) {
            is Class<*> -> {
                out.add(Action("查看静态字段") { host.inspect(obj) })
                out.add(Action("无参构造一个实例") {
                    try {
                        val ctor = obj.declaredConstructors.firstOrNull { it.parameterCount == 0 }
                        if (ctor == null) host.toast("没有无参构造函数")
                        else {
                            val r = Reflect.newInstance(ctor, arrayOfNulls(0))
                            if (r.ok && r.value != null) {
                                host.toast("已创建 " + Values.describe(r.value))
                                host.inspect(r.value)
                            } else {
                                host.toast("创建失败: " + r.error)
                            }
                        }
                    } catch (t: Throwable) {
                        host.toast("创建失败: " + t.message)
                    }
                })
                out.add(Action("列出实现的接口") {
                    val sb = StringBuilder()
                    for (i in obj.interfaces) sb.append(i.name).append('\n')
                    host.showText(obj.name + " 的接口", if (sb.isEmpty()) "（无）" else sb.toString())
                })
            }

            is Activity -> {
                out.add(Action("查看 Intent") { describeIntent(obj.intent, host) })
                out.add(Action("查看 Window / DecorView") {
                    val decor = try {
                        obj.window?.decorView
                    } catch (t: Throwable) {
                        null
                    }
                    if (decor == null) host.toast("拿不到 decorView") else host.inspect(decor)
                })
                out.add(Action("打印生命周期状态") {
                    host.showText(
                        obj.javaClass.name,
                        "finishing=" + obj.isFinishing +
                            "\ndestroyed=" + obj.isDestroyed +
                            "\nchangingConfigurations=" + obj.changingConfigurations +
                            "\nwindowHasFocus=" + obj.hasWindowFocus() +
                            "\ntaskId=" + obj.taskId
                    )
                })
            }

            is TextView -> {
                out.add(Action("读取文本") { host.showText("text", obj.text?.toString() ?: "") })
                out.add(Action("修改文本") {
                    host.prompt("修改文本", "新内容", obj.text?.toString() ?: "", false) { s ->
                        try {
                            obj.text = Values.unescape(s)
                            host.toast("已修改")
                        } catch (t: Throwable) {
                            host.toast("修改失败: " + t.message)
                        }
                    }
                })
                out.add(Action("复制文本") { host.copy("text", obj.text?.toString() ?: "") })
            }

            is ViewGroup -> {
                out.add(Action("子 View 列表 (" + obj.childCount + ")") {
                    listChildren(obj, host)
                })
                out.add(Action("打印视图树") { host.showText("视图树", dumpTree(obj, 0)) })
            }

            is ImageView -> {
                out.add(Action("保存 drawable 到收件箱") { saveDrawable(obj.drawable, host) })
            }

            is View -> {
                out.add(Action("显示 / 隐藏") {
                    host.choose(
                        "visibility",
                        listOf("VISIBLE", "INVISIBLE", "GONE", "读取当前值"),
                        listOf(View.VISIBLE, View.INVISIBLE, View.GONE, null)
                    ) { i ->
                        if (i == 3) {
                            host.toast("当前 visibility = " + obj.visibility)
                        } else {
                            obj.visibility = i
                            host.toast("已设置 visibility = " + i)
                        }
                    }
                })
                out.add(Action("触发 performClick") {
                    val ok = try {
                        obj.performClick()
                    } catch (t: Throwable) {
                        false
                    }
                    host.toast(if (ok) "performClick 返回 true" else "performClick 返回 false")
                })
                out.add(Action("打印布局信息") {
                    val loc = IntArray(2)
                    try {
                        obj.getLocationOnScreen(loc)
                    } catch (ignored: Throwable) {
                    }
                    host.showText(
                        obj.javaClass.name,
                        "width=" + obj.width + " height=" + obj.height +
                            "\nleft=" + obj.left + " top=" + obj.top +
                            "\nscrollX=" + obj.scrollX + " scrollY=" + obj.scrollY +
                            "\nonScreen=(" + loc[0] + "," + loc[1] + ")" +
                            "\nid=" + idName(obj) +
                            "\nenabled=" + obj.isEnabled +
                            " visible=" + (obj.visibility == View.VISIBLE) +
                            "\nclickable=" + obj.isClickable +
                            " alpha=" + obj.alpha
                    )
                })
                out.add(Action("查看父级") {
                    val p = obj.parent
                    if (p == null) host.toast("没有父级") else host.inspect(p)
                })
            }

            is Bitmap -> {
                out.add(Action("保存到收件箱") { saveBitmap(obj, host) })
                out.add(Action("打印信息") {
                    host.showText(
                        "Bitmap",
                        obj.width.toString() + " x " + obj.height +
                            "\nconfig=" + obj.config +
                            "\nbyteCount=" + obj.byteCount +
                            "\nrecycled=" + obj.isRecycled
                    )
                })
            }

            is Drawable -> {
                out.add(Action("保存到收件箱") { saveDrawable(obj, host) })
            }

            is Intent -> {
                out.add(Action("查看 Intent") { describeIntent(obj, host) })
            }

            is Bundle -> {
                out.add(Action("列出键值") {
                    val sb = StringBuilder()
                    for (k in obj.keySet()) sb.append(k).append(" = ").append(Values.describe(obj.get(k))).append('\n')
                    host.showText("Bundle (" + obj.size() + ")", if (sb.isEmpty()) "（空）" else sb.toString())
                })
            }

            is Throwable -> {
                out.add(Action("打印堆栈") {
                    host.showText(obj.javaClass.name, android.util.Log.getStackTraceString(obj))
                })
            }

            is String -> {
                out.add(Action("Base64 解码") {
                    try {
                        val decoded = android.util.Base64.decode(obj, android.util.Base64.NO_WRAP)
                        host.showText("Base64 -> " + decoded.size + " 字节", hexDump(decoded, 512))
                    } catch (t: Throwable) {
                        host.toast("解码失败: " + t.message)
                    }
                })
                out.add(Action("按 Hex 解码") {
                    val bytes = hexToBytes(obj)
                    if (bytes == null) host.toast("不是合法的十六进制串")
                    else host.showText("Hex -> " + bytes.size + " 字节", hexDump(bytes, 512))
                })
                out.add(Action("统计") {
                    host.showText(
                        "String",
                        "length=" + obj.length +
                            "\nbytes(UTF-8)=" + obj.toByteArray(Charsets.UTF_8).size +
                            "\nhashCode=" + obj.hashCode()
                    )
                })
            }
        }

        // Collections / maps / arrays are handled through their runtime shape so
        // that subclasses (ArrayList, LinkedList, HashSet, HashMap, ...) all work.
        val cls = obj.javaClass
        if (obj !is Class<*>) {
            if (cls.isArray) {
                out.add(Action("数组元素 (" + ReflectArray.getLength(obj) + ")") { listArray(obj, host) })
            } else if (obj is Map<*, *>) {
                out.add(Action("键值对 (" + obj.size + ")") { listMap(obj, host) })
            } else if (obj is Collection<*>) {
                out.add(Action("元素列表 (" + obj.size + ")") { listCollection(obj, host) })
            }
            if (obj is ByteArray) {
                out.add(Action("保存到收件箱") {
                    saveBytes(suggestName(obj, "bin"), obj, host)
                })
                out.add(Action("Hex 预览") { host.showText("byte[" + obj.size + "]", hexDump(obj, 1024)) })
                out.add(Action("按 UTF-8 显示") {
                    host.showText("byte[] as text", String(obj, Charsets.UTF_8))
                })
            }
        }
        return out
    }

    // ------------------------------------------------------------- listings

    private fun listChildren(group: ViewGroup, host: Host) {
        val items = ArrayList<String>()
        val payloads = ArrayList<Any?>()
        for (i in 0 until group.childCount) {
            val c = group.getChildAt(i) ?: continue
            items.add("[" + i + "] " + c.javaClass.simpleName + "  " + describeView(c))
            payloads.add(c)
        }
        if (items.isEmpty()) {
            host.toast("没有子 View")
            return
        }
        host.choose("子 View", items, payloads) { idx -> host.inspect(payloads[idx]) }
    }

    private fun describeView(v: View): String {
        val label = when (v) {
            is TextView -> v.text?.toString()?.take(24) ?: ""
            else -> ""
        }
        val size = v.width.toString() + "x" + v.height
        return size + (if (label.isEmpty()) "" else "  “" + label + "”")
    }

    private fun listCollection(c: Collection<*>, host: Host) {
        val all = ArrayList<Any?>()
        for (e in c) all.add(e)
        page(all, "元素", host)
    }

    private fun listArray(obj: Any, host: Host) {
        val n = ReflectArray.getLength(obj)
        val all = ArrayList<Any?>(n)
        for (i in 0 until n) all.add(ReflectArray.get(obj, i))
        page(all, "元素", host)
    }

    private fun listMap(m: Map<*, *>, host: Host) {
        val keys = ArrayList<Any?>()
        val items = ArrayList<String>()
        for ((k, v) in m) {
            keys.add(v)
            items.add(Values.describe(k) + " = " + Values.describe(v))
        }
        if (items.isEmpty()) {
            host.toast("Map 为空")
            return
        }
        host.choose("键值对 (" + m.size + ")", items.take(PAGE), keys.take(PAGE)) { idx ->
            host.inspect(keys[idx])
        }
    }

    private fun page(all: List<Any?>, title: String, host: Host) {
        if (all.isEmpty()) {
            host.toast("没有内容")
            return
        }
        var offset = 0
        fun show() {
            val end = (offset + PAGE).coerceAtMost(all.size)
            val items = ArrayList<String>()
            val payloads = ArrayList<Any?>()
            for (i in offset until end) {
                items.add("[" + i + "] " + Values.describe(all[i]))
                payloads.add(all[i])
            }
            if (end < all.size) items.add("… 下一页 (" + (all.size - end) + " 条)")
            if (offset > 0) items.add("… 上一页")
            host.choose(title + " " + (offset + 1) + "-" + end + "/" + all.size, items, payloads) { idx ->
                val real = idx + offset
                if (real < all.size) {
                    host.inspect(payloads[idx])
                } else if (idx == items.size - 1 && offset > 0) {
                    offset = (offset - PAGE).coerceAtLeast(0)
                    show()
                } else {
                    offset = end
                    show()
                }
            }
        }
        show()
    }

    // ------------------------------------------------------------- describe

    private fun describeIntent(intent: Intent?, host: Host) {
        if (intent == null) {
            host.toast("Intent 为 null")
            return
        }
        val sb = StringBuilder()
        sb.append("action  = ").append(intent.action).append('\n')
        sb.append("data    = ").append(intent.dataString).append('\n')
        sb.append("type    = ").append(intent.type).append('\n')
        sb.append("package = ").append(intent.`package`).append('\n')
        sb.append("component = ").append(intent.component?.flattenToString()).append('\n')
        sb.append("flags   = 0x").append(Integer.toHexString(intent.flags)).append('\n')
        sb.append("categories = ").append(intent.categories).append('\n')
        val extras = intent.extras
        if (extras != null) {
            sb.append("extras (").append(extras.size()).append("):\n")
            for (k in extras.keySet()) {
                sb.append("  ").append(k).append(" = ").append(Values.describe(extras.get(k))).append('\n')
            }
        } else {
            sb.append("extras  = null\n")
        }
        host.showText("Intent", sb.toString())
    }

    private fun dumpTree(v: View, depth: Int): String {
        val sb = StringBuilder()
        val indent = StringBuilder()
        for (i in 0 until depth) indent.append("  ")
        sb.append(indent).append(v.javaClass.simpleName)
        val id = idName(v)
        if (id.isNotEmpty()) sb.append(" @").append(id)
        if (v is TextView) {
            val t = v.text?.toString()
            if (!t.isNullOrEmpty()) sb.append("  “").append(t.take(30)).append("”")
        }
        sb.append("  ").append(v.width).append('x').append(v.height)
        sb.append("  vis=").append(v.visibility)
        sb.append('\n')
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i)
                if (c != null) sb.append(dumpTree(c, depth + 1))
            }
        }
        return sb.toString()
    }

    private fun idName(v: View): String = try {
        if (v.id == View.NO_ID) "" else v.resources.getResourceEntryName(v.id)
    } catch (t: Throwable) {
        "0x" + Integer.toHexString(v.id)
    }

    // -------------------------------------------------------------- saving

    private fun saveDrawable(d: Drawable?, host: Host) {
        if (d == null) {
            host.toast("drawable 为 null")
            return
        }
        val bitmap = if (d is BitmapDrawable && d.bitmap != null) {
            d.bitmap
        } else {
            try {
                val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 64
                val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 64
                val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(b)
                d.setBounds(0, 0, w, h)
                d.draw(c)
                b
            } catch (t: Throwable) {
                host.toast("转换失败: " + t.message)
                null
            }
        }
        if (bitmap != null) saveBitmap(bitmap, host)
    }

    private fun saveBitmap(bitmap: Bitmap, host: Host) {
        bg.execute {
            try {
                val bos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, bos)
                saveBytes(suggestName(bitmap, "png"), bos.toByteArray(), host)
            } catch (t: Throwable) {
                RmLog.w("保存图片失败: " + t.message)
                host.toast("保存失败: " + t.message)
            }
        }
    }

    private fun saveBytes(name: String, data: ByteArray, host: Host) {
        bg.execute {
            val ok = RemoteChannel.pushFile(name, data)
            if (ok) {
                host.toast("已存入模块收件箱: " + name + " (" + data.size + " 字节)")
            } else {
                host.toast("收件箱写入失败，检查模块进程是否存活")
            }
        }
    }

    private fun suggestName(obj: Any, ext: String): String {
        val base = obj.javaClass.simpleName.replace('$', '_')
        return base + "_" + System.currentTimeMillis() + "." + ext
    }

    // ----------------------------------------------------------------- hex

    fun hexDump(data: ByteArray, limit: Int): String {
        val n = data.size.coerceAtMost(limit)
        val sb = StringBuilder()
        var i = 0
        while (i < n) {
            sb.append(String.format("%08x", i))
            sb.append("  ")
            val ascii = StringBuilder()
            var j = 0
            while (j < 16) {
                if (i + j < n) {
                    val b = data[i + j]
                    sb.append(String.format("%02x", b.toInt() and 0xFF))
                    val c = b.toInt().toChar()
                    ascii.append(if (b.toInt() in 32..126) c else '.')
                } else {
                    sb.append("  ")
                    ascii.append(' ')
                }
                sb.append(' ')
                j++
            }
            sb.append(" ").append(ascii).append('\n')
            i += 16
        }
        if (data.size > limit) sb.append("… 仅显示前 ").append(limit).append(" 字节\n")
        return sb.toString()
    }

    fun hexToBytes(s: String): ByteArray? {
        val clean = s.replace(" ", "").replace("\n", "").replace("\r", "")
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return try {
            val out = ByteArray(clean.length / 2)
            for (i in out.indices) {
                out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            out
        } catch (t: Throwable) {
            null
        }
    }
}
