package org.ohmyloader.adapter.v26_3

import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import kotlin.test.assertEquals

/**
 * The behavior subclass is the only place OML overrides game methods for declared content, and a
 * method that stops matching the game's shape is silently dead — no startup verifier covers plain
 * Kotlin overrides, so the mod's hooks would never fire and nothing would say why. These assertions
 * read the real jar with ASM (the game libraries are not on the test classpath, so the classes
 * cannot be loaded) and fail loudly when [OMLBehaviorBlock]'s overrides drift from the game.
 */
class BehaviorBlockShapeTest {

    private val clientJar: File =
        File(System.getProperty("oml.clientJar", "libs/26.3-client.jar"))

    private fun ourClass(): ClassNode =
        OMLBehaviorBlock::class.java.classLoader
            .getResourceAsStream("org/ohmyloader/adapter/v26_3/OMLBehaviorBlock.class")!!
            .use { stream -> ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, 0) } }

    private fun gameClass(jar: JarFile, internalName: String): ClassNode =
        jar.getInputStream(jar.getJarEntry("$internalName.class")).use { stream ->
            ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, 0) }
        }

    /** The override must keep the exact name+descriptor the game class still declares. */
    private fun assertRidesGameMethod(our: ClassNode, gameOwner: ClassNode, name: String) {
        val override = our.methods.firstOrNull { it.name == name && it.desc.startsWith("(") }
        assertNotNull(override, "OMLBehaviorBlock must declare $name")
        assertTrue(
            gameOwner.methods.any { it.name == override.name && it.desc == override.desc },
            "$name ${override.desc} no longer exists in the game's ${gameOwner.name} — " +
                "the override is dead code and mod behavior hooks would never fire",
        )
    }

    @Test
    fun `stepOn override rides the real game method`() {
        JarFile(clientJar).use { jar ->
            assertRidesGameMethod(ourClass(), gameClass(jar, "net/minecraft/world/level/block/Block"), "stepOn")
        }
    }

    @Test
    fun `attack override rides the real game method`() {
        JarFile(clientJar).use { jar ->
            assertRidesGameMethod(
                ourClass(),
                gameClass(jar, "net/minecraft/world/level/block/state/BlockBehaviour"),
                "attack",
            )
        }
    }

    @Test
    fun `the subclass still extends the game Block`() {
        assertEquals(
            "net/minecraft/world/level/block/Block",
            ourClass().superName,
            "OMLBehaviorBlock must extend net.minecraft.world.level.block.Block, got ${ourClass().superName}"
        )
    }
}
