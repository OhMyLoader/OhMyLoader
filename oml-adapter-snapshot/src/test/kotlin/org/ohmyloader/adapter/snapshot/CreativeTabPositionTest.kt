package org.ohmyloader.adapter.snapshot

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
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

    private val clientJar: File = File(System.getProperty("oml.clientJar", "libs/26.4-snapshot-2-client.jar"))

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
