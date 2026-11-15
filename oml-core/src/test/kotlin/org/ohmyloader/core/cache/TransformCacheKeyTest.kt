package org.ohmyloader.core.cache

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The fingerprint must be stable, order-independent and change with every transform input. */
class TransformCacheKeyTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `the fingerprint is deterministic and order-sensitive`() {
        val game = file("game.jar", "game")
        val adapter = file("adapter.jar", "adapter")
        val a = file("a.jar", "a")
        val b = file("b.jar", "b")

        val first = compute(game, adapter, listOf(a, b))
        val reordered = compute(game, adapter, listOf(b, a))

        assertNotNull(first)
        assertEquals(first, compute(game, adapter, listOf(a, b)))
        assertNotEquals(first, reordered, "the mod load order changes generated bridge ids and must invalidate the cache")
        assertTrue(first.matches(Regex("[0-9a-f]{64}")), "the namespace is a SHA-256 hex digest")
    }

    @Test
    fun `every transform input changes the fingerprint`() {
        val game = file("game.jar", "game")
        val adapter = file("adapter.jar", "adapter")
        val a = file("a.jar", "a")
        val b = file("b.jar", "b")
        val base = compute(game, adapter, listOf(a, b))

        assertNotEquals(base, compute(file("game2.jar", "game2"), adapter, listOf(a, b)))
        assertNotEquals(base, compute(game, file("adapter2.jar", "adapter2"), listOf(a, b)))
        assertNotEquals(base, compute(game, adapter, listOf(a, file("b2.jar", "b2"))))
        assertNotEquals(base, compute(game, adapter, listOf(a, b), side = "server"))
        assertNotEquals(base, compute(game, adapter, listOf(a, b), jdk = 26))
        assertNotEquals(base, compute(game, adapter, listOf(a, b), loaderVersion = "2.0"))
        assertNotEquals(base, compute(game, adapter, listOf(a, b), adapterId = "other"))
    }

    @Test
    fun `a missing required input disables the cache`() {
        val game = file("game.jar", "game")
        val adapter = file("adapter.jar", "adapter")
        val mod = file("a.jar", "a")

        assertNull(TransformCacheKey.compute(null, "1.0", "26_3", adapter.toURI().toURL(), listOf(adapter.toURI().toURL()), listOf(mod), "client", 27))
        assertNull(TransformCacheKey.compute(File(dir.toFile(), "missing.jar"), "1.0", "26_3", adapter.toURI().toURL(), listOf(adapter.toURI().toURL()), listOf(mod), "client", 27))
        assertNull(TransformCacheKey.compute(game, "1.0", "26_3", null, emptyList(), listOf(mod), "client", 27))
        assertNull(TransformCacheKey.compute(game, "1.0", "26_3", adapter.toURI().toURL(), listOf(File(dir.toFile(), "missing-layer.jar").toURI().toURL()), listOf(mod), "client", 27))
        assertNull(TransformCacheKey.compute(game, "1.0", "26_3", adapter.toURI().toURL(), listOf(adapter.toURI().toURL()), listOf(File(dir.toFile(), "missing.jar")), "client", 27))
        assertNotNull(TransformCacheKey.compute(game, "1.0", "26_3", null, listOf(adapter.toURI().toURL()), listOf(mod), "client", 27))
    }

    @Test
    fun `an adapter class directory is hashed by its class files`() {
        val game = file("game.jar", "game")
        val adapter = dir.resolve("adapter").toFile().apply { mkdirs() }
        val probe = File(adapter, "com/example/Adapter.class").apply {
            parentFile.mkdirs()
            writeText("first")
        }
        val mod = file("a.jar", "a")

        val first = TransformCacheKey.compute(game, "1.0", "26_3", adapter.toURI().toURL(), listOf(adapter.toURI().toURL()), listOf(mod), "client", 27)
        probe.writeText("second")
        val changed = TransformCacheKey.compute(game, "1.0", "26_3", adapter.toURI().toURL(), listOf(adapter.toURI().toURL()), listOf(mod), "client", 27)

        assertNotNull(first)
        assertNotEquals(first, changed)
    }

    private fun compute(
        game: File,
        adapter: File,
        mods: List<File>,
        side: String = "client",
        jdk: Int = 27,
        loaderVersion: String = "1.0",
        adapterId: String = "26_3",
    ): String? = TransformCacheKey.compute(
        gameJar = game,
        loaderVersion = loaderVersion,
        adapterId = adapterId,
        adapterLocation = adapter.toURI().toURL(),
        layerLocations = listOf(adapter.toURI().toURL()),
        modJars = mods,
        side = side,
        jdkFeature = jdk,
    )

    private fun file(name: String, content: String): File =
        dir.resolve(name).toFile().apply { writeText(content) }
}
