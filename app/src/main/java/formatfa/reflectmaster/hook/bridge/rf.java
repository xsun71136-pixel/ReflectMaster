package formatfa.reflectmaster.hook.bridge;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import formatfa.reflectmaster.core.RmLog;
import formatfa.reflectmaster.hook.ActivityTracker;
import formatfa.reflectmaster.hook.KeyTrigger;
import formatfa.reflectmaster.hook.ScriptState;
import formatfa.reflectmaster.overlay.ConsoleController;
import formatfa.reflectmaster.reflect.Reflect;
import formatfa.reflectmaster.reflect.Registry;
import formatfa.reflectmaster.reflect.Values;

/**
 * Reflection bridge exposed to FakeScript as {@code rf.*}.
 *
 * Written in Java on purpose. {@code fk.regclass()} reflects over
 * {@code getMethods()} and derives the script-visible name from
 * {@code Modifier.isStatic} plus {@code Class.getSimpleName()}, so the exact
 * static-method shape matters; Kotlin would need {@code @JvmStatic} on every
 * member of an object and would also rename the class.
 *
 * The class name must stay {@code rf} - existing user scripts call
 * {@code rf.print(...)} and the name comes from {@code getSimpleName()}.
 *
 * Every method swallows its own exceptions and returns a printable value, so a
 * bad script can never take the host application down.
 */
public class rf {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ------------------------------------------------------------- console

    /** Print a line into the console's script output pane. */
    public static String print(Object o) {
        String s = Values.INSTANCE.describe(o);
        ScriptState.INSTANCE.emit(s);
        return s;
    }

    /** Write to the shared module log (visible in the log tab and in LSPosed). */
    public static String log(Object o) {
        String s = String.valueOf(o);
        RmLog.INSTANCE.i("[script] " + s);
        ScriptState.INSTANCE.emit(s);
        return s;
    }

    /** Show a toast on the host's main thread. */
    public static int toast(final Object o) {
        final Context ctx = ScriptState.INSTANCE.context();
        if (ctx == null) return 0;
        final String s = Values.INSTANCE.describe(o);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Toast.makeText(ctx.getApplicationContext(), s, Toast.LENGTH_SHORT).show();
                } catch (Throwable t) {
                    RmLog.INSTANCE.w("toast 失败: " + t.getMessage());
                }
            }
        });
        return 1;
    }

    /** Copy text to the clipboard. Returns 1 on success. */
    public static int copy(Object o) {
        Context ctx = ScriptState.INSTANCE.context();
        if (ctx == null) return 0;
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) return 0;
            cm.setPrimaryClip(ClipData.newPlainText("ReflectMaster", Values.INSTANCE.describe(o)));
            return 1;
        } catch (Throwable t) {
            RmLog.INSTANCE.w("复制失败: " + t.getMessage());
            return 0;
        }
    }

    /** Open (or bring to front) the floating console. */
    public static int summon() {
        KeyTrigger.INSTANCE.summon();
        return 1;
    }

    // ------------------------------------------------------------- objects

    /** The object the script was launched on ($this). */
    public static Object thiz() {
        return ScriptState.INSTANCE.thisObject();
    }

    /** A Context valid inside the host process. */
    public static Context ctx() {
        return ScriptState.INSTANCE.context();
    }

    /** The most recently resumed Activity, or null. */
    public static Activity activity() {
        return ActivityTracker.INSTANCE.top();
    }

    /** The host Application object, or null. */
    public static Object app() {
        return ActivityTracker.INSTANCE.getApplication();
    }

    /** Read a variable slot: rf.slot(0) == $v0. */
    public static Object slot(int index) {
        return Registry.INSTANCE.getOrNull(index);
    }

    /** Write a variable slot. Returns the slot name, e.g. "$v3". */
    public static String setSlot(int index, Object value) {
        Registry.INSTANCE.set(index, value, null);
        return Values.INSTANCE.slotName(index);
    }

    /** Store an object into the first free slot; returns the slot name or "". */
    public static String store(Object value) {
        int i = Registry.INSTANCE.store(value, null);
        return i < 0 ? "" : Values.INSTANCE.slotName(i);
    }

    /** Number of occupied slots. */
    public static int slotCount() {
        return Registry.INSTANCE.count();
    }

    /** Clear every slot. */
    public static int clearSlots() {
        Registry.INSTANCE.clear();
        return 1;
    }

    // ---------------------------------------------------------- reflection

    /** Resolve a class by name ("int", "int[]", "java.lang.String", "[I" ...). */
    public static Object findClass(String name) {
        ClassLoader loader = loaderOf(ScriptState.INSTANCE.thisObject());
        try {
            return Reflect.INSTANCE.findClass(name, loader);
        } catch (Throwable t) {
            ScriptState.INSTANCE.fail("findClass: " + t.getMessage());
            return null;
        }
    }

    /** List every field of an object (or of a Class for statics). */
    public static String fields(Object target) {
        Class<?> cls = classOf(target);
        if (cls == null) return "";
        List<Field> all = Reflect.INSTANCE.fields(cls, true, true);
        Object instance = (target instanceof Class) ? null : target;
        StringBuilder sb = new StringBuilder();
        for (Field f : all) {
            sb.append(Reflect.INSTANCE.signatureOf(f)).append(" = ");
            Reflect.ReadResult r = Reflect.INSTANCE.read(f, instance);
            sb.append(r.getOk() ? Values.INSTANCE.describe(r.getValue()) : ("<" + r.getError() + ">"));
            sb.append('\n');
        }
        ScriptState.INSTANCE.emit(sb.toString());
        return sb.toString();
    }

    /** List every method of an object (or of a Class for statics). */
    public static String methods(Object target) {
        Class<?> cls = classOf(target);
        if (cls == null) return "";
        List<Method> all = Reflect.INSTANCE.methods(cls, true, true);
        StringBuilder sb = new StringBuilder();
        for (Method m : all) sb.append(Reflect.INSTANCE.signatureOf(m)).append('\n');
        ScriptState.INSTANCE.emit(sb.toString());
        return sb.toString();
    }

    /** Read a field by name; searches the whole class hierarchy. */
    public static Object get(Object target, String fieldName) {
        Field f = findField(target, fieldName);
        if (f == null) {
            ScriptState.INSTANCE.fail("没有字段 " + fieldName);
            return null;
        }
        Reflect.ReadResult r = Reflect.INSTANCE.read(f, (target instanceof Class) ? null : target);
        if (!r.getOk()) ScriptState.INSTANCE.fail(String.valueOf(r.getError()));
        return r.getValue();
    }

    /** Write a field by name. Returns 1 on success, 0 on failure. */
    public static int set(Object target, String fieldName, Object value) {
        Field f = findField(target, fieldName);
        if (f == null) {
            ScriptState.INSTANCE.fail("没有字段 " + fieldName);
            return 0;
        }
        Object converted;
        try {
            converted = Values.INSTANCE.coerce(value, f.getType());
        } catch (Throwable t) {
            converted = value;
        }
        Reflect.ReadResult r = Reflect.INSTANCE.write(f, (target instanceof Class) ? null : target, converted);
        if (!r.getOk()) {
            ScriptState.INSTANCE.fail(String.valueOf(r.getError()));
            return 0;
        }
        return 1;
    }

    /** Call a no-argument method. */
    public static Object call0(Object target, String name) {
        return invoke(target, name, new Object[0]);
    }

    /** Call a one-argument method. */
    public static Object call1(Object target, String name, Object a0) {
        return invoke(target, name, new Object[]{a0});
    }

    /** Call a two-argument method. */
    public static Object call2(Object target, String name, Object a0, Object a1) {
        return invoke(target, name, new Object[]{a0, a1});
    }

    /** Call a three-argument method. */
    public static Object call3(Object target, String name, Object a0, Object a1, Object a2) {
        return invoke(target, name, new Object[]{a0, a1, a2});
    }

    /** Call a static no-argument method by class name. */
    public static Object callStatic0(String className, String name) {
        Object cls = findClass(className);
        return cls == null ? null : invoke(cls, name, new Object[0]);
    }

    /** Call a static one-argument method by class name. */
    public static Object callStatic1(String className, String name, Object a0) {
        Object cls = findClass(className);
        return cls == null ? null : invoke(cls, name, new Object[]{a0});
    }

    /** Read a static field by class name. */
    public static Object getStatic(String className, String fieldName) {
        Object cls = findClass(className);
        return cls == null ? null : get(cls, fieldName);
    }

    /** Write a static field by class name. */
    public static int setStatic(String className, String fieldName, Object value) {
        Object cls = findClass(className);
        return cls == null ? 0 : set(cls, fieldName, value);
    }

    /** Create an instance through the no-argument constructor. */
    public static Object newInstance0(String className) {
        return construct(className, new Object[0]);
    }

    /** Create an instance through the best matching 1-argument constructor. */
    public static Object newInstance1(String className, Object a0) {
        return construct(className, new Object[]{a0});
    }

    /** Create an instance through the best matching 2-argument constructor. */
    public static Object newInstance2(String className, Object a0, Object a1) {
        return construct(className, new Object[]{a0, a1});
    }

    private static Object construct(String className, Object[] args) {
        Object cls = findClass(className);
        if (!(cls instanceof Class)) return null;
        try {
            Constructor<?> k = XposedHelpers.findConstructorBestMatch((Class<?>) cls, args);
            Reflect.ReadResult r = Reflect.INSTANCE.newInstance(k, args);
            if (!r.getOk()) {
                ScriptState.INSTANCE.fail(String.valueOf(r.getError()));
                return null;
            }
            return r.getValue();
        } catch (Throwable t) {
            ScriptState.INSTANCE.fail("构造 " + className + " 失败: " + t.getMessage());
            return null;
        }
    }

    /** Open the inspector for an object inside the floating console. */
    public static int inspect(final Object target) {
        if (target == null) return 0;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    ConsoleController.INSTANCE.inspect(target);
                } catch (Throwable t) {
                    RmLog.INSTANCE.w("inspect 失败: " + t.getMessage());
                }
            }
        });
        return 1;
    }

    /** Describe any value the way the console list rows do. */
    public static String describe(Object o) {
        return Values.INSTANCE.describe(o);
    }

    /** Runtime class name of a value. */
    public static String typeOf(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    // ------------------------------------------------- inside a hook script

    /**
     * Replace the return value of the hooked method.
     * Only meaningful while a user defined hook script runs. Returns 1 on success.
     */
    public static int setResult(Object value) {
        XC_MethodHook.MethodHookParam p = ScriptState.INSTANCE.hookParam();
        if (p == null) {
            ScriptState.INSTANCE.fail("setResult 只能在 hook 脚本里使用");
            return 0;
        }
        try {
            p.setResult(value);
            return 1;
        } catch (Throwable t) {
            ScriptState.INSTANCE.fail("setResult 失败: " + t.getMessage());
            return 0;
        }
    }

    /** Current return value (after-hooks only). */
    public static Object getResult() {
        XC_MethodHook.MethodHookParam p = ScriptState.INSTANCE.hookParam();
        if (p == null) return null;
        try {
            return p.getResult();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Number of arguments of the hooked method. */
    public static int argCount() {
        XC_MethodHook.MethodHookParam p = ScriptState.INSTANCE.hookParam();
        if (p == null || p.args == null) return 0;
        return p.args.length;
    }

    /** Read one argument of the hooked method. */
    public static Object getArg(int index) {
        XC_MethodHook.MethodHookParam p = ScriptState.INSTANCE.hookParam();
        if (p == null || p.args == null) return null;
        if (index < 0 || index >= p.args.length) return null;
        return p.args[index];
    }

    /** Replace one argument of the hooked method (before-hooks only). */
    public static int setArg(int index, Object value) {
        XC_MethodHook.MethodHookParam p = ScriptState.INSTANCE.hookParam();
        if (p == null || p.args == null) {
            ScriptState.INSTANCE.fail("setArg 只能在 hook 脚本里使用");
            return 0;
        }
        if (index < 0 || index >= p.args.length) {
            ScriptState.INSTANCE.fail("参数下标越界: " + index + "/" + p.args.length);
            return 0;
        }
        p.args[index] = value;
        return 1;
    }

    /** 1 when the hooked method threw (after-hooks only). */
    public static int hasThrowable() {
        XC_MethodHook.MethodHookParam p = ScriptState.INSTANCE.hookParam();
        if (p == null) return 0;
        try {
            return p.hasThrowable() ? 1 : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Text form of the thrown exception, or "". */
    public static String throwable() {
        XC_MethodHook.MethodHookParam p = ScriptState.INSTANCE.hookParam();
        if (p == null) return "";
        try {
            Throwable t = p.getThrowable();
            if (t == null) return "";
            return t.getClass().getName() + ": " + t.getMessage();
        } catch (Throwable t) {
            return "";
        }
    }

    /** The hooked instance (same as thiz() inside a hook script). */
    public static Object hookThis() {
        XC_MethodHook.MethodHookParam p = ScriptState.INSTANCE.hookParam();
        return p == null ? null : p.thisObject;
    }

    // ------------------------------------------------------------- helpers

    private static ClassLoader loaderOf(Object target) {
        if (target instanceof Class) return ((Class<?>) target).getClassLoader();
        if (target != null) return target.getClass().getClassLoader();
        Context ctx = ScriptState.INSTANCE.context();
        if (ctx != null) return ctx.getClassLoader();
        Activity a = ActivityTracker.INSTANCE.top();
        return a == null ? null : a.getClassLoader();
    }

    private static Class<?> classOf(Object target) {
        if (target == null) {
            ScriptState.INSTANCE.fail("目标为 null");
            return null;
        }
        if (target instanceof Class) return (Class<?>) target;
        return target.getClass();
    }

    private static Field findField(Object target, String name) {
        Class<?> cls = classOf(target);
        if (cls == null) return null;
        List<Field> all = Reflect.INSTANCE.fields(cls, true, true);
        for (Field f : all) if (f.getName().equals(name)) return f;
        return null;
    }

    private static Object invoke(Object target, String name, Object[] args) {
        Class<?> cls = classOf(target);
        if (cls == null) return null;
        Object receiver = (target instanceof Class) ? null : target;
        List<Method> all = Reflect.INSTANCE.methods(cls, true, true);
        Method best = null;
        for (Method m : all) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterTypes().length != args.length) continue;
            best = m;
            if (isAssignable(m.getParameterTypes(), args)) break;
        }
        if (best == null) {
            ScriptState.INSTANCE.fail("没有匹配的方法 " + name + "/" + args.length + " 参数");
            return null;
        }
        Object[] converted = new Object[args.length];
        Class<?>[] types = best.getParameterTypes();
        for (int i = 0; i < args.length; i++) {
            try {
                converted[i] = args[i] == null ? null : Values.INSTANCE.coerce(args[i], types[i]);
            } catch (Throwable t) {
                converted[i] = args[i];
            }
        }
        Reflect.ReadResult r = Reflect.INSTANCE.invoke(best, receiver, converted);
        if (!r.getOk()) {
            ScriptState.INSTANCE.fail(String.valueOf(r.getError()));
            return null;
        }
        return r.getValue();
    }

    private static boolean isAssignable(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            Object a = args[i];
            if (a == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            Class<?> t = types[i];
            if (t.isPrimitive()) {
                String n = t.getName();
                if ("int".equals(n) && !(a instanceof Integer)) return false;
                if ("long".equals(n) && !(a instanceof Long)) return false;
                if ("boolean".equals(n) && !(a instanceof Boolean)) return false;
                if ("double".equals(n) && !(a instanceof Double)) return false;
                if ("float".equals(n) && !(a instanceof Float)) return false;
                if ("short".equals(n) && !(a instanceof Short)) return false;
                if ("byte".equals(n) && !(a instanceof Byte)) return false;
                if ("char".equals(n) && !(a instanceof Character)) return false;
            } else if (!t.isInstance(a)) {
                return false;
            }
        }
        return true;
    }
}
