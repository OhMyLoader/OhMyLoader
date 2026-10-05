package org.ohmyloader.adapter.snapshot

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The behavior subclass is the only place OML overrides game methods for declared content, and a
 * method that stops matching the game's shape is silently dead — no startup verifier covers plain
 * Kotlin overrides, so the mod's hooks would never fire and nothing would say why. These assertions
 * read the real jar with ASM (the game libraries are not on the test classpath, so the classes
 * cannot be loaded) and fail loudly when [OMLBehaviorBlock]'s overrides drift from the game.
 */
class BehaviorBlockShapeTest {

    private val clientJar: File =
        File(System.getProperty("oml.clientJar", "libs/26.4-snapshot-2-client.jar"))

    private fun ourClass(): ClassNode =
        OMLBehaviorBlock::class.java.classLoader
            .getResourceAsStream("org/ohmyloader/adapter/v26_3/OMLBehaviorBlock.class")!!
            .use { stream -> ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, 0) } }

    private fun gameClass(jar: JarFile, internalName: String): ClassNode =
        jar.getInputStream(jar.getJarEntry("$internalName.class")).use { stream ->
            ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, 0) }
        }

    private fun readOur(internalName: String): ClassNode =
        OMLBehaviorBlock::class.java.classLoader.getResourceAsStream(internalName)!!
            .use { stream -> ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, 0) } }

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
            "OMLBehaviorBlock must extend net.minecraft.world.level.block.Block, got ${ourClass().superName}",
        )
    }

    @Test
    fun `the machine block still implements the game EntityBlock shapes`() {
        JarFile(clientJar).use { jar ->
            val entityBlock = gameClass(jar, "net/minecraft/world/level/block/EntityBlock")
            val machine = readOur("org/ohmyloader/adapter/v26_3/OMLBlockEntityBlock.class")
            for (name in listOf("newBlockEntity", "getTicker")) {
                val declared = machine.methods.firstOrNull { it.name == name }
                assertNotNull(declared, "OMLBlockEntityBlock must declare $name")
                val game = entityBlock.methods.firstOrNull { it.name == name }
                assertNotNull(game, "the game's EntityBlock no longer declares $name")
                assertEquals(
                    declared.desc.substringBefore(')'),
                    game.desc.substringBefore(')'),
                    "$name's parameters drifted from the game's EntityBlock: ${declared.desc} vs ${game.desc}"
                )
            }
            val beClass = readOur("org/ohmyloader/adapter/v26_3/OMLMachineBlockEntity.class")
            assertEquals(
                "net/minecraft/world/level/block/entity/BlockEntity",
                beClass.superName,
                "OMLMachineBlockEntity must extend the game's BlockEntity, got ${beClass.superName}",
            )
        }
    }
}
