package formatfa.reflectmaster.overlay

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import formatfa.reflectmaster.core.RmConfig
import formatfa.reflectmaster.core.RmLog
import formatfa.reflectmaster.core.ScriptItem
import formatfa.reflectmaster.hook.ActivityTracker
import formatfa.reflectmaster.hook.CustomHooks
import formatfa.reflectmaster.hook.HookConfig
import formatfa.reflectmaster.hook.ScriptHost
import formatfa.reflectmaster.reflect.Reflect
import formatfa.reflectmaster.reflect.Registry
import formatfa.reflectmaster.reflect.Values
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * The console body: a tab strip plus one swappable screen and a breadcrumb line
 * showing which object is under inspection.
 *
 * The 1.x module opened a *new* system window for every drill-down
 * (`FieldWindow` -> `MethodWindow` -> `EditFieldWindow`, each with its own
 * `TYPE_APPLICATION` LayoutParams), so windows piled up and could not be
 * dismissed together. Everything here lives in one view tree inside one panel,
 * and "back" is a plain stack pop.
 */
class Inspector(
    private val ctx: Context,
    private val pal: OUi.Palette,
    private val surface: Surface,
    private val panel: PanelView,
    private var cfg: RmConfig
) : ObjectActions.Host {

    companion object {
        private const val TAB_OBJECTS = 0
        private const val TAB_FIELDS = 1
        private const val TAB_METHODS = 2
        private const val TAB_CONSTRUCTORS = 3
        private const val TAB_SCRIPT = 4
        private const val TAB_LOG = 5

        // Sentinels for the pseudo rows of the "objects" screen. They live in the
        // companion object so they are initialised before the instance `init`
        // block runs renderTab().
        private val PICK_VIEW = Any()
        private val FIND_CLASS = Any()
        private val CLASS_LOADER = Any()
        private val CLEAR_SLOTS = Any()
        private val REFRESH_CONFIG = Any()

        private const val SLOT_HINT = "支持 \$v0 变量槽 / \$null / 0x1F / \\n 转义"
        private const val ARG_TIP = "长按输入框可从变量槽插入；支持 \$null、0x1F、[1,2,3]"

        private val DEFAULT_SCRIPT = """func main()
    rf.print("当前对象: " + rf.describe(rf.thiz()))
    rf.fields(rf.thiz())
end"""
    }

    private var current: Any? = ActivityTracker.top()
    private val backStack = ArrayList<Any?>()

    private val root = LinearLayout(ctx)
    private val crumbBar: LinearLayout
    private val crumbText: TextView
    private val contentHost = FrameLayout(ctx)
    private val statusLine: TextView

    private var tabs: OUi.TabStrip? = null
    private var activeTab = TAB_OBJECTS

    /** List options, kept for the lifetime of the console. */
    private var includeSuper = true
    private var includeStatic = true
    private var fieldFilter = ""
    private var methodFilter = ""

    private var lastScriptOutput = ""
    private var scriptDraft = ""

    init {
        root.orientation = LinearLayout.VERTICAL

        crumbText = TextView(ctx)
        crumbText.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        crumbText.setTextColor(pal.accent)
        crumbText.setSingleLine(true)
        crumbText.ellipsize = android.text.TextUtils.TruncateAt.START
        crumbText.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        )

        crumbBar = OUi.horizontal(ctx, pal, 0f)
        crumbBar.setPadding(OUi.dp(ctx, 10f), OUi.dp(ctx, 4f), OUi.dp(ctx, 4f), OUi.dp(ctx, 4f))
        crumbBar.addView(crumbText)
        crumbBar.addView(OUi.button(ctx, pal, "切换", false) { showObjectPicker() })
        crumbBar.addView(space(ctx))
        crumbBar.addView(OUi.button(ctx, pal, "存入槽", false) { storeCurrent() })

        root.addView(
            crumbBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val strip = OUi.TabStrip(
            ctx, pal,
            listOf("对象", "字段", "方法", "构造", "脚本", "日志")
        ) { index -> selectTab(index) }
        tabs = strip
        val stripWrap = LinearLayout(ctx)
        stripWrap.orientation = LinearLayout.VERTICAL
        stripWrap.setPadding(OUi.dp(ctx, 6f), OUi.dp(ctx, 2f), OUi.dp(ctx, 6f), OUi.dp(ctx, 4f))
        stripWrap.addView(strip.view)
        root.addView(
            stripWrap,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            contentHost,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        statusLine = TextView(ctx)
        statusLine.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
        statusLine.setTextColor(pal.textSecondary)
        statusLine.setSingleLine(true)
        statusLine.ellipsize = android.text.TextUtils.TruncateAt.END
        statusLine.setPadding(
            OUi.dp(ctx, 10f), OUi.dp(ctx, 2f),
            OUi.dp(ctx, 10f), OUi.dp(ctx, 4f)
        )
        root.addView(
            statusLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        renderTab()
    }

    fun view(): View = root

    fun updateConfig(next: RmConfig) {
        cfg = next
    }

    // ------------------------------------------------------------ navigation

    fun focusOn(target: Any?) {
        if (target == null) {
            toast("目标为 null")
            return
        }
        if (current != null && current !== target) backStack.add(current)
        current = target
        if (activeTab == TAB_OBJECTS) selectTab(TAB_FIELDS) else renderTab()
    }

    private fun goBack() {
        if (backStack.isEmpty()) return
        current = backStack.removeAt(backStack.size - 1)
        renderTab()
    }

    fun canGoBack(): Boolean = backStack.isNotEmpty()

    fun selectTab(index: Int) {
        activeTab = index
        // highlight(), not select(): select() would call back into selectTab().
        tabs?.highlight(index)
        renderTab()
    }

    private fun showObjectPicker() {
        selectTab(TAB_OBJECTS)
    }

    private fun renderTab() {
        val back: (() -> Unit)? = if (canGoBack()) {
            { goBack() }
        } else {
            null
        }
        panel.onBack = back
        panel.backEnabled = back != null
        updateCrumb()
        val view = when (activeTab) {
            TAB_OBJECTS -> objectsScreen()
            TAB_FIELDS -> fieldsScreen()
            TAB_METHODS -> methodsScreen()
            TAB_CONSTRUCTORS -> constructorsScreen()
            TAB_SCRIPT -> scriptScreen()
            TAB_LOG -> logScreen()
            else -> objectsScreen()
        }
        contentHost.removeAllViews()
        contentHost.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        panel.setTitle(titleFor())
        panel.setStatus(statusFor())
    }

    private fun titleFor(): String {
        val c = current
        return if (c == null) "反射大师 · 无对象" else "反射大师 · " + Values.typeLabel(c.javaClass)
    }

    private fun statusFor(): String =
        "配置 " + HookConfig.sourceName() +
            " · 槽 " + Registry.count() +
            " · hook " + CustomHooks.installedCount +
            " · 目标 " + HookConfig.current.targets.size

    private fun updateCrumb() {
        val c = current
        crumbText.text = if (c == null) {
            "当前对象: 无（点「切换」选择）"
        } else {
            "当前: " + c.javaClass.name + "@" + Integer.toHexString(System.identityHashCode(c))
        }
        statusLine.text = statusFor()
    }

    private fun storeCurrent() {
        val c = current
        if (c == null) {
            toast("没有当前对象")
            return
        }
        val i = Registry.store(c, Values.typeLabel(c.javaClass))
        toast(
            if (i < 0) "变量槽已满（上限 " + Registry.CAPACITY + "）"
            else "已存入 " + Values.slotName(i)
        )
    }

    // ---------------------------------------------------------- objects tab

    private fun objectsScreen(): View {
        val rows = ArrayList<Row>()

        val activities = ActivityTracker.activities()
        for (i in activities.indices) {
            val a = activities[i]
            rows.add(
                Row(
                    (if (i == 0) "▶ " else "") + a.javaClass.name,
                    "Activity · taskId " + a.taskId, a, i == 0
                )
            )
        }
        if (activities.isEmpty()) rows.add(Row("（没有存活的 Activity）", null, null))

        for (d in ActivityTracker.dialogs()) rows.add(Row(d.javaClass.name, "Dialog", d))

        val app = ActivityTracker.application
        if (app != null) rows.add(Row(app.javaClass.name, "Application", app))

        for (s in ActivityTracker.services()) rows.add(Row(s.javaClass.name, "Service", s))

        val slots = Registry.entries()
        for (e in slots) {
            rows.add(
                Row(
                    e.label,
                    if (e.value == null) "null" else Values.describe(e.value),
                    e.value
                )
            )
        }
        if (slots.isEmpty()) rows.add(Row("（变量槽为空）", null, null))

        rows.add(Row("视图拾取", "在屏幕上点选一个 View", PICK_VIEW, true))
        rows.add(Row("查找类", "按类名加载并检查", FIND_CLASS, true))
        rows.add(Row("ClassLoader 链", "查看当前类加载器", CLASS_LOADER, true))
        rows.add(Row("清空变量槽", "释放全部 " + Registry.CAPACITY + " 个槽位", CLEAR_SLOTS, true))
        rows.add(Row("刷新配置", "重新读取模块配置", REFRESH_CONFIG, true))

        return listView(rows, onClick = { row ->
            val p = row.payload
            when {
                p === PICK_VIEW -> startViewPicker()
                p === FIND_CLASS -> promptFindClass()
                p === CLASS_LOADER -> showClassLoaderInfo()
                p === CLEAR_SLOTS -> {
                    Registry.clear()
                    toast("变量槽已清空")
                    renderTab()
                }
                p === REFRESH_CONFIG -> {
                    HookConfig.refreshAsync(null)
                    toast("已触发配置刷新，来源: " + HookConfig.sourceName())
                    renderTab()
                }
                p == null -> {
                }
                else -> focusOn(p)
            }
        })
    }

    private fun showClassLoaderInfo() {
        val sb = StringBuilder()
        var l: ClassLoader? = currentClassLoader()
        var guard = 0
        while (l != null && guard < 32) {
            sb.append(l.javaClass.name).append('\n').append("    ").append(l).append('\n')
            l = l.parent
            guard++
        }
        sb.append("↑ BootClassLoader")
        W.showText(ctx, "ClassLoader 链", sb.toString())
    }

    private fun promptFindClass() {
        W.prompt(
            ctx, "查找类", "完整类名，例如 android.app.Activity",
            "android.app.Activity", false
        ) { s ->
            try {
                focusOn(Reflect.findClass(s, currentClassLoader()))
            } catch (t: Throwable) {
                toast(t.message ?: "找不到类")
            }
        }
    }

    private fun currentClassLoader(): ClassLoader? {
        val c = current
        return when {
            c is Class<*> -> c.classLoader
            c != null -> c.javaClass.classLoader
            else -> ctx.classLoader
        }
    }

    // ----------------------------------------------------------- fields tab

    private fun fieldsScreen(): View {
        val target = current
        if (target == null) return emptyHint("先在「对象」页选择一个对象")
        val cls = if (target is Class<*>) target else target.javaClass
        val instance: Any? = if (target is Class<*>) null else target

        val all = Reflect.fields(cls, includeSuper, includeStatic)
        val rows = ArrayList<Row>()
        for (f in all) {
            if (fieldFilter.isNotEmpty() &&
                !f.name.contains(fieldFilter, ignoreCase = true) &&
                !f.declaringClass.name.contains(fieldFilter, ignoreCase = true)
            ) continue
            val r = Reflect.read(f, instance)
            val valueText = if (r.ok) Values.describe(r.value) else "<" + r.error + ">"
            val mods = if (Modifier.isStatic(f.modifiers)) "static " else ""
            rows.add(Row(mods + Values.typeLabel(f.type) + " " + f.name, valueText, f))
        }

        val header = filterHeader(
            "字段 " + rows.size + "/" + all.size,
            fieldFilter,
            { fieldFilter = it; renderTab() },
            listOf("含父类" to includeSuper, "含静态" to includeStatic)
        ) { which ->
            if (which == 0) includeSuper = !includeSuper else includeStatic = !includeStatic
            renderTab()
        }

        val list = listView(rows, onClick = { row ->
            val f = row.payload as? Field ?: return@listView
            val r = Reflect.read(f, instance)
            if (!r.ok) {
                W.alert(ctx, "读取失败", r.error ?: "")
                return@listView
            }
            val value = r.value
            if (value == null || Values.isPrimitive(value.javaClass)) {
                editField(f, instance, value)
            } else {
                focusOn(value)
            }
        }, onLongClick = { row ->
            val f = row.payload as? Field ?: return@listView false
            fieldActions(f, instance)
            true
        })

        return withHeader(header, list)
    }

    private fun fieldActions(f: Field, instance: Any?) {
        val items = listOf(
            "编辑值",
            "存入变量槽",
            "查看完整值",
            "复制字段名",
            "复制签名",
            "复制 Hook 用「类名 字段名」",
            "查看声明类"
        )
        W.sheet(ctx, f.name, items) { which ->
            when (which) {
                0 -> {
                    val r = Reflect.read(f, instance)
                    editField(f, instance, r.value)
                }
                1 -> {
                    val r = Reflect.read(f, instance)
                    val i = Registry.store(r.value, f.name)
                    toast(if (i < 0) "变量槽已满" else "已存入 " + Values.slotName(i))
                }
                2 -> {
                    val r = Reflect.read(f, instance)
                    W.showText(
                        ctx, f.name,
                        if (r.ok) Values.describe(r.value) else "读取失败: " + r.error
                    )
                }
                3 -> W.copy(ctx, "字段名", f.name)
                4 -> W.copy(ctx, "签名", Reflect.signatureOf(f))
                5 -> W.copy(ctx, "hook", f.declaringClass.name + " " + f.name)
                6 -> focusOn(f.declaringClass)
            }
        }
    }

    private fun editField(f: Field, instance: Any?, currentValue: Any?) {
        val type = f.type
        val initial = when {
            currentValue == null -> Values.NULL_TOKEN
            type == String::class.java -> Values.escape(currentValue.toString())
            Values.isTextEditable(type) -> currentValue.toString()
            else -> Values.describe(currentValue)
        }
        val editable = Values.isTextEditable(type)
        val title = (if (editable) "编辑 " else "查看 ") + Values.typeLabel(type) + " " + f.name

        if (!editable) {
            val value = currentValue
            W.sheet(
                ctx, title,
                listOf("查看完整值", "存入变量槽", "下钻到该对象", "复制值", "类型专属操作")
            ) { which ->
                when (which) {
                    0 -> W.showText(ctx, f.name, Values.describe(value))
                    1 -> {
                        val i = Registry.store(value, f.name)
                        toast(if (i < 0) "变量槽已满" else "已存入 " + Values.slotName(i))
                    }
                    2 -> focusOn(value)
                    3 -> W.copy(ctx, f.name, Values.describe(value))
                    4 -> showTypeActions(value)
                }
            }
            return
        }

        W.prompt(ctx, title, SLOT_HINT, initial, false) { text ->
            try {
                val value = Values.parse(text, type, instance, ctx)
                val r = Reflect.write(f, instance, value)
                if (r.ok) {
                    toast("已写入 " + f.name + " = " + Values.describe(r.value))
                    renderTab()
                } else {
                    W.alert(ctx, "写入失败", r.error ?: "")
                }
            } catch (t: Throwable) {
                W.alert(ctx, "解析失败", t.message ?: t.toString())
            }
        }
    }

    /** Type aware quick actions (replaces the old ClassHandle hierarchy). */
    private fun showTypeActions(value: Any?) {
        if (value == null) {
            toast("值为 null")
            return
        }
        val actions = ObjectActions.actionsFor(ctx, value, this)
        if (actions.isEmpty()) {
            W.sheet(ctx, Values.typeLabel(value.javaClass), listOf("下钻到该对象", "存入变量槽", "复制值")) { which ->
                when (which) {
                    0 -> focusOn(value)
                    1 -> {
                        val i = Registry.store(value, Values.typeLabel(value.javaClass))
                        toast(if (i < 0) "变量槽已满" else "已存入 " + Values.slotName(i))
                    }
                    2 -> W.copy(ctx, "值", Values.describe(value))
                }
            }
            return
        }
        val labels = ArrayList<String>()
        for (a in actions) labels.add(a.label)
        labels.add("下钻到该对象")
        labels.add("存入变量槽")
        labels.add("复制值")
        W.sheet(ctx, Values.typeLabel(value.javaClass), labels) { which ->
            when {
                which < actions.size -> safeRun(actions[which])
                which == actions.size -> focusOn(value)
                which == actions.size + 1 -> {
                    val i = Registry.store(value, Values.typeLabel(value.javaClass))
                    toast(if (i < 0) "变量槽已满" else "已存入 " + Values.slotName(i))
                }
                else -> W.copy(ctx, "值", Values.describe(value))
            }
        }
    }

    private fun safeRun(action: ObjectActions.Action) {
        try {
            action.run()
        } catch (t: Throwable) {
            W.alert(ctx, "操作失败", (t.javaClass.name + ": " + t.message))
            RmLog.e("操作 " + action.label + " 失败", t)
        }
    }

    // ---------------------------------------------------------- methods tab

    private fun methodsScreen(): View {
        val target = current
        if (target == null) return emptyHint("先在「对象」页选择一个对象")
        val cls = if (target is Class<*>) target else target.javaClass
        val instance: Any? = if (target is Class<*>) null else target

        val all = Reflect.methods(cls, includeSuper, includeStatic)
        val rows = ArrayList<Row>()
        for (m in all) {
            if (methodFilter.isNotEmpty() && !m.name.contains(methodFilter, ignoreCase = true)) continue
            val mods = if (Modifier.isStatic(m.modifiers)) "static " else ""
            rows.add(
                Row(
                    mods + Values.typeLabel(m.returnType) + " " + m.name + "(…)",
                    Reflect.signatureOf(m),
                    m
                )
            )
        }

        val header = filterHeader(
            "方法 " + rows.size + "/" + all.size,
            methodFilter,
            { methodFilter = it; renderTab() },
            listOf("含父类" to includeSuper, "含静态" to includeStatic)
        ) { which ->
            if (which == 0) includeSuper = !includeSuper else includeStatic = !includeStatic
            renderTab()
        }

        val list = listView(rows, onClick = { row ->
            val m = row.payload as? Method ?: return@listView
            invokeDialog(m, instance)
        }, onLongClick = { row ->
            val m = row.payload as? Method ?: return@listView false
            methodActions(m, instance)
            true
        })
        return withHeader(header, list)
    }

    private fun methodActions(m: Method, instance: Any?) {
        W.sheet(
            ctx, m.name,
            listOf("调用", "复制签名", "复制 Hook 用「类名 方法名 参数类型」", "查看声明类")
        ) { which ->
            when (which) {
                0 -> invokeDialog(m, instance)
                1 -> W.copy(ctx, "签名", Reflect.signatureOf(m))
                2 -> W.copy(ctx, "hook", Reflect.hookSignature(m))
                3 -> focusOn(m.declaringClass)
            }
        }
    }

    private fun invokeDialog(m: Method, instance: Any?) {
        val types = m.parameterTypes
        if (types.isEmpty()) {
            doInvoke(m, instance, arrayOfNulls(0))
            return
        }
        val box = W.stack(ctx, 12f)

        val label = TextView(ctx)
        label.text = Reflect.signatureOf(m)
        label.setTextColor(pal.accent)
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
        box.addView(label)

        val tip = TextView(ctx)
        tip.text = ARG_TIP
        tip.setTextColor(pal.textSecondary)
        tip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10.5f)
        box.addView(tip)

        val inputs = ArrayList<EditText>()
        for (i in types.indices) {
            val hint = "参数 " + i + ": " + types[i].name
            val e = OUi.input(ctx, pal, hint, defaultFor(types[i]), false)
            e.setOnLongClickListener {
                pickSlot { slot -> e.setText(slot) }
                true
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = OUi.dp(ctx, 6f)
            box.addView(e, lp)
            inputs.add(e)
        }

        val scroll = ScrollView(ctx)
        scroll.addView(box)
        android.app.AlertDialog.Builder(ctx)
            .setTitle("调用 " + m.name)
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("运行") { _, _ ->
                val args = arrayOfNulls<Any>(inputs.size)
                var failure: String? = null
                try {
                    for (i in inputs.indices) {
                        args[i] = Values.parse(
                            inputs[i].text?.toString() ?: "", types[i], instance, ctx
                        )
                    }
                } catch (t: Throwable) {
                    failure = t.message ?: t.toString()
                }
                if (failure != null) {
                    W.alert(ctx, "参数解析失败", failure)
                } else {
                    doInvoke(m, instance, args)
                }
            }
            .show()
    }

    private fun defaultFor(type: Class<*>): String = when (type.name) {
        "int", "long", "short", "byte", "float", "double" -> "0"
        "boolean" -> "false"
        "char" -> "a"
        else -> ""
    }

    private fun doInvoke(m: Method, instance: Any?, args: Array<Any?>) {
        val r = Reflect.invoke(m, instance, args)
        if (!r.ok) {
            W.alert(ctx, "调用失败", r.error ?: "")
            RmLog.w("调用 " + m.name + " 失败: " + r.error)
            return
        }
        val value = r.value
        RmLog.i("调用 " + m.name + " -> " + Values.describe(value))
        if (value == null) {
            W.alert(ctx, "调用完成", "返回 null（或 void）")
            return
        }
        W.sheet(
            ctx, "返回 " + Values.typeLabel(value.javaClass),
            listOf("查看完整值", "下钻到该对象", "存入变量槽", "复制值", "类型专属操作")
        ) { which ->
            when (which) {
                0 -> W.showText(ctx, m.name, Values.describe(value))
                1 -> focusOn(value)
                2 -> {
                    val i = Registry.store(value, m.name)
                    toast(if (i < 0) "变量槽已满" else "已存入 " + Values.slotName(i))
                }
                3 -> W.copy(ctx, m.name, Values.describe(value))
                4 -> showTypeActions(value)
            }
        }
    }

    // ----------------------------------------------------- constructors tab

    private fun constructorsScreen(): View {
        val target = current
        if (target == null) return emptyHint("先在「对象」页选择一个对象")
        val cls = if (target is Class<*>) target else target.javaClass
        val all = Reflect.constructors(cls)
        val rows = ArrayList<Row>()
        for (c in all) rows.add(Row(Reflect.signatureOf(c), c.declaringClass.name, c))
        if (rows.isEmpty()) rows.add(Row("（没有可访问的构造函数）", null, null))

        val list = listView(rows, onClick = { row ->
            val c = row.payload as? Constructor<*> ?: return@listView
            constructDialog(c)
        })
        return withHeader(OUi.secondary(ctx, pal, cls.name + " · 构造函数 " + all.size), list)
    }

    private fun constructDialog(c: Constructor<*>) {
        val types = c.parameterTypes
        if (types.isEmpty()) {
            reportConstruct(Reflect.newInstance(c, arrayOfNulls(0)))
            return
        }
        val box = W.stack(ctx, 12f)
        val inputs = ArrayList<EditText>()
        for (i in types.indices) {
            val e = OUi.input(ctx, pal, "参数 " + i + ": " + types[i].name, defaultFor(types[i]), false)
            e.setOnLongClickListener {
                pickSlot { slot -> e.setText(slot) }
                true
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = OUi.dp(ctx, 6f)
            box.addView(e, lp)
            inputs.add(e)
        }
        val scroll = ScrollView(ctx)
        scroll.addView(box)
        android.app.AlertDialog.Builder(ctx)
            .setTitle("构造 " + c.declaringClass.simpleName)
            .setView(scroll)
            .setNegativeButton("取消", null)
            .setPositiveButton("创建") { _, _ ->
                val args = arrayOfNulls<Any>(inputs.size)
                var failure: String? = null
                try {
                    for (i in inputs.indices) {
                        args[i] = Values.parse(
                            inputs[i].text?.toString() ?: "", types[i], null, ctx
                        )
                    }
                } catch (t: Throwable) {
                    failure = t.message ?: t.toString()
                }
                if (failure != null) {
                    W.alert(ctx, "参数解析失败", failure)
                } else {
                    reportConstruct(Reflect.newInstance(c, args))
                }
            }
            .show()
    }

    private fun reportConstruct(r: Reflect.ReadResult) {
        if (!r.ok) {
            W.alert(ctx, "构造失败", r.error ?: "")
            return
        }
        toast("已创建 " + Values.describe(r.value))
        focusOn(r.value)
    }

    // --------------------------------------------------------- script tab

    private fun scriptScreen(): View {
        val box = W.stack(ctx, 10f)

        val saved = cfg.scripts
        val bar = OUi.horizontal(ctx, pal, 0f)
        bar.addView(OUi.button(ctx, pal, "运行", true) { runDraft() })
        bar.addView(space(ctx))
        bar.addView(OUi.button(ctx, pal, "我的脚本 (" + saved.size + ")") { pickScript(saved) })
        bar.addView(space(ctx))
        bar.addView(OUi.button(ctx, pal, "清空输出") {
            lastScriptOutput = ""
            renderTab()
        })
        box.addView(bar)

        val initial = if (scriptDraft.isEmpty()) DEFAULT_SCRIPT else scriptDraft
        val editor = OUi.input(ctx, pal, "func main()", initial, true)
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                scriptDraft = s?.toString() ?: ""
            }
        })
        box.addView(
            editor,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, OUi.dp(ctx, 130f))
        )

        val outputText = if (lastScriptOutput.isEmpty()) "（输出会显示在这里）" else lastScriptOutput
        val output = OUi.mono(ctx, pal, outputText, 11f)
        output.setTextColor(if (lastScriptOutput.isEmpty()) pal.textSecondary else pal.text)
        val outputScroll = ScrollView(ctx)
        outputScroll.addView(output)
        box.addView(
            outputScroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        box.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        return box
    }

    private fun pickScript(items: List<ScriptItem>) {
        if (items.isEmpty()) {
            W.alert(ctx, "没有脚本", "在模块 App 的「脚本」页新建脚本后，重新呼出控制台即可看到。")
            return
        }
        val names = ArrayList<String>()
        for (s in items) names.add(s.name)
        W.sheet(ctx, "我的脚本", names) { i ->
            scriptDraft = items[i].code
            renderTab()
        }
    }

    private fun runDraft() {
        val code = scriptDraft
        if (code.trim().isEmpty()) {
            toast("脚本为空")
            return
        }
        lastScriptOutput = "运行中…"
        renderTab()
        val out = ScriptHost.Output()
        val target = current
        val runner = Thread {
            try {
                ScriptHost.run(
                    code = code,
                    entry = "main",
                    context = ctx,
                    thisObject = target,
                    extra = arrayOfNulls(0),
                    out = out
                )
            } catch (t: Throwable) {
                out.fail(t.javaClass.name + ": " + t.message)
            }
            val text = out.text()
            W.ui {
                lastScriptOutput = if (text.isEmpty()) "（无输出）" else text
                renderTab()
            }
        }
        runner.name = "rm-script"
        runner.isDaemon = true
        runner.start()
    }

    // ------------------------------------------------------------ log tab

    private fun logScreen(): View {
        val box = W.stack(ctx, 10f)
        val bar = OUi.horizontal(ctx, pal, 0f)
        bar.addView(OUi.button(ctx, pal, "刷新", true) { renderTab() })
        bar.addView(space(ctx))
        bar.addView(OUi.button(ctx, pal, "复制") {
            W.copy(ctx, "log", RmLog.snapshot().joinToString("\n"))
        })
        bar.addView(space(ctx))
        bar.addView(OUi.button(ctx, pal, "清空") {
            RmLog.clearMemory()
            renderTab()
        })
        box.addView(bar)

        val lines = RmLog.snapshot()
        val text = if (lines.isEmpty()) "（暂无日志）" else lines.joinToString("\n")
        val view = OUi.mono(ctx, pal, text, 10.5f)
        val scroll = ScrollView(ctx)
        scroll.addView(view)
        box.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        box.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        return box
    }

    // -------------------------------------------------------- shared parts

    private fun space(c: Context): View {
        val v = View(c)
        v.layoutParams = LinearLayout.LayoutParams(OUi.dp(c, 6f), 1)
        return v
    }

    private fun withHeader(header: View, body: View): LinearLayout {
        val l = LinearLayout(ctx)
        l.orientation = LinearLayout.VERTICAL
        l.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        l.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        l.addView(OUi.divider(ctx, pal))
        l.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return l
    }

    private fun emptyHint(msg: String): View {
        val t = TextView(ctx)
        t.text = msg
        t.setTextColor(pal.textSecondary)
        t.gravity = Gravity.CENTER
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
        t.setPadding(OUi.dp(ctx, 16f), OUi.dp(ctx, 24f), OUi.dp(ctx, 16f), OUi.dp(ctx, 16f))
        return t
    }

    private fun filterHeader(
        label: String,
        filter: String,
        onFilter: (String) -> Unit,
        toggles: List<Pair<String, Boolean>>,
        onToggle: (Int) -> Unit
    ): View {
        val bar = OUi.horizontal(ctx, pal, 0f)
        bar.setPadding(OUi.dp(ctx, 8f), OUi.dp(ctx, 4f), OUi.dp(ctx, 8f), OUi.dp(ctx, 4f))

        val count = TextView(ctx)
        count.text = label
        count.setTextColor(pal.textSecondary)
        count.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11f)
        bar.addView(count)
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))

        for (i in toggles.indices) {
            val pair = toggles[i]
            val b = OUi.button(ctx, pal, pair.first, pair.second) { onToggle(i) }
            if (!pair.second) b.setTextColor(pal.textSecondary)
            bar.addView(b)
            bar.addView(space(ctx))
        }
        bar.addView(OUi.button(ctx, pal, if (filter.isEmpty()) "搜索" else "清除搜索") {
            if (filter.isNotEmpty()) {
                onFilter("")
            } else {
                W.prompt(ctx, "过滤", "输入关键字", "") { s -> onFilter(s) }
            }
        })
        return bar
    }

    private fun listView(
        rows: List<Row>,
        onClick: (Row) -> Unit,
        onLongClick: ((Row) -> Boolean)? = null
    ): ListView {
        val list = ListView(ctx)
        list.setBackgroundColor(Color.TRANSPARENT)
        list.divider = android.graphics.drawable.ColorDrawable(pal.divider)
        list.dividerHeight = Math.max(1, OUi.dp(ctx, 0.6f))
        list.isFastScrollEnabled = true
        val adapter = RowAdapter(ctx, pal, rows)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (position >= 0 && position < adapter.count) onClick(adapter.getItem(position))
        }
        if (onLongClick != null) {
            list.setOnItemLongClickListener { _, _, position, _ ->
                if (position >= 0 && position < adapter.count) {
                    onLongClick(adapter.getItem(position))
                } else {
                    false
                }
            }
        }
        return list
    }

    private fun pickSlot(onPick: (String) -> Unit) {
        val entries = Registry.entries()
        if (entries.isEmpty()) {
            toast("变量槽为空，先在字段/方法里「存入变量槽」")
            return
        }
        val items = ArrayList<String>()
        val values = ArrayList<String>()
        for (e in entries) {
            items.add(e.label + "  =  " + Values.describe(e.value))
            values.add(Values.slotName(e.index))
        }
        W.sheet(ctx, "选择变量槽", items) { i -> onPick(values[i]) }
    }

    // ------------------------------------------------- ObjectActions.Host

    override fun inspect(target: Any?) {
        W.ui { focusOn(target) }
    }

    override fun showText(title: String, text: String) {
        W.showText(ctx, title, text)
    }

    override fun choose(title: String, items: List<String>, payloads: List<Any?>, onPick: (Int) -> Unit) {
        W.sheet(ctx, title, items) { i -> onPick(i) }
    }

    override fun prompt(
        title: String,
        hint: String,
        initial: String,
        multiline: Boolean,
        onOk: (String) -> Unit
    ) {
        W.prompt(ctx, title, hint, initial, multiline) { s -> onOk(s) }
    }

    override fun toast(msg: String) {
        W.toast(ctx, msg)
    }

    override fun copy(label: String, text: String) {
        W.copy(ctx, label, text)
    }

    // ------------------------------------------------------------ view pick

    private fun startViewPicker() {
        val activity = ctx as? Activity ?: ActivityTracker.top()
        if (activity == null) {
            toast("没有可用的 Activity")
            return
        }
        val px = panel.x
        val py = panel.y
        val pw = panel.w
        val ph = panel.h
        panel.hide()
        ViewPicker.start(activity, surface, pal) { picked ->
            if (pw > 0 && ph > 0) panel.show(px, py, pw, ph)
            if (picked != null) focusOn(picked)
        }
    }
}
