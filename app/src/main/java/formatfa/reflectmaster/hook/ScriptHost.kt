package formatfa.reflectmaster.hook

import android.content.Context
import com.github.esrrhs.fakescript.callback
import com.github.esrrhs.fakescript.fake
import com.github.esrrhs.fakescript.fk
import com.github.esrrhs.fakescript.fkconfig
import formatfa.reflectmaster.core.RmLog
import formatfa.reflectmaster.hook.bridge.io
import formatfa.reflectmaster.hook.bridge.rf

/**
 * FakeScript runner shared by the console's script tab and by user defined hooks.
 *
 * The 1.x code created a `fake` instance per invocation, registered the bridge
 * classes every time, and never surfaced `on_error` anywhere the user could see
 * (the hook callback literally had an empty `on_print`). Errors now go to the
 * shared log sink and the return value is handed back to the caller.
 *
 * Binding names stay `rf.*` / `io.*`: `fk.regclass` derives the script-visible
 * name from `Class.getSimpleName()`, and existing user scripts already say
 * `rf.print(...)`.
 */
object ScriptHost {

    /** Everything a script produced, in order. */
    class Output {
        private val lines = ArrayList<String>()

        @Volatile
        var error: String? = null
            private set

        fun line(s: String) {
            synchronized(lines) { lines.add(s) }
        }

        fun fail(s: String) {
            error = s
            line("[错误] " + s)
        }

        fun text(): String = synchronized(lines) { lines.joinToString("\n") }

        fun list(): List<String> = synchronized(lines) { ArrayList(lines) }
    }

    /**
     * Bind the per-run state, then execute [entry] in [code].
     *
     * @param extra extra arguments handed to the entry function after its own
     *              parameters (used to pass the `MethodHookParam` to hooks).
     * @return whatever the script returned, or null.
     */
    fun run(
        code: String,
        entry: String,
        context: Context?,
        thisObject: Any?,
        extra: Array<Any?> = emptyArray(),
        out: Output = Output()
    ): Any? {
        val src = code.trim()
        if (src.isEmpty()) {
            out.fail("脚本为空")
            return null
        }

        ScriptState.bind(context, thisObject, out)
        return try {
            val conf = fkconfig()
            val f: fake = fk.newfake(conf)
            fk.openbaselib(f)
            fk.set_callback(f, object : callback {
                override fun on_error(
                    fkInstance: fake?,
                    file: String?,
                    lineno: Int,
                    func: String?,
                    str: String?
                ) {
                    val msg = "line " + lineno + " func " + (func ?: "?") + ": " + (str ?: "unknown")
                    out.fail(msg)
                    RmLog.w("FakeScript " + msg)
                }

                override fun on_print(fkInstance: fake?, str: String?) {
                    val s = str ?: ""
                    out.line(s)
                    RmLog.d("[script] " + s)
                }
            })
            fk.regclass(f, rf::class.java)
            fk.regclass(f, io::class.java)

            if (!fk.parsestr(f, src)) {
                out.fail("语法解析失败")
                return null
            }
            if (!fk.isfunc(f, entry)) {
                out.fail("脚本里没有入口函数 " + entry + "()")
                return null
            }
            val result = if (extra.isEmpty()) {
                fk.run(f, entry)
            } else {
                fk.run(f, entry, *extra)
            }
            if (result != null) out.line("[返回] " + result)
            result
        } catch (t: Throwable) {
            out.fail(t.javaClass.name + ": " + t.message)
            RmLog.e("脚本执行异常", t)
            null
        } finally {
            // The vendored FakeScript 1.0.3 exposes no public teardown; the
            // instance is dropped here and collected once the run finishes.
            ScriptState.unbind()
        }
    }

    /** Compile-only check used by the editors lives in `core.ScriptSyntax`. */
}
