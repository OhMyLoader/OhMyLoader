package org.ohmyloader.adapter.common

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.core.transformer.TransformContext
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The one creative-tab constraint that can crash the game without any hook drifting: OML registers
 * its shared tab at [OMLCreativeTabs.TAB_ROW]/[OMLCreativeTabs.TAB_COLUMN], and
 * `CreativeModeTabs.validate()` throws on any duplicate (row, column) — a bootstrap crash. This
 * walks the (row, column) pairs vanilla's own `bootstrap` feeds to `CreativeModeTab.builder` in
 * the real jar and fails the build the day that slot is taken.
 */
class CreativeTabPositionTest {

    private companion object {
        const val SPRITE_ARRAY = "net/minecraft/resources/Identifier"
        const val OML_CREATIVE_TABS = "org/ohmyloader/adapter/common/OMLCreativeTabs"
    }

    private val clientJar: File = File(System.getProperty("oml.clientJar", "libs/26.3-client.jar"))

    @Test
    fun `vanilla occupies no creative tab slot where OML registers its own`() {
        JarFile(clientJar).use { jar ->
            val entry = jar.getJarEntry("net/minecraft/world/item/CreativeModeTabs.class")
                ?: fail("CreativeModeTabs missing from the client jar")
            val node = ClassNode()
            jar.getInputStream(entry).use { ClassReader(it.readAllBytes()).accept(node, 0) }
            val bootstrap = node.methods.firstOrNull { it.name == "bootstrap" }
                ?: fail("CreativeModeTabs.bootstrap missing from the client jar")

            val occupied = mutableSetOf<Pair<Int, Int>>() // row ordinal to column
            var prev: AbstractInsnNode? = null
            var prevPrev: AbstractInsnNode? = null
            for (insn in bootstrap.instructions) {
                if (insn is MethodInsnNode && insn.owner == "net/minecraft/world/item/CreativeModeTab" &&
                    insn.name == "builder"
                ) {
                    // Stack shape at the call: [Row getstatic][column int push][builder].
                    val row = prevPrev as? FieldInsnNode
                    val column = intPushed(prev)
                    if (row == null || column == null) {
                        fail("builder call with unrecognized operand shape near ${insn.name}${insn.desc}")
                    }
                    occupied += rowOrdinal(row.name) to column
                }
                prevPrev = prev
                prev = insn
            }
            assertTrue(
                occupied.isNotEmpty(),
                "bootstrap declares no tabs — the operand walk is broken, not the slot",
            )
            val omlOrdinal = OMLCreativeTabs.TAB_ROW.ordinal
            val clash = occupied.contains(omlOrdinal to OMLCreativeTabs.TAB_COLUMN)
            assertTrue(
                !clash,
                "vanilla now occupies the (row=$omlOrdinal, column=${OMLCreativeTabs.TAB_COLUMN}) slot " +
                    "OMLCreativeTabs registers in — CreativeModeTabs.validate() would crash the game; move the tab",
            )
        }
    }

    /**
     * The other half of the slot's story: the screen's tab-button sprites hold one entry per vanilla
     * column, so [OMLCreativeTabs.TAB_COLUMN] is out of their range by construction, and the only thing
     * standing between a mod tab and an `ArrayIndexOutOfBoundsException` in "Rendering screen" is the
     * bound rule. This applies the live rules to the real class and asks that the handler sit on the
     * value the vanilla code feeds to `Mth.clamp`.
     */
    @Test
    fun `the creative screen clamps a mod tab onto a sprite it owns`() {
        val target = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen"
        JarFile(clientJar).use { jar ->
            val entry = jar.getJarEntry("$target.class") ?: fail("$target missing from the client jar")
            val node = ClassNode()
            jar.getInputStream(entry).use { ClassReader(it.readAllBytes()).accept(node, 0) }

            val clinit = instructionsOf(node.methods.first { it.name == "<clinit>" })
            // `bipush <n>; anewarray Identifier` — anewarray carries the element type, and the length is
            // the push ahead of it.
            val spriteWidths: List<Int> = clinit.mapIndexedNotNull { index, insn ->
                if (insn is TypeInsnNode && insn.desc == SPRITE_ARRAY) {
                    (clinit.getOrNull(index - 1) as? IntInsnNode)?.operand
                } else {
                    null
                }
            }
            assertTrue(spriteWidths.isNotEmpty(), "no tab sprite array is built in <clinit> — the read is off")
            assertTrue(
                OMLCreativeTabs.TAB_COLUMN > spriteWidths.min() - 1,
                "column ${OMLCreativeTabs.TAB_COLUMN} now fits the ${spriteWidths.min()}-entry tab sprite " +
                    "arrays, so the bound rule is no longer what keeps the creative screen alive — recheck both",
            )

            MinecraftHookTransformer(idPrefix = "test", shapes = testHookShapes).transform(TransformContext(target, node))
            val button = node.methods.first { it.name == "extractTabButton" }
            val insns = instructionsOf(button)
            val clampAt = insns.indexOfFirst {
                it is MethodInsnNode && it.owner == "net/minecraft/util/Mth" && it.name == "clamp"
            }
            assertTrue(
                clampAt > 0,
                "extractTabButton no longer clamps a column into the sprite array — the vanilla lookup " +
                    "moved, so this rule anchors on nothing",
            )
            val handlerAt = insns.take(clampAt).indexOfLast {
                it is MethodInsnNode && it.owner == OML_CREATIVE_TABS && it.name == "spriteBound"
            }
            assertTrue(
                handlerAt >= 0,
                "nothing narrows the clamp bound before Mth.clamp: a column past vanilla's sprites indexes " +
                    "one past the array and the creative screen crashes while rendering",
            )
        }
    }

    private fun instructionsOf(method: MethodNode): List<AbstractInsnNode> =
        List(method.instructions.size()) { method.instructions[it] }

    private fun intPushed(insn: AbstractInsnNode?): Int? = when (insn) {
        is InsnNode -> when (insn.opcode) {
            Opcodes.ICONST_M1 -> -1
            Opcodes.ICONST_0 -> 0
            Opcodes.ICONST_1 -> 1
            Opcodes.ICONST_2 -> 2
            Opcodes.ICONST_3 -> 3
            Opcodes.ICONST_4 -> 4
            Opcodes.ICONST_5 -> 5
            else -> null
        }

        is IntInsnNode -> if (insn.opcode == Opcodes.BIPUSH || insn.opcode == Opcodes.SIPUSH) insn.operand else null
        is LdcInsnNode -> insn.cst as? Int
        else -> null
    }

    private fun rowOrdinal(name: String): Int = when (name) {
        "TOP" -> 0
        "BOTTOM" -> 1
        else -> fail("unknown CreativeModeTab.Row constant: $name")
    }
}
