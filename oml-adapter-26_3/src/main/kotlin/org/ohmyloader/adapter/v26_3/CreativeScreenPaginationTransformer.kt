package org.ohmyloader.adapter.v26_3

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The hooks behind [CreativeScreenPagination] (client only):
 *
 * - `getTabX` / `getTabY` are replaced wholesale - they read the paged row/column.
 * - `extractTabButton` reads `CreativeModeTab.row()` / `column()` directly for the strip choice
 *   and the sprite index; those two reads are swapped for the paged variants (same stack shape,
 *   tab in - value out), so the drawn tab and its hit test can never disagree.
 * - `extractBackground` draws `selectedTab` unconditionally after the page strip; the call is
 *   redirected to a visibility-guarded handler (a selected tab on another page would ghost-draw
 *   over the current page's tabs otherwise).
 * - `init` gets the two page-arrow buttons at its tail; `extractLabels` gets the `i / n`
 *   indicator at its tail.
 */
class CreativeScreenPaginationTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen") {
            method("getTabX", desc = "(Lnet/minecraft/world/item/CreativeModeTab;)I") {
                atHead {
                    raw { m, _ ->
                        m.instructions.clear()
                        m.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0))
                        m.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1))
                        m.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, PAGINATION, "tabX", TAB_X, false))
                        m.instructions.add(org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN))
                    }
                }
            }
            method("getTabY", desc = "(Lnet/minecraft/world/item/CreativeModeTab;)I") {
                atHead {
                    raw { m, _ ->
                        m.instructions.clear()
                        m.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0))
                        m.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 1))
                        m.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, PAGINATION, "tabY", TAB_Y, false))
                        m.instructions.add(org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN))
                    }
                }
            }
            method("extractTabButton", desc = TAB_BUTTON) {
                atHead {
                    raw { m, _ -> rewriteTabReads(m) }
                }
            }
            method("extractBackground", desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V") {
                atHead {
                    raw { m, _ -> redirectSelectedTabDraw(m) }
                }
            }
            method("init", desc = "()V") {
                atTail {
                    call(PAGINATION, "addPageButtons", ADD_PAGE_BUTTONS, args = listOf(DslValue.This))
                }
            }
            method("extractLabels", desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;II)V") {
                atTail {
                    call(PAGINATION, "drawPageIndicator", DRAW_PAGE_INDICATOR, args = listOf(DslValue.This, DslValue.Arg(0)))
                }
            }
        }
    },
    id = "v26_3:creative-pagination",
) {
    companion object {
        private const val PAGINATION = "org/ohmyloader/adapter/v26_3/CreativeScreenPagination"
        private const val SCREEN = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen"
        private const val TAB = "net/minecraft/world/item/CreativeModeTab"
        private const val ROW_TYPE = "Lnet/minecraft/world/item/CreativeModeTab" + "$" + "Row;"
        private const val TAB_BUTTON = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/item/CreativeModeTab;)V"
        private const val TAB_X = "(L" + SCREEN + ";L" + TAB + ";)I"
        private const val TAB_Y = "(L" + SCREEN + ";L" + TAB + ";)I"
        private const val ADD_PAGE_BUTTONS = "(L" + SCREEN + ";)V"
        private const val DRAW_PAGE_INDICATOR = "(L" + SCREEN + ";Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V"

        /**
         * Swaps the two `CreativeModeTab.row()` / `column()` reads inside [m] for the paged
         * variants - same stack shape (the tab is already on the stack, one value comes back),
         * so each call site is a one-instruction substitution.
         */
        private fun rewriteTabReads(m: MethodNode) {
            for (insn in m.instructions) {
                val call = insn as? MethodInsnNode ?: continue
                if (call.owner != TAB || (call.name != "row" && call.name != "column")) {
                    continue
                }
                when (call.name) {
                    "row" -> m.instructions.set(
                        call,
                        MethodInsnNode(Opcodes.INVOKESTATIC, PAGINATION, "pagedRow", "(L" + TAB + ";)" + ROW_TYPE, false),
                    )
                    "column" -> m.instructions.set(
                        call,
                        MethodInsnNode(Opcodes.INVOKESTATIC, PAGINATION, "pagedColumn", "(L" + TAB + ";)I", false),
                    )
                }
            }
        }

        /**
         * Redirects the extractTabButton call that draws `selectedTab` (its tab argument is fed
         * by the GETSTATIC of the screen's static selectedTab field) to the visibility-guarded
         * handler. The static form consumes the receiver as its first parameter, so the stack
         * stays balanced.
         */
        private fun redirectSelectedTabDraw(m: MethodNode) {
            val calls = m.instructions.toList()
                .withIndex()
                .filter { (_, insn) -> insn is MethodInsnNode && insn.name == "extractTabButton" && insn.owner == SCREEN }
                .map { it.index }
            for (index in calls.reversed()) {
                val previous = m.instructions[index].previous as? FieldInsnNode ?: continue
                if (previous.opcode != Opcodes.GETSTATIC || previous.name != "selectedTab") {
                    continue
                }
                m.instructions.set(
                    m.instructions[index],
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC, PAGINATION, "drawSelectedTabIfVisible",
                        "(L" + SCREEN + ";Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIL" + TAB + ";)V", false,
                    ),
                )
            }
        }
    }
}
