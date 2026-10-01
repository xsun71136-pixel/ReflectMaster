package formatfa.reflectmaster.hook

import android.content.Context
import de.robv.android.xposed.XC_MethodHook

/**
 * Per-run state handed to the FakeScript bridge classes.
 *
 * Kept separate from `bridge.rf` / `bridge.io` on purpose: `fk.regclass()` binds
 * **every** public method of the class it is given, so putting lifecycle methods
 * there would leak `bind()` / `unbind()` into the script namespace.
 *
 * A [ThreadLocal] is used because a user hook can fire on any thread of the host
 * app while the console runs scripts on the main thread.
 */
object ScriptState {

    private val context = ThreadLocal<Context?>()
    private val thisObject = ThreadLocal<Any?>()
    private val output = ThreadLocal<ScriptHost.Output?>()
    private val hookParam = ThreadLocal<XC_MethodHook.MethodHookParam?>()

    fun bind(ctx: Context?, thiz: Any?, out: ScriptHost.Output?) {
        context.set(ctx)
        thisObject.set(thiz)
        output.set(out)
    }

    fun unbind() {
        context.remove()
        thisObject.remove()
        output.remove()
    }

    /** Extra binding active only while a user defined hook script runs. */
    fun bindHook(param: XC_MethodHook.MethodHookParam?) {
        hookParam.set(param)
    }

    fun unbindHook() {
        hookParam.remove()
    }

    fun hookParam(): XC_MethodHook.MethodHookParam? = hookParam.get()

    fun context(): Context? = context.get() ?: ActivityTracker.bestContext()

    fun thisObject(): Any? = thisObject.get()

    fun output(): ScriptHost.Output? = output.get()

    fun emit(line: String) {
        output.get()?.line(line)
    }

    fun fail(line: String) {
        val o = output.get()
        if (o != null) o.fail(line) else formatfa.reflectmaster.core.RmLog.w(line)
    }
}
