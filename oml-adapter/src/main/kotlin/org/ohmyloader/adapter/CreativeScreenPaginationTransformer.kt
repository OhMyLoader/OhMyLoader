package org.ohmyloader.adapter

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.core.inject.DslValue
import org.ohmyloader.api.core.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The hooks behind [CreativeScreenPagination] — the takeover of the creative screen's tab strip
 * (client only). The vanilla methods that decide strip layout and interaction are replaced
 * wholesale with calls into the strip-model handlers; the vanilla loops remain and drive them
 * per tab:
 *
 * - `extractTabButton` → [CreativeScreenPagination.drawTab]: draws a tab only where the strip
 *   model gives it a slot, at the model's position;
 * - `checkTabClicked` / `checkTabHovering` → [CreativeScreenPagination.tabClicked] /
 *   [CreativeScreenPagination.tabHovered]: hit tests against the strip slot;
 * - `mouseClicked` head → [CreativeScreenPagination.handleArrowClick]: consumes clicks on the
 *   page arrows (with an explicit stackmap frame — a branch target needs one, and the loader's
 *   writer does not recompute frames);
 * - `extractBackground` tail → the page-arrow sprites; `extractLabels` tail → the `i / n`
 *   indicator; `init` tail → the strip rebuild (player config read here too).
 */
class CreativeScreenPaginationTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen") {
            method("extractTabButton", desc = TAB_BUTTON) {
                atHead {
                    raw { m, _ ->
                        m.instructions.clear()
                        m.localVariables.clear()
                        m.visibleLocalVariableAnnotations?.clear()
                        m.invisibleLocalVariableAnnotations?.clear()
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
                        m.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, PAGINATION, "drawTab", DRAW_TAB, false))
                        m.instructions.add(org.objectweb.asm.tree.InsnNode(Opcodes.RETURN))
                    }
                }
            }
            method("checkTabClicked", desc = "(Lnet/minecraft/world/item/CreativeModeTab;DD)Z") {
                atHead {
                    raw { m, _ ->
                        m.instructions.clear()
                        m.localVariables.clear()
                        m.visibleLocalVariableAnnotations?.clear()
                        m.invisibleLocalVariableAnnotations?.clear()
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
                        m.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.DLOAD, 2))
                        m.instructions.add(org.objectweb.asm.tree.VarInsnNode(Opcodes.DLOAD, 4))
                        m.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, PAGINATION, "tabClicked", TAB_CLICKED_STATIC, false))
                        m.instructions.add(org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN))
                    }
                }
            }
            method("checkTabHovering", desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/CreativeModeTab;II)Z") {
                atHead {
                    raw { m, _ ->
                        m.instructions.clear()
                        m.localVariables.clear()
                        m.visibleLocalVariableAnnotations?.clear()
                        m.invisibleLocalVariableAnnotations?.clear()
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 2))
                        m.instructions.add(VarInsnNode(Opcodes.ILOAD, 3))
                        m.instructions.add(VarInsnNode(Opcodes.ILOAD, 4))
                        m.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, PAGINATION, "tabHovered", TAB_HOVERING_STATIC, false))
                        m.instructions.add(org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN))
                    }
                }
            }
            method("extractLabels", desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V") {
                atTail {
                    call(
                        PAGINATION, "drawPageIndicator",
                        "(L" + SCREEN_TYPE + ";Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
                        args = listOf(DslValue.This, DslValue.Arg(0)),
                    )
                }
            }
            method("init", desc = "()V") {
                atTail {
                    call(PAGINATION, "onScreenInit", "(L" + SCREEN_TYPE + ";)V", args = listOf(DslValue.This))
                }
            }
        }
    },
    id = "oml:creative-pagination",
) {
    companion object {
        private const val PAGINATION = "org/ohmyloader/adapter/CreativeScreenPagination"
        private const val SCREEN_TYPE = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen"
        private const val TAB = "net/minecraft/world/item/CreativeModeTab"
        private const val TAB_BUTTON = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/CreativeModeTab;)V"
        private const val TAB_CLICKED_STATIC = "(L" + SCREEN_TYPE + ";Lnet/minecraft/world/item/CreativeModeTab;DD)Z"
        private const val TAB_HOVERING_STATIC = "(L" + SCREEN_TYPE + ";Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/CreativeModeTab;II)Z"
        private const val DRAW_TAB = "(L" + SCREEN_TYPE + ";Lnet/minecraft/client/gui/GuiGraphicsExtractor;L" + TAB + ";)V"
    }
}
