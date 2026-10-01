package formatfa.reflectmaster.ui

import android.os.Bundle
import android.widget.EditText
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import formatfa.reflectmaster.R
import formatfa.reflectmaster.core.ConfigStore
import formatfa.reflectmaster.core.HookRule
import formatfa.reflectmaster.core.ScriptSyntax
import formatfa.reflectmaster.util.Ui
import java.util.concurrent.Callable

/**
 * Editor for one [HookRule].
 *
 * Parameter types are one per line instead of space separated, so types whose
 * names contain no spaces stay unambiguous and an empty list is expressible.
 */
class HookEditActivity : AppCompatActivity() {

    companion object {
        private val TEMPLATE = """func hook(param)
    # param 就是 XposedBridge 的 MethodHookParam
    rf.print("命中: " + rf.describe(rf.hookThis()))
    rf.print("参数个数: " + rf.argCount())

    # before: 改写参数
    # rf.setArg(0, 1)

    # before: 直接短路返回
    # rf.setResult(true)

    # after: 改写返回值
    # rf.setResult(rf.getResult())
end"""
    }

    private var index = -1

    private lateinit var nameInput: EditText
    private lateinit var pkgInput: EditText
    private lateinit var classInput: EditText
    private lateinit var methodInput: EditText
    private lateinit var paramsInput: EditText
    private lateinit var codeInput: EditText
    private lateinit var timing: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hook_edit)
        Ui.toolbar(this, findViewById<MaterialToolbar>(R.id.toolbar), true)

        index = intent.getIntExtra(HooksActivity.EXTRA_INDEX, -1)

        nameInput = findViewById(R.id.et_name)
        pkgInput = findViewById(R.id.et_pkg)
        classInput = findViewById(R.id.et_class)
        methodInput = findViewById(R.id.et_method)
        paramsInput = findViewById(R.id.et_params)
        codeInput = findViewById(R.id.et_code)
        timing = findViewById(R.id.rg_timing)

        val rules = ConfigStore.load(this).hooks
        if (index in rules.indices) {
            val r = rules[index]
            nameInput.setText(r.name)
            pkgInput.setText(r.pkg)
            classInput.setText(r.className)
            methodInput.setText(r.methodName)
            val sb = StringBuilder()
            for (p in r.paramTypes) sb.append(p).append('\n')
            paramsInput.setText(sb.toString())
            timing.check(if (r.after) R.id.rb_after else R.id.rb_before)
            codeInput.setText(r.code)
        } else {
            timing.check(R.id.rb_before)
            codeInput.setText(TEMPLATE)
        }

        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btn_template).setOnClickListener {
            codeInput.setText(TEMPLATE)
        }
        findViewById<MaterialButton>(R.id.btn_check).setOnClickListener { check() }
    }

    private fun check() {
        val code = codeInput.text?.toString() ?: ""
        val task = Callable<String?> { ScriptSyntax.check(code, "hook") }
        Ui.async(task) { error ->
            if (error == null) {
                Ui.toast(this, getString(R.string.syntax_ok))
            } else {
                Ui.alert(this, getString(R.string.check_syntax), getString(R.string.syntax_error, error))
            }
        }
    }

    private fun parseParams(): List<String> {
        val raw = paramsInput.text?.toString() ?: ""
        val out = ArrayList<String>()
        for (line in raw.split('\n')) {
            val t = line.trim()
            if (t.isNotEmpty()) out.add(t)
        }
        return out
    }

    private fun save() {
        val cls = classInput.text?.toString()?.trim() ?: ""
        val method = methodInput.text?.toString()?.trim() ?: ""
        if (cls.isEmpty() || method.isEmpty()) {
            Ui.toast(this, getString(R.string.hook_invalid))
            return
        }
        var name = nameInput.text?.toString()?.trim() ?: ""
        if (name.isEmpty()) name = method

        val rules = ArrayList(ConfigStore.load(this).hooks)
        val previousEnabled = if (index in rules.indices) rules[index].enabled else true

        val rule = HookRule(
            name = name,
            pkg = pkgInput.text?.toString()?.trim() ?: "",
            className = cls,
            methodName = method,
            paramTypes = parseParams(),
            after = timing.checkedRadioButtonId == R.id.rb_after,
            code = codeInput.text?.toString() ?: "",
            enabled = previousEnabled
        )

        if (index in rules.indices) {
            rules[index] = rule
        } else {
            rules.add(rule)
        }
        ConfigStore.saveHooks(this, rules)
        Ui.toast(this, getString(R.string.hook_applied))
        finish()
    }
}
