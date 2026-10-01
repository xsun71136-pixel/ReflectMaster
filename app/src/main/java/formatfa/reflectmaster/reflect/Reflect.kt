package formatfa.reflectmaster.reflect

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedHashSet

/**
 * Reflection primitives shared by the floating console and the script bridge.
 *
 * Every accessor is defensive: a hooked process routinely contains classes whose
 * static initialisers explode, fields that Android's hidden-API policy refuses
 * and `toString()` implementations that throw. None of that may take the host
 * application down, so failures are reported as values instead of propagating.
 */
object Reflect {

    private val PRIMITIVE_MAP: Map<String, Class<*>> = mapOf(
        "int" to Int::class.javaPrimitiveType!!,
        "long" to Long::class.javaPrimitiveType!!,
        "short" to Short::class.javaPrimitiveType!!,
        "byte" to Byte::class.javaPrimitiveType!!,
        "char" to Char::class.javaPrimitiveType!!,
        "boolean" to Boolean::class.javaPrimitiveType!!,
        "float" to Float::class.javaPrimitiveType!!,
        "double" to Double::class.javaPrimitiveType!!,
        "void" to java.lang.Void.TYPE
    )

    /** Alias accepted in hook parameter lists and in the "find class" dialog. */
    private val FRIENDLY_ALIASES: Map<String, String> = mapOf(
        "String" to "java.lang.String",
        "Integer" to "java.lang.Integer",
        "Boolean" to "java.lang.Boolean",
        "Long" to "java.lang.Long",
        "Float" to "java.lang.Float",
        "Double" to "java.lang.Double",
        "Object" to "java.lang.Object",
        "Bundle" to "android.os.Bundle",
        "Context" to "android.content.Context",
        "View" to "android.view.View",
        "Activity" to "android.app.Activity"
    )

    class ClassNotFound(name: String, cause: Throwable?) :
        Exception("找不到类: " + name + if (cause == null) "" else " (" + cause.message + ")")

    /**
     * Resolve a class by name.
     * Accepts `int`, `int[]`, `[I`, `[Ljava.lang.String;`, `java.lang.String`
     * and a few friendly aliases.
     */
    @Throws(ClassNotFound::class)
    fun findClass(name: String, loader: ClassLoader?): Class<*> {
        val raw = name.trim()
        if (raw.isEmpty()) throw ClassNotFound(name, null)

        PRIMITIVE_MAP[raw]?.let { return it }

        if (raw.endsWith("[]")) {
            val component = findClass(raw.substring(0, raw.length - 2), loader)
            return java.lang.reflect.Array.newInstance(component, 0).javaClass
        }

        FRIENDLY_ALIASES[raw]?.let { alias ->
            PRIMITIVE_MAP[alias]?.let { return it }
        }
        val resolvedName = FRIENDLY_ALIASES[raw] ?: raw

        val loaders = ArrayList<ClassLoader?>()
        if (loader != null) loaders.add(loader)
        loaders.add(Reflect::class.java.classLoader)
        loaders.add(ClassLoader.getSystemClassLoader())

        var lastError: Throwable? = null
        for (l in loaders) {
            try {
                return Class.forName(resolvedName, false, l)
            } catch (t: Throwable) {
                lastError = t
            }
        }
        // Last resort: the bootstrap loader (needed for `[I` style descriptors).
        try {
            return Class.forName(resolvedName)
        } catch (t: Throwable) {
            lastError = t
        }
        throw ClassNotFound(resolvedName, lastError)
    }

    fun findClassOrNull(name: String, loader: ClassLoader?): Class<*>? =
        try {
            findClass(name, loader)
        } catch (t: Throwable) {
            null
        }

    // ------------------------------------------------------------- members

    /** [Class.getSuperclass] chain, nearest first, never null. */
    fun hierarchy(cls: Class<*>): List<Class<*>> {
        val out = ArrayList<Class<*>>()
        var c: Class<*>? = cls
        var guard = 0
        while (c != null && guard < 64) {
            out.add(c)
            c = try {
                c.superclass
            } catch (t: Throwable) {
                null
            }
            guard++
        }
        return out
    }

    fun fields(cls: Class<*>, includeSuper: Boolean, includeStatic: Boolean): List<Field> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Field>()
        val classes = if (includeSuper) hierarchy(cls) else listOf(cls)
        for (c in classes) {
            val declared: Array<Field> = try {
                c.declaredFields ?: emptyArray()
            } catch (t: Throwable) {
                emptyArray()
            }
            for (f in declared) {
                val isStatic = Modifier.isStatic(f.modifiers)
                if (isStatic && !includeStatic) continue
                if (f.isSynthetic) continue
                val key = c.name + "#" + f.name
                if (!seen.add(key)) continue
                out.add(f)
            }
        }
        out.sortWith(FIELD_ORDER)
        return out
    }

    private val FIELD_ORDER = Comparator<Field> { a, b ->
        val sa = if (Modifier.isStatic(a.modifiers)) 1 else 0
        val sb = if (Modifier.isStatic(b.modifiers)) 1 else 0
        if (sa != sb) return@Comparator sa - sb
        val c = a.name.compareTo(b.name, ignoreCase = true)
        if (c != 0) c else a.declaringClass.name.compareTo(b.declaringClass.name)
    }

    fun methods(cls: Class<*>, includeSuper: Boolean, includeStatic: Boolean): List<Method> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<Method>()
        val classes = if (includeSuper) hierarchy(cls) else listOf(cls)
        for (c in classes) {
            val declared: Array<Method> = try {
                c.declaredMethods ?: emptyArray()
            } catch (t: Throwable) {
                emptyArray()
            }
            for (m in declared) {
                val isStatic = Modifier.isStatic(m.modifiers)
                if (isStatic && !includeStatic) continue
                if (m.isSynthetic || m.isBridge) continue
                val key = signatureOf(m)
                if (!seen.add(key)) continue
                out.add(m)
            }
        }
        out.sortWith(METHOD_ORDER)
        return out
    }

    private val METHOD_ORDER = Comparator<Method> { a, b ->
        val sa = if (Modifier.isStatic(a.modifiers)) 1 else 0
        val sb = if (Modifier.isStatic(b.modifiers)) 1 else 0
        if (sa != sb) return@Comparator sa - sb
        val c = a.name.compareTo(b.name, ignoreCase = true)
        if (c != 0) c
        else a.parameterTypes.size - b.parameterTypes.size
    }

    fun constructors(cls: Class<*>): List<Constructor<*>> {
        val declared: Array<Constructor<*>> = try {
            @Suppress("UNCHECKED_CAST")
            (cls.declaredConstructors as Array<Constructor<*>>)
        } catch (t: Throwable) {
            emptyArray()
        }
        val out = declared.filter { !it.isSynthetic }.toMutableList()
        out.sortWith { a, b -> a.parameterCount - b.parameterCount }
        return out
    }

    // ----------------------------------------------------------- signatures

    fun signatureOf(f: Field): String {
        val mods = Modifier.toString(f.modifiers)
        return (if (mods.isEmpty()) "" else mods + " ") +
            Values.typeLabel(f.type) + " " + f.name
    }

    fun signatureOf(m: Method): String {
        val mods = Modifier.toString(m.modifiers)
        val sb = StringBuilder()
        if (mods.isNotEmpty()) sb.append(mods).append(' ')
        sb.append(Values.typeLabel(m.returnType)).append(' ').append(m.name).append('(')
        appendParams(sb, m.parameterTypes)
        return sb.append(')').toString()
    }

    fun signatureOf(c: Constructor<*>): String {
        val mods = Modifier.toString(c.modifiers)
        val sb = StringBuilder()
        if (mods.isNotEmpty()) sb.append(mods).append(' ')
        sb.append(c.declaringClass.simpleName).append('(')
        appendParams(sb, c.parameterTypes)
        return sb.append(')').toString()
    }

    private fun appendParams(sb: StringBuilder, types: Array<Class<*>>) {
        for (i in types.indices) {
            if (i > 0) sb.append(", ")
            sb.append(Values.typeLabel(types[i]))
        }
    }

    /** `com.foo.Bar doIt(int,java.lang.String)` - the format used by hook rules. */
    fun hookSignature(m: Method): String {
        val sb = StringBuilder()
        sb.append(m.declaringClass.name).append(' ').append(m.name)
        for (t in m.parameterTypes) sb.append(' ').append(t.name)
        return sb.toString()
    }

    // ------------------------------------------------------------ accessors

    fun ensureAccessible(member: java.lang.reflect.AccessibleObject): Boolean = try {
        if (!member.isAccessible) member.isAccessible = true
        true
    } catch (t: Throwable) {
        false
    }

    /** Result of a reflective read; never throws out of the helper. */
    data class ReadResult(val ok: Boolean, val value: Any?, val error: String?)

    fun read(field: Field, obj: Any?): ReadResult {
        if (!ensureAccessible(field)) {
            return ReadResult(false, null, "无法访问该字段（可能被 Android 隐藏 API 策略拦截）")
        }
        return try {
            ReadResult(true, field.get(obj), null)
        } catch (t: Throwable) {
            ReadResult(false, null, t.javaClass.simpleName + ": " + t.message)
        }
    }

    fun write(field: Field, obj: Any?, value: Any?): ReadResult {
        if (!ensureAccessible(field)) {
            return ReadResult(false, null, "无法访问该字段（可能被 Android 隐藏 API 策略拦截）")
        }
        // Final static fields are still attempted: on many ART versions the write
        // succeeds, and a failure is reported as a value instead of an exception.
        return try {
            field.set(obj, value)
            ReadResult(true, field.get(obj), null)
        } catch (t: Throwable) {
            ReadResult(false, null, t.javaClass.simpleName + ": " + t.message)
        }
    }

    fun invoke(method: Method, obj: Any?, args: Array<Any?>): ReadResult {
        if (!ensureAccessible(method)) {
            return ReadResult(false, null, "无法访问该方法（可能被 Android 隐藏 API 策略拦截）")
        }
        return try {
            ReadResult(true, method.invoke(obj, *args), null)
        } catch (t: Throwable) {
            val real = if (t is java.lang.reflect.InvocationTargetException && t.targetException != null) {
                t.targetException
            } else {
                t
            }
            ReadResult(false, null, real.javaClass.name + ": " + real.message)
        }
    }

    fun newInstance(ctor: Constructor<*>, args: Array<Any?>): ReadResult {
        if (!ensureAccessible(ctor)) {
            return ReadResult(false, null, "无法访问该构造函数")
        }
        return try {
            ReadResult(true, ctor.newInstance(*args), null)
        } catch (t: Throwable) {
            val real = if (t is java.lang.reflect.InvocationTargetException && t.targetException != null) {
                t.targetException
            } else {
                t
            }
            ReadResult(false, null, real.javaClass.name + ": " + real.message)
        }
    }

    /** Resolve an overload by parameter type names; null when ambiguous/missing. */
    fun findMethod(
        cls: Class<*>,
        name: String,
        paramTypeNames: List<String>,
        loader: ClassLoader?
    ): Method? {
        val types = ArrayList<Class<*>>(paramTypeNames.size)
        for (n in paramTypeNames) {
            val c = findClassOrNull(n, loader) ?: return null
            types.add(c)
        }
        for (m in methods(cls, includeSuper = true, includeStatic = true)) {
            if (m.name != name) continue
            if (m.parameterTypes.size != types.size) continue
            var match = true
            for (i in types.indices) {
                if (m.parameterTypes[i] != types[i]) {
                    match = false
                    break
                }
            }
            if (match) return m
        }
        // Fall back to an arity match so a slightly wrong type name still works.
        return methods(cls, true, true).firstOrNull {
            it.name == name && it.parameterTypes.size == types.size
        }
    }
}
