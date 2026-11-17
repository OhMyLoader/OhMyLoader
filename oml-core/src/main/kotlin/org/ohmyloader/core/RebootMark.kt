package org.ohmyloader.core

/**
 * Session-side bridge into the supervisor loader's reboot stopwatch
 * ([org.ohmyloader.launcher.RebootClock], reached through parent delegation the same way
 * [SessionTeardown] reaches `ThreadHygiene`).
 *
 * Measurement only: every call is a runCatching no-op on failure, so an instrumentation gap can
 * never change what the loader does.
 */
object RebootMark {

    private fun clock(): Class<*>? = runCatching {
        Class.forName("org.ohmyloader.launcher.RebootClock", true, RebootMark::class.java.classLoader)
    }.getOrNull()

    fun mark(name: String) {
        runCatching { clock()?.getMethod("mark", String::class.java)?.invoke(null, name) }
    }

    fun markLatest(name: String) {
        runCatching { clock()?.getMethod("markLatest", String::class.java)?.invoke(null, name) }
    }

    fun add(key: String, nanos: Long) {
        runCatching { clock()?.getMethod("add", String::class.java, Long::class.javaPrimitiveType)?.invoke(null, key, nanos) }
    }

    fun count(key: String) {
        runCatching { clock()?.getMethod("count", String::class.java)?.invoke(null, key) }
    }
}
