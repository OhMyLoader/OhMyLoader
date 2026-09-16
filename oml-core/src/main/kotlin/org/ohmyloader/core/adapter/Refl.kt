package org.ohmyloader.core.adapter

import java.lang.reflect.Field

/**
 * Reflection lookup utilities.
 *
 * Adapters cannot depend on game classes (net.minecraft.*) at compile time, so all access to
 * game classes goes through reflection and must use this single shared implementation: it
 * uniformly falls back from public to declared members and always sets accessible, so no
 * call site can silently get a lookup that misses non-public members.
 */
object Refl {

    /** Convenience for a single candidate: looks up an instance field by name and marks it accessible. */
    fun field(target: Any, name: String): Field {
        val f = target.javaClass.getDeclaredField(name)
        f.isAccessible = true
        return f
    }

    /**
     * Tolerant cached field lookup along [type]'s inheritance chain (up to and including
     * [stopAt]): the first declared match is returned accessible, `null` when no class in the
     * chain declares it. For hot paths that probe the same game fields every tick/packet —
     * the (type, name) pair is cached, miss results included, so an absent field costs one
     * failed lookup instead of one per call.
     */
    fun fieldOrNull(type: Class<*>, name: String, stopAt: Class<*>): Field? =
        fields.computeIfAbsent(type to name) { [owner, fieldName] ->
            val found = generateSequence(owner) { it.superclass }
                .takeWhile { stopAt.isAssignableFrom(it) }
                .firstNotNullOfOrNull { cls ->
                    try {
                        cls.getDeclaredField(fieldName).apply { isAccessible = true }
                    } catch (_: NoSuchFieldException) {
                        null
                    }
                }
            java.util.Optional.ofNullable(found)
        }.orElse(null)

    private val fields = java.util.concurrent.ConcurrentHashMap<Pair<Class<*>, String>, java.util.Optional<Field>>()
}
