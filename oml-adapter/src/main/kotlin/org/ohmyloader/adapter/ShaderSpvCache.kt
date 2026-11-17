package org.ohmyloader.adapter

import com.mojang.renderpearl.api.pipeline.ShaderType
import net.minecraft.client.renderer.ShaderDefines
import org.lwjgl.system.MemoryUtil
import org.ohmyloader.api.core.hook.Operation
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The session side of the Supervisor's [SpirvCache] (pure `String -> byte[]`, no game types there).
 *
 * <p>`GlslCompiler.compileToSpv` is where shaderc turns the assembled source into SPIR-V — a
 * hundreds-of-milliseconds native compile per pipeline, about two hundred of them per resource
 * reload. The bytes are a pure function of the inputs, so the second session can hand back the
 * first session's output instead of compiling again.
 *
 * <p>Key correctness: the inputs of the compile are the shader location, the type, the entry point,
 * the defines, the fully expanded source and the device info. The top-level source string plus
 * **every** include text the reload produced are hashed, so a resource-pack change to any included
 * file misses instead of returning stale bytes; expansion is a pure function of those two inputs, so
 * equal digests imply a byte-identical expanded source. Compiler options (`isZeroToOne`,
 * shaderDrawParameters) are fixed for the process by the device and cannot change between the
 * sessions of one reboot, so they do not enter the key.
 */
object ShaderSpvCache {

    /** `#version` model and the entry point `PipelineBuilder` hard-codes for both stages. */
    private const val ENTRY = "main"

    /** Every include text the current reload produced, keyed by resource id. */
    private val includeTexts = ConcurrentHashMap<String, String>()

    @Volatile
    private var includeDigest: String? = null

    /**
     * Records one include as the shader source loader read it. Injected at
     * `ShaderSource$CachedIncludeSource.create`; the loader reads the whole include set before any
     * pipeline compiles, so the digest below is stable by the time the first key is needed.
     */
    @JvmStatic
    fun noteInclude(id: Any?, text: String?) {
        if (id == null || text == null) return
        if (includeTexts.put(id.toString(), text) != text) includeDigest = null
    }

    /**
     * The compile hook: answer from the Supervisor cache when the key was seen before, otherwise
     * compile and remember the bytes. Injected as a `@WrapMethod` handler, so the original call is
     * still the only path that ever invokes shaderc.
     *
     * <p>The compile returns raw SPIR-V bytes; a cache hit allocates a fresh native buffer because
     * the caller owns and frees whatever it receives.
     */
    @JvmStatic
    fun compileToSpv(
        location: String,
        source: String,
        type: Any?,
        defines: Any?,
        shaderSource: Any?,
        deviceInfo: Any?,
        original: Operation<*>,
        id: Int,
    ): ByteBuffer {
        val shaderType = type as? ShaderType
        val key = if (shaderType == null) {
            null
        } else {
            runCatching { key(location, source, shaderType, defines) }.getOrNull()
        }
        if (key != null) {
            val cached = runCatching { cacheGet()?.invoke(null, key) as? ByteArray }.getOrNull()
            if (cached != null) return nativeCopy(cached)
        }
        val spv = original.call(location, source, type, defines, shaderSource, deviceInfo) as ByteBuffer
        if (key != null) {
            runCatching {
                val duplicate = spv.duplicate()
                val bytes = ByteArray(duplicate.remaining())
                duplicate.get(bytes)
                cachePut()?.invoke(null, key, bytes)
            }
        }
        return spv
    }

    private fun key(location: String, source: String, type: ShaderType, defines: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(location.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(type.name.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(ENTRY.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update((defines as? ShaderDefines)?.asSourceDirectives()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0))
        digest.update(0)
        digest.update(source.toByteArray(Charsets.UTF_8))
        digest.update(0)
        digest.update(corpusDigest().toByteArray(Charsets.UTF_8))
        return hex(digest.digest())
    }

    /**
     * SHA-256 over every include the reload produced, sorted by id. Hashing the whole corpus (not
     * just the ones a shader happens to include) is deliberately conservative: an unused include
     * edit only costs a miss, never a stale hit.
     */
    private fun corpusDigest(): String {
        includeDigest?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        for (id in includeTexts.keys.sorted()) {
            digest.update(id.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update((includeTexts[id] ?: "").toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return hex(digest.digest()).also { includeDigest = it }
    }

    /** The caller consumes (and may free) its buffer, so a cache hit ships a fresh native copy. */
    private fun nativeCopy(bytes: ByteArray): ByteBuffer =
        MemoryUtil.memAlloc(bytes.size).apply {
            put(bytes)
            position(0)
        }

    private fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    // The Supervisor owns the cache; reflection keeps this class free of any launcher reference.
    private val cacheLookup: Array<java.lang.reflect.Method?> by lazy {
        runCatching {
            val cache = Class.forName("org.ohmyloader.launcher.SpirvCache", true, ShaderSpvCache::class.java.classLoader)
            arrayOf(
                cache.getMethod("get", String::class.java),
                cache.getMethod("put", String::class.java, ByteArray::class.java),
            )
        }.getOrDefault(arrayOfNulls(2))
    }

    private fun cacheGet(): java.lang.reflect.Method? = cacheLookup[0]

    private fun cachePut(): java.lang.reflect.Method? = cacheLookup[1]
}
