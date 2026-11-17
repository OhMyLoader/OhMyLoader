package org.ohmyloader.adapter

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
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

    private val clientJar: File = File(System.getProperty("oml.clientJar", "libs/26.4-snapshot-9-client.jar"))

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
     * The other half of the strip's story: the takeover reaches into the screen's two
     * `TabNavigationBar`s, their `tabButtons` list and each `ItemTabButton`'s `Tab` adapter, and it
     * assumes the button is 26 px wide with a 1 px gap (the 27 px column pitch). If any of that
     * moves, the feature would silently leave vanilla's own strip on screen; the build fails here
     * instead.
     */
    @Test
    fun `the tab strip the layout takes over still has the shape it reflects`() {
        val screenType = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen"
        JarFile(clientJar).use { jar ->
            val screen = classNode(jar, screenType)
            for (name in listOf("tabBarTop", "tabBarBottom")) {
                val field = screen.fields.firstOrNull { it.name == name }
                    ?: fail("$screenType no longer declares $name — the strip layout reflects it")
                assertTrue(
                    field.desc == "Lnet/minecraft/client/gui/components/tabs/TabNavigationBar;",
                    "$name is ${field.desc}, not the TabNavigationBar the layout walks",
                )
            }

            val bar = classNode(jar, "net/minecraft/client/gui/components/tabs/TabNavigationBar")
            val buttons = bar.fields.firstOrNull { it.name == "tabButtons" }
                ?: fail("TabNavigationBar no longer declares tabButtons — the strip layout reflects it")
            assertTrue(
                buttons.desc == "Lcom/google/common/collect/ImmutableList;",
                "tabButtons is ${buttons.desc}, not the ImmutableList the layout walks",
            )

            val button = classNode(jar, "net/minecraft/client/gui/components/TabButton")
            button.methods.firstOrNull {
                it.name == "tab" && it.desc == "()Lnet/minecraft/client/gui/components/tabs/Tab;"
            } ?: fail("TabButton.tab() is gone — the layout cannot map a button back to its tab")

            val item = classNode(jar, "net/minecraft/client/gui/components/ItemTabButton")
            assertTrue(
                item.superName == "net/minecraft/client/gui/components/TabButton",
                "ItemTabButton no longer extends TabButton — the bars' children are not the buttons assumed",
            )

            val adapterType = "$screenType\$CreativeModeTabAdapter"
            classNode(jar, adapterType).methods.firstOrNull {
                it.name == "tab" && it.desc == "()Lnet/minecraft/world/item/CreativeModeTab;"
            } ?: fail("$adapterType.tab() is gone — the layout cannot read the tab behind a button")

            // 26 px buttons with the bar's 1 px spacing are what the 27 px column pitch encodes.
            val insns = instructionsOf(screen.methods.first { it.name == "init" })
            val sizeAt = insns.indexOfFirst {
                it is MethodInsnNode && it.owner == "net/minecraft/client/gui/components/ItemTabButton\$Builder" &&
                    it.name == "size"
            }
            assertTrue(sizeAt > 1, "init no longer sizes ItemTabButton — the 27 px column pitch is unverified")
            assertTrue(
                pushedBefore(insns, sizeAt, 2) == 26 && pushedBefore(insns, sizeAt, 1) == 32,
                "the tab button is no longer 26x32 — the 27 px column pitch is stale",
            )
        }
    }

    private fun classNode(jar: JarFile, type: String): ClassNode {
        val entry = jar.getJarEntry("$type.class") ?: fail("$type missing from the client jar")
        val node = ClassNode()
        jar.getInputStream(entry).use { ClassReader(it.readAllBytes()).accept(node, 0) }
        return node
    }

    private fun instructionsOf(method: MethodNode): List<AbstractInsnNode> =
        List(method.instructions.size()) { method.instructions[it] }

    /** The [back]-th real instruction before [index], skipping labels / line numbers / frames. */
    private fun pushedBefore(insns: List<AbstractInsnNode>, index: Int, back: Int): Int? {
        var remaining = back
        for (i in index - 1 downTo 0) {
            if (insns[i].opcode < 0) continue
            if (--remaining == 0) return intPushed(insns[i])
        }
        return null
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
