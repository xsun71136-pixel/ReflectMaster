package formatfa.reflectmaster.reflect

import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Variable slots (`$v0` .. `$v[capacity-1]`).
 *
 * Holds strong references on purpose: the entire point is to keep an object
 * discovered in one screen alive so a later method call can use it. The 1.x
 * version used a plain [ArrayList] that was mutated while being iterated
 * (`for (Object o : objects) if (o == null) objects.remove(o)`) which threw
 * [java.util.ConcurrentModificationException] at random, and hard capped at 50
 * entries without telling the user.
 *
 * This version is a fixed size atomic array: thread safe, no reallocation, and
 * a slot keeps its identity (an object stored in `$v3` stays in `$v3`).
 */
object Registry {

    const val CAPACITY = 32

    private val slots = AtomicReferenceArray<Any?>(CAPACITY)
    private val notes = AtomicReferenceArray<String?>(CAPACITY)

    data class Entry(val index: Int, val value: Any?, val note: String?) {
        val label: String
            get() {
                val type = if (value == null) "null" else value.javaClass.name
                val n = note
                return Values.slotName(index) + "  " + type + if (n.isNullOrEmpty()) "" else "  #" + n
            }
    }

    fun getOrNull(index: Int): Any? =
        if (index in 0 until CAPACITY) slots.get(index) else null

    fun noteOf(index: Int): String? =
        if (index in 0 until CAPACITY) notes.get(index) else null

    fun set(index: Int, value: Any?, note: String? = null) {
        if (index !in 0 until CAPACITY) return
        slots.set(index, value)
        notes.set(index, note)
    }

    fun clearIndex(index: Int) {
        if (index !in 0 until CAPACITY) return
        slots.set(index, null)
        notes.set(index, null)
    }

    fun clear() {
        for (i in 0 until CAPACITY) clearIndex(i)
    }

    /** Store into the first free slot; returns the slot index or -1 when full. */
    fun store(value: Any?, note: String? = null): Int {
        // Reuse an existing identical reference so repeated clicks do not eat slots.
        if (value != null) {
            for (i in 0 until CAPACITY) {
                if (slots.get(i) === value) {
                    if (note != null) notes.set(i, note)
                    return i
                }
            }
        }
        for (i in 0 until CAPACITY) {
            if (slots.get(i) == null) {
                slots.set(i, value)
                notes.set(i, note)
                return i
            }
        }
        return -1
    }

    fun entries(): List<Entry> {
        val out = ArrayList<Entry>()
        for (i in 0 until CAPACITY) {
            val v = slots.get(i)
            val n = notes.get(i)
            if (v != null || n != null) out.add(Entry(i, v, n))
        }
        return out
    }

    fun count(): Int {
        var n = 0
        for (i in 0 until CAPACITY) if (slots.get(i) != null) n++
        return n
    }
}
