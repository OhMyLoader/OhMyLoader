package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.core.transformer.ExclusiveClaim
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Cross-transformer exclusive claims: adapters and mods must not race for the same instruction. */
class CrossTransformerConflictsTest {

    private val target = "omltest/CrossConflictTarget"

    @Test
    fun `two transformers claiming the same instruction are reported`() {
        val specA = specOf { wrapMax("A") }
        val specB = specOf { wrapMax("B") }
        val node = target()
        val context = TransformContext(target, node)
        val sources = listOf(
            CrossTransformerConflicts.Source("adapter", specA.exclusiveClaims(context)),
            CrossTransformerConflicts.Source("mixins", specB.exclusiveClaims(context)),
        )

        val conflicts = CrossTransformerConflicts.find(sources)

        assertEquals(1, conflicts.size, conflicts.toString())
        assertTrue(conflicts.single().contains("[adapter]"), conflicts.single())
        assertTrue(conflicts.single().contains("[mixins]"), conflicts.single())
    }

    @Test
    fun `claims from one transformer are not a cross-transformer conflict`() {
        val claim = ExclusiveClaim("target", "(I)I", 2, false, "rule")
        val conflicts = CrossTransformerConflicts.find(
            listOf(
                CrossTransformerConflicts.Source("mixins", listOf(claim, claim)),
                CrossTransformerConflicts.Source("other", emptyList()),
            ),
        )

        assertEquals(emptyList(), conflicts)
    }

    @Test
    fun `hasExclusivePoints sees the rule before resolving it`() {
        val spec = specOf { wrapMax("A") }
        assertTrue(spec.hasExclusivePoints(target))
        assertTrue(!spec.hasExclusivePoints("omltest/Other"))
    }

    private fun org.ohmyloader.api.inject.InjectionBuilder.wrapMax(handler: String) {
        classTarget(target) {
            method("calc", desc = "(I)I") {
                beforeCall(owner = "java/lang/Math", name = "max", desc = "(II)I") {
                    wrapOperation(
                        handler, "wrap",
                        "(IILorg/ohmyloader/api/hook/Operation;)I",
                    )
                }
            }
        }
    }

    private fun target(): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = target
        superName = "java/lang/Object"
        methods.add(
            MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "calc", "(I)I", null, null).apply {
                instructions.add(VarInsnNode(Opcodes.ILOAD, 0))
                instructions.add(InsnNode(Opcodes.ICONST_3))
                instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false))
                instructions.add(InsnNode(Opcodes.IRETURN))
                maxStack = 4
                maxLocals = 2
            },
        )
    }
}