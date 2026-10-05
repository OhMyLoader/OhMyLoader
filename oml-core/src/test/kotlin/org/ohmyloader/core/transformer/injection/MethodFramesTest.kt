package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.DslValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reproduces the multi-anchor injection shape: the frame table is addressed by instruction
 * **index**, every inserted block shifts all later indexes, so a table cached before the first
 * insertion answers at the wrong instruction for the second anchor. Without invalidation the
 * lookup lands past the old table and the hook is emitted with a missing/wrong local — either a
 * silently wrong argument or a VerifyError on the produced class.
 */
class MethodFramesTest {

    private val owner = "omltest/FramesTarget"

    private fun buildMethod(): MethodNode {
        val method = MethodNode(Opcodes.ASM9, Opcodes.ACC_STATIC, "m", "()V", null, null)
        method.maxStack = 4
        method.maxLocals = 4
        method.instructions.apply {
            add(LdcInsnNode("tag"))                                                                  // 0
            add(VarInsnNode(Opcodes.ASTORE, 1))                                                     // 1
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "sink", "a", "()V", false))                     // 2 anchor A
            add(InsnNode(Opcodes.ICONST_5))                                                          // 3
            add(VarInsnNode(Opcodes.ISTORE, 2))                                                     // 4
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "sink", "b", "()V", false))                     // 5 anchor B
            add(InsnNode(Opcodes.RETURN))                                                            // 6
        }
        return method
    }

    private fun callOf(method: MethodNode, name: String): AbstractInsnNode =
        method.instructions.iterator().asSequence().filterIsInstance<MethodInsnNode>()
            .first { it.name == name }

    @Test
    fun `frame lookups after an injected block stay anchored to the mutated list`() {
        val method = buildMethod()
        val anchorA = callOf(method, "a")
        val anchorB = callOf(method, "b")
        val frames = MethodFrames(owner, method)

        // Analysis is forced while the list is pristine: slot 2 is not live yet at anchor A.
        assertNull(frames.localTypeAt(anchorA, 2))

        // The injection of anchor A's block shifts everything after it by three instructions.
        val block = InsnList().apply { repeat(3) { add(InsnNode(Opcodes.NOP)) } }
        method.instructions.insert(anchorA, block)

        // Anchor B resolves slot 2 from the **mutated** list (live int), not from the stale table.
        val atB = frames.at(anchorB, after = false)
        assertEquals(Type.INT_TYPE, atB.frame()?.live(2), "slot 2 at anchor B: ${atB.explain()}")
        assertEquals("Ljava/lang/String;", frames.localTypeAt(anchorB, 1)?.descriptor)
    }

    @Test
    fun `explicit slot resolution survives the shift`() {
        val method = buildMethod()
        val anchorB = callOf(method, "b")
        val frames = MethodFrames(owner, method)

        // Trigger the analysis first, then mutate (two rules × one method: rule one injects, rule two resolves).
        frames.localTypeAt(callOf(method, "a"), 1)
        val block = InsnList().apply { repeat(3) { add(InsnNode(Opcodes.NOP)) } }
        method.instructions.insert(callOf(method, "a"), block)

        val resolution = frames.at(anchorB, after = false).frame()
            ?.resolve(DslValue.Local(index = 2), isStatic = true)
        assertEquals(2, (resolution as? LocalResolution.Found)?.slot, "slot 2 must resolve at anchor B after the shift")
    }
}
