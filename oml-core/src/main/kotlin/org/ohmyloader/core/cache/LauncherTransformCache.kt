package org.ohmyloader.core.cache

import java.lang.reflect.Method

/**
 * The session-side view of the supervisor's transform cache. The supervisor is the owner of L1
 * (it survives a session swap), so the bridge is reflection: the launcher class is parent-first,
 * and no class-identity edge is created between the two layers.
 */
internal class LauncherTransformCache private constructor(
    private val getter: Method,
    private val putter: Method,
) : TransformCache {

    override fun get(namespace: String, internalName: String): ByteArray? =
        runCatching { getter.invoke(null, namespace, internalName) as ByteArray? }.getOrNull()

    override fun put(namespace: String, internalName: String, bytes: ByteArray) {
        try {
            putter.invoke(null, namespace, internalName, bytes)
        } catch (_: Throwable) {
            // The cache is best-effort; the classloader already has its bytes.
        }
    }

    companion object {
        /** Null when the launcher layer does not ship the cache (unit tests, an old shell). */
        fun create(loader: ClassLoader): TransformCache? = runCatching {
            val type = Class.forName("org.ohmyloader.launcher.TransformCache", true, loader)
            LauncherTransformCache(
                type.getMethod("get", String::class.java, String::class.java),
                type.getMethod("put", String::class.java, String::class.java, ByteArray::class.java),
            )
        }.getOrNull()
    }
}
