package formatfa.reflectmaster.reflect

import java.lang.reflect.Array as ReflectArray

/**
 * Text <-> typed value conversion plus human readable rendering.
 *
 * The 1.x parser understood only `int / boolean / long / byte` and silently
 * returned the raw [String] for everything else, which made it impossible to
 * call a method taking a `float`, `double`, `short`, `char`, an array or any
 * boxed type. This replaces it.
 *
 * Accepted syntax for an argument / field value:
 *  - `$v3`        -> object stored in variable slot 3
 *  - `$null`      -> null
 *  - `$this`      -> the object currently being inspected
 *  - `$ctx`       -> the current Context
 *  - `0x1F` / `-0x1F` -> hexadecimal
 *  - `0b1010`     -> binary
 *  - `123` `123L` `1.5f` `1.5d` `1.5`
 *  - `true` / `false` / `1` / `0` for booleans
 *  - `'c'`        -> char
 *  - `[1,2,3]`    -> array / list literal
 *  - anything else -> String (with `\n` `\t` `\\` `\"` unescaped)
 */
object Values {

    private val DOLLAR = '$'

    const val NULL_TOKEN = "null"
    const val THIS_TOKEN = "this"
    const val CTX_TOKEN = "ctx"
    const val SLOT_PREFIX_CHAR = 'v'

    /** True when [text] is a `$...` reference rather than a literal. */
    fun isReference(text: String): Boolean = text.startsWith(DOLLAR)

    fun slotName(index: Int): String = DOLLAR.toString() + SLOT_PREFIX_CHAR + index

    /** Resolve the `$...` family; returns [UNRESOLVED] when it is not a reference. */
    private val UNRESOLVED = Any()

    fun resolveReference(text: String, thisObj: Any?, ctx: Any?): Any? {
        if (!isReference(text)) return UNRESOLVED
        val body = text.substring(1)
        if (body == NULL_TOKEN) return null
        if (body == THIS_TOKEN) return thisObj
        if (body == CTX_TOKEN) return ctx
        if (body.length > 1 && body[0] == SLOT_PREFIX_CHAR) {
            val idx = body.substring(1).toIntOrNull()
            if (idx != null) return Registry.getOrNull(idx)
        }
        return UNRESOLVED
    }

    // ---------------------------------------------------------------- render

    /** One-line summary used in lists. Never throws. */
    fun describe(obj: Any?): String {
        if (obj == null) return NULL_TOKEN
        return try {
            when (obj) {
                is String -> "\"" + obj + "\""
                is Char -> "'" + obj + "'"
                is Class<*> -> "class " + obj.name
                is Boolean, is Int, is Long, is Short, is Byte, is Float, is Double -> obj.toString()
                else -> {
                    if (obj.javaClass.isArray) describeArray(obj)
                    else if (obj is Collection<*>) describeCollection(obj)
                    else if (obj is Map<*, *>) describeMap(obj)
                    else obj.toString() ?: obj.javaClass.name
                }
            }
        } catch (t: Throwable) {
            "<" + obj.javaClass.name + " toString() failed: " + t.javaClass.simpleName + ">"
        }
    }

    private fun describeArray(obj: Any): String {
        val n = ReflectArray.getLength(obj)
        val sb = StringBuilder()
        sb.append(obj.javaClass.componentType?.simpleName ?: "?").append('[').append(n).append("] ")
        val shown = if (n > 8) 8 else n
        sb.append('{')
        for (i in 0 until shown) {
            if (i > 0) sb.append(", ")
            sb.append(describe(ReflectArray.get(obj, i)))
        }
        if (n > shown) sb.append(", ...")
        return sb.append('}').toString()
    }

    private fun describeCollection(c: Collection<*>): String {
        val sb = StringBuilder()
        sb.append(c.javaClass.simpleName).append('(').append(c.size).append(") ")
        var i = 0
        sb.append('{')
        for (e in c) {
            if (i >= 8) {
                sb.append(", ...")
                break
            }
            if (i > 0) sb.append(", ")
            sb.append(describe(e))
            i++
        }
        return sb.append('}').toString()
    }

    private fun describeMap(m: Map<*, *>): String {
        val sb = StringBuilder()
        sb.append(m.javaClass.simpleName).append('(').append(m.size).append(") ")
        var i = 0
        sb.append('{')
        for ((k, v) in m) {
            if (i >= 6) {
                sb.append(", ...")
                break
            }
            if (i > 0) sb.append(", ")
            sb.append(describe(k)).append('=').append(describe(v))
            i++
        }
        return sb.append('}').toString()
    }

    /** Short type label, e.g. `int`, `String`, `int[]`, `List<String>`. */
    fun typeLabel(cls: Class<*>?): String {
        if (cls == null) return "?"
        if (cls.isArray) return typeLabel(cls.componentType) + "[]"
        val n = cls.name
        return when (n) {
            "java.lang.String" -> "String"
            "java.lang.Integer" -> "Integer"
            "java.lang.Boolean" -> "Boolean"
            "java.lang.Long" -> "Long"
            "java.lang.Float" -> "Float"
            "java.lang.Double" -> "Double"
            "java.lang.Short" -> "Short"
            "java.lang.Byte" -> "Byte"
            "java.lang.Character" -> "Character"
            else -> if (cls.simpleName.isEmpty()) n else cls.simpleName
        }
    }

    /**
     * Name to show in signatures / hook parameter lists.
     * [Class.getName] already returns the JVM descriptor for arrays
     * (`[I`, `[Ljava.lang.String;`) and the plain token for primitives, and
     * [Reflect.findClass] accepts both that form and the friendlier `int[]`.
     */
    fun jvmName(cls: Class<*>): String = cls.name

    fun isPrimitive(cls: Class<*>): Boolean =
        cls.isPrimitive || PRIMITIVES.contains(cls.name) || cls === String::class.java

    /** True when the type can be edited as plain text. */
    fun isTextEditable(cls: Class<*>): Boolean = when (cls.name) {
        "int", "java.lang.Integer",
        "long", "java.lang.Long",
        "short", "java.lang.Short",
        "byte", "java.lang.Byte",
        "char", "java.lang.Character",
        "boolean", "java.lang.Boolean",
        "float", "java.lang.Float",
        "double", "java.lang.Double",
        "java.lang.String" -> true
        else -> false
    }

    private val PRIMITIVES = setOf(
        "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
        "java.lang.Character", "java.lang.Boolean", "java.lang.Float", "java.lang.Double"
    )

    // ----------------------------------------------------------------- parse

    class ParseException(message: String) : Exception(message)

    /**
     * Convert user text into an instance assignable to [target].
     * @throws ParseException when the text cannot represent [target].
     */
    fun parse(text: String, target: Class<*>, thisObj: Any?, ctx: Any?): Any? {
        val trimmed = text.trim()

        val ref = resolveReference(trimmed, thisObj, ctx)
        if (ref !== UNRESOLVED) {
            if (ref == null) {
                if (target.isPrimitive) throw ParseException("基本类型不能为 null: " + target.name)
                return null
            }
            return coerce(ref, target)
        }

        if (trimmed == NULL_TOKEN || trimmed.isEmpty()) {
            if (trimmed.isEmpty() && target == String::class.java) return ""
            if (target.isPrimitive) throw ParseException("基本类型不能为空: " + target.name)
            return null
        }

        return when (target.name) {
            "boolean", "java.lang.Boolean" -> parseBoolean(trimmed)
            "int", "java.lang.Integer" -> parseInt(trimmed).toInt()
            "long", "java.lang.Long" -> parseInt(trimmed)
            "short", "java.lang.Short" -> parseInt(trimmed).toShort()
            "byte", "java.lang.Byte" -> parseInt(trimmed).toByte()
            "char", "java.lang.Character" -> parseChar(trimmed)
            "float", "java.lang.Float" -> parseDouble(trimmed).toFloat()
            "double", "java.lang.Double" -> parseDouble(trimmed)
            "java.lang.String" -> unescape(trimmed)
            "java.lang.CharSequence" -> unescape(trimmed)
            "java.lang.Object" -> guess(trimmed)
            else -> {
                if (target.isArray) parseArray(trimmed, target)
                else if (target.isEnum) parseEnum(trimmed, target)
                else if (CharSequence::class.java.isAssignableFrom(target)) unescape(trimmed)
                else if (Number::class.java.isAssignableFrom(target)) guess(trimmed)
                else throw ParseException(
                    "无法从文本构造 " + target.name + "，请用变量槽（先浏览到该对象再存入 \$v0）"
                )
            }
        }
    }

    private fun parseBoolean(s: String): Boolean {
        val t = s.toLowerCase(java.util.Locale.US)
        return when (t) {
            "true", "1", "yes", "y", "on" -> true
            "false", "0", "no", "n", "off" -> false
            else -> throw ParseException("不是布尔值: " + s)
        }
    }

    private fun parseInt(s: String): Long {
        var t = s
        var negative = false
        if (t.startsWith("-")) {
            negative = true
            t = t.substring(1)
        } else if (t.startsWith("+")) {
            t = t.substring(1)
        }
        val value: Long = when {
            t.startsWith("0x") || t.startsWith("0X") ->
                java.lang.Long.parseLong(t.substring(2), 16)
            t.startsWith("0b") || t.startsWith("0B") ->
                java.lang.Long.parseLong(t.substring(2), 2)
            t.endsWith("L") || t.endsWith("l") ->
                t.substring(0, t.length - 1).toLong()
            else -> t.toLong()
        }
        return if (negative) -value else value
    }

    private fun parseDouble(s: String): Double {
        var t = s
        if (t.endsWith("f") || t.endsWith("F") || t.endsWith("d") || t.endsWith("D")) {
            t = t.substring(0, t.length - 1)
        }
        if (t.startsWith("0x") || t.startsWith("0X")) return parseInt(t).toDouble()
        return t.toDoubleOrNull() ?: throw ParseException("不是数字: " + s)
    }

    private fun parseChar(s: String): Char {
        val body = if (s.length >= 2 && s[0] == '\'' && s[s.length - 1] == '\'') {
            s.substring(1, s.length - 1)
        } else {
            s
        }
        val un = unescape(body)
        if (un.length != 1) throw ParseException("字符必须只有一个字符: " + s)
        return un[0]
    }

    private fun parseEnum(s: String, target: Class<*>): Any {
        val constants = target.enumConstants ?: throw ParseException("不是枚举: " + target.name)
        for (c in constants) {
            if ((c as Enum<*>).name == s) return c
        }
        // Be forgiving: allow the ordinal too.
        val idx = s.toIntOrNull()
        if (idx != null && idx in constants.indices) return constants[idx]
        throw ParseException("没有枚举常量 " + s + "，可选: " + constants.joinToString { (it as Enum<*>).name })
    }

    private fun parseArray(text: String, target: Class<*>): Any {
        val t = text.trim()
        if (!t.startsWith("[") || !t.endsWith("]")) {
            throw ParseException("数组需要形如 [1,2,3]")
        }
        val component = target.componentType ?: throw ParseException("不是数组类型")
        val body = t.substring(1, t.length - 1).trim()
        val parts = if (body.isEmpty()) emptyList() else splitTopLevel(body)
        val out = ReflectArray.newInstance(component, parts.size)
        for (i in parts.indices) {
            ReflectArray.set(out, i, parse(parts[i], component, null, null))
        }
        return out
    }

    /** Split on commas that are not nested inside [] or quotes. */
    private fun splitTopLevel(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var inQuote = false
        val cur = StringBuilder()
        for (ch in body) {
            when {
                ch == '"' -> {
                    inQuote = !inQuote
                    cur.append(ch)
                }
                inQuote -> cur.append(ch)
                ch == '[' -> {
                    depth++
                    cur.append(ch)
                }
                ch == ']' -> {
                    depth--
                    cur.append(ch)
                }
                ch == ',' && depth == 0 -> {
                    out.add(cur.toString().trim())
                    cur.setLength(0)
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out.add(cur.toString().trim())
        return out
    }

    /** Best effort literal for `Object` parameters. */
    private fun guess(s: String): Any? {
        if (s == NULL_TOKEN) return null
        val lower = s.toLowerCase(java.util.Locale.US)
        if (lower == "true" || lower == "false") return lower == "true"
        if (s.startsWith("[") && s.endsWith("]")) {
            val parts = splitTopLevel(s.substring(1, s.length - 1).trim())
            val out = ArrayList<Any?>()
            for (p in parts) out.add(if (p.isEmpty()) null else guess(p))
            return out
        }
        val asLong = try {
            parseInt(s)
        } catch (e: Exception) {
            null
        }
        if (asLong != null) {
            return if (asLong in Int.MIN_VALUE..Int.MAX_VALUE) asLong.toInt() else asLong
        }
        val asDouble = s.toDoubleOrNull()
        if (asDouble != null) return asDouble
        return unescape(s)
    }

    /** Turn `\n`, `\t`, `\\`, `\"`, `\uXXXX` into real characters. */
    fun unescape(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i == s.length - 1) {
                sb.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                '0' -> sb.append('\u0000')
                '\\' -> sb.append('\\')
                '"' -> sb.append('"')
                '\'' -> sb.append('\'')
                'u' -> {
                    if (i + 5 < s.length) {
                        val hex = s.substring(i + 2, i + 6)
                        val v = hex.toIntOrNull(16)
                        if (v != null) {
                            sb.append(v.toChar())
                            i += 6
                            continue
                        }
                    }
                    sb.append(n)
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }

    /** Inverse of [unescape], used to prefill editors. */
    fun escape(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** Widen/narrow an already resolved object so it fits [target]. */
    fun coerce(value: Any, target: Class<*>): Any {
        if (target.isInstance(value)) return value
        if (value !is Number) {
            throw ParseException(value.javaClass.name + " 不能赋给 " + target.name)
        }
        return when (target.name) {
            "int", "java.lang.Integer" -> value.toInt()
            "long", "java.lang.Long" -> value.toLong()
            "short", "java.lang.Short" -> value.toShort()
            "byte", "java.lang.Byte" -> value.toByte()
            "float", "java.lang.Float" -> value.toFloat()
            "double", "java.lang.Double" -> value.toDouble()
            "char", "java.lang.Character" -> value.toInt().toChar()
            else -> throw ParseException(value.javaClass.name + " 不能赋给 " + target.name)
        }
    }
}
