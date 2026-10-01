package formatfa.reflectmaster.ui

import android.content.ClipboardManager
import android.os.Bundle
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import formatfa.reflectmaster.R
import formatfa.reflectmaster.core.ConfigStore
import formatfa.reflectmaster.core.ScriptItem
import formatfa.reflectmaster.core.ScriptSyntax
import formatfa.reflectmaster.util.Ui

/**
 * FakeScript editor.
 *
 * The 1.x editor was an `AlertDialog` around a plain `EditText` inside
 * `ScriptManager`, with a separate half-finished syntax-highlight widget
 * (`view.formatfa.ftexteditor`, 36 files) that only `CodeDialog` used. Both are
 * replaced by this full-screen editor plus a real syntax check that runs the
 * vendored FakeScript compiler.
 */
class ScriptEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_INDEX = "index"

        private val TEMPLATE = """func main()
    # rf.thiz() 是呼出控制台时的当前对象
    rf.print("当前对象: " + rf.describe(rf.thiz()))

    # 列出全部字段 / 方法
    rf.fields(rf.thiz())

    # 读写字段
    # rf.set(rf.thiz(), "someField", 1)
    # rf.print(rf.get(rf.thiz(), "someField"))

    # 调用方法
    # rf.print(rf.call0(rf.thiz(), "toString"))

    # 变量槽
    # rf.print(rf.store(rf.thiz()))   # 返回槽名，例如 v0（脚本里写成美元符 + v0）
    # rf.print(rf.slot(0))
end"""
    }

    private var index = -1
    private lateinit var nameInput: EditText
    private lateinit var codeInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_script_edit)
        Ui.toolbar(this, findViewById<MaterialToolbar>(R.id.toolbar), true)

        index = intent.getIntExtra(EXTRA_INDEX, -1)
        nameInput = findViewById(R.id.et_name)
        codeInput = findViewById(R.id.et_code)

        val scripts = ConfigStore.load(this).scripts
        if (index in scripts.indices) {
            nameInput.setText(scripts[index].name)
            codeInput.setText(scripts[index].code)
        } else {
            codeInput.setText(TEMPLATE)
        }

        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btn_check).setOnClickListener { check() }
        findViewById<MaterialButton>(R.id.btn_template).setOnClickListener {
            codeInput.setText(TEMPLATE)
        }
        findViewById<MaterialButton>(R.id.btn_paste).setOnClickListener { paste() }
    }

    private fun paste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
        val text = cm?.primaryClip?.let { if (it.itemCount > 0) it.getItemAt(0).text else null }
        if (text == null) {
            Ui.toast(this, getString(R.string.none))
            return
        }
        val start = codeInput.selectionStart.coerceAtLeast(0)
        codeInput.text.insert(start, text)
    }

    private fun check() {
        val code = codeInput.text?.toString() ?: ""
        val task = java.util.concurrent.Callable<String?> { ScriptSyntax.check(code, "main") }
        Ui.async(task) { error ->
            if (error == null) {
                Ui.toast(this, getString(R.string.syntax_ok))
            } else {
                Ui.alert(this, getString(R.string.check_syntax), getString(R.string.syntax_error, error))
            }
        }
    }

    private fun save() {
        val name = nameInput.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) {
            Ui.toast(this, getString(R.string.name_empty))
            return
        }
        val code = codeInput.text?.toString() ?: ""
        val scripts = ArrayList(ConfigStore.load(this).scripts)
        val item = ScriptItem(name, code)
        if (index in scripts.indices) {
            scripts[index] = item
        } else {
            scripts.add(item)
        }
        ConfigStore.saveScripts(this, scripts)
        Ui.toast(this, getString(R.string.saved))
        finish()
    }
}
