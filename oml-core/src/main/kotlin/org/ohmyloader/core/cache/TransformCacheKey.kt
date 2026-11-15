package org.ohmyloader.core.cache

import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * The cache fingerprint: every input that can change transformed bytecode.
 *
 * <p>The rule set is covered by the adapter and mod-jar hashes (the rule classes are their
 * bytecode) plus the game jar and the loader version; the side and the JDK are included because
 * the transformer set and frame handling can differ. Missing required inputs disable the cache
 * rather than risk a stale hit.
 */
internal object TransformCacheKey {

    private const val SCHEMA = "oml-transform-v1"
    private const val BUFFER = 64 * 1024

    fun compute(
        gameJar: File?,
        loaderVersion: String,
        adapterId: String,
        adapterLocation: URL?,
        layerLocations: List<URL>,
        modJars: List<File>,
        side: String,
        jdkFeature: Int,
    ): String? {
        if (gameJar == null || !gameJar.isFile) return null
        if (layerLocations.isEmpty()) return null
        val gameHash = hashFile(gameJar)?.toHex() ?: return null
        val adapterHash = adapterLocation?.let(::hashLocation) ?: "no-location"
        val layerHashes = layerLocations
            .map { hashLocation(it) ?: return null }
            .sorted()
        val modHashes = modJars
            .map { it.canonicalFile }
            .distinctBy { it.path }
            .map { file ->
                if (!file.isFile) return null
                val hash = hashFile(file) ?: return null
                file.name + "=" + hash.toHex()
            }
        // Deliberately not sorted: generated bridge method ids follow rule registration order, so
        // the mod load order is part of the transform input, not just the set of jars.

        val canonical = buildString {
            append(SCHEMA).append('\n')
            append("loader=").append(loaderVersion).append('\n')
            append("adapter=").append(adapterId).append('\n')
            append("adapter-location=").append(adapterHash).append('\n')
            append("game=").append(gameHash).append('\n')
            append("side=").append(side).append('\n')
            append("jdk=").append(jdkFeature).append('\n')
            for (layer in layerHashes) append("layer=").append(layer).append('\n')
            for (mod in modHashes) append("mod=").append(mod).append('\n')
        }
        return sha256(canonical.toByteArray(Charsets.UTF_8)).toHex()
    }

    private fun hashLocation(url: URL): String? {
        val file = runCatching { File(url.toURI()) }.getOrNull() ?: return null
        if (file.isFile) return hashFile(file)?.toHex()
        if (!file.isDirectory) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val classes = file.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .sortedBy { it.relativeTo(file).invariantSeparatorsPath }
            .toList()
        for (entry in classes) {
            val relative = entry.relativeTo(file).invariantSeparatorsPath
            val hash = hashFile(entry) ?: return null
            digest.update(relative.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(hash)
        }
        return digest.digest().toHex()
    }

    private fun hashFile(file: File): ByteArray? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest()
    }.getOrNull()

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
