package formatfa.reflectmaster.core

import com.github.esrrhs.fakescript.callback
import com.github.esrrhs.fakescript.fake
import com.github.esrrhs.fakescript.fk
import com.github.esrrhs.fakescript.fkconfig

/**
 * Parse-only syntax check for the editors.
 *
 * Deliberately does **not** register the `rf` / `io` bridge classes: those live
 * in the hook package and reference `de.robv.android.xposed.*`, which is a
 * `compileOnly` dependency and therefore absent from the module's own process.
 *
 * That is safe because FakeScript resolves call targets at run time - the
 * compiler emits an unknown function name as a plain string constant
 * (`compiler.java`, "4 直接字符串使用") - so a script using `rf.print(...)`
 * parses identically with or without the bridge registered.
 */
object ScriptSyntax {

    /**
     * @return null when the code parses and contains [entry]; otherwise a
     *         human readable reason.
     */
    fun check(code: String, entry: String): String? {
        val src = code.trim()
        if (src.isEmpty()) return "脚本为空"
        var error: String? = null
        return try {
            val f: fake = fk.newfake(fkconfig())
            fk.set_callback(f, object : callback {
                override fun on_error(
                    fkInstance: fake?,
                    file: String?,
                    lineno: Int,
                    func: String?,
                    str: String?
                ) {
                    if (error == null) {
                        error = "第 " + lineno + " 行: " + (str ?: "unknown")
                    }
                }

                override fun on_print(fkInstance: fake?, str: String?) {
                }
            })
            val parsed = fk.parsestr(f, src)
            if (!parsed && error == null) {
                error = "语法解析失败"
            }
            if (error == null && !fk.isfunc(f, entry)) {
                error = "缺少入口函数 " + entry + "()"
            }
            error
        } catch (t: Throwable) {
            t.javaClass.simpleName + ": " + t.message
        }
    }
}
