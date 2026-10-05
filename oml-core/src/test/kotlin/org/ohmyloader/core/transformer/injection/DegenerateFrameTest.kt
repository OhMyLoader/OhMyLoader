package org.ohmyloader.core.transformer.injection

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val OWNER = "omltest/FrameTarget"
private const val HOOK = "org/ohmyloader/core/transformer/injection/FrameHooks"

/** Static hook called by the injection block: records the target instance it receives. */
object FrameHooks {
    val seen = mutableListOf<Any>()

    @JvmStatic
    fun onReturn(instance: Any) {
        seen += instance
    }
}

/**
 * The vanilla "degenerate stack frame" region: a frame that declares `this` dead. Replicates the shape of
 * `getFramerateLimit()`: two branch targets 21/23 and 24/26, where the frame at `21: bipush 60` is a **chop**
 * to empty locals (even `this` gone) and the next frame `append`s it back:
 * ```
 * 21: bipush 60   ← branch target; frame here is chop ⇒ locals = []
 * 23: ireturn
 * 24: bipush 7    ← another branch target; append ⇒ locals = [FrameTarget]
 * 26: ireturn
 * ```
 * The vanilla code never touches locals in that window, so HotSpot stays silent; but once the injection block
 * reads `this`, the verifier judges from that empty-locals frame that "slot 0 is top" and rejects the whole
 * class (`VerifyError: Bad local variable type @23: aload_0 — Type top ... not assignable to reference type`).
 * Fix: declare `this` back into that frame ([FrameRepair]), without touching the code.
 */
class DegenerateFrameTest {

    @Test
    fun `the degenerate frame shape is legal as long as nothing reads a local`() {
        val clazz = define(targetClass(), null)

        val taken = newInstance(clazz, arrayOf(null, "b", null))
        assertEquals(60, clazz.getMethod("getLimit").invoke(taken), "the original shape must be runnable by itself")

        val skipped = newInstance(clazz, arrayOf("a", "b", "c"))
        assertEquals(7, clazz.getMethod("getLimit").invoke(skipped))
    }

    @Test
    fun `the frame covering the injection point ends up declaring this`() {
        val node = targetClass()
        assertTrue(localReadAtReturn().transform(TransformContext(OWNER, node)), "the injection should happen")

        val insns = node.methods.first { it.name == "getLimit" }.instructions.toArray()
        val hookIndex = insns.indexOfFirst { it is MethodInsnNode && it.owner == HOOK }
        assertTrue(hookIndex > 0, "the injected block did not land inside the method body")

        val covering = insns.take(hookIndex).lastOrNull { it is FrameNode } as? FrameNode
        assertNotNull(
            covering,
            "there should be that frame (whose this was originally dropped) before the injection point",
        )
        assertEquals(
            OWNER, covering.local?.firstOrNull(),
            "the stack frame covering the injection point must re-declare this, otherwise the injected aload_0 would be rejected by the verifier:" +
                "locals=${covering.local?.toList()}",
        )
    }

    @Test
    fun `the injected block runs and sees the target instance`() {
        FrameHooks.seen.clear()
        val clazz = define(targetClass(), localReadAtReturn())

        val instance = newInstance(clazz, arrayOf(null, "b", null))
        assertEquals(
            60,
            clazz.getMethod("getLimit").invoke(instance),
            "the return value is unaffected by the injection",
        )

        assertEquals(1, FrameHooks.seen.size, "the injected block should run exactly once")
        assertSame(
            instance,
            FrameHooks.seen[0],
            "the this received by the injected block must be the target instance itself",
        )
    }

    // ---------- spec ----------

    /** Read `this` before the first `ireturn` — the landing point sits exactly in the window
     *  where "the frame lost `this`". */
    private fun localReadAtReturn(): InjectionSpec = specOf {
        classTarget(OWNER) {
            method("getLimit", desc = "()I") {
                atReturn(0) {
                    call(HOOK, "onReturn", "(Ljava/lang/Object;)V", listOf<DslValue>(DslValue.This))
                }
            }
        }
    }

    // ---------- target class ----------

    private fun targetClass(): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = OWNER
        superName = "java/lang/Object"
        fields.add(FieldNode(Opcodes.ACC_PRIVATE, "a", "Ljava/lang/String;", null, null))
        fields.add(FieldNode(Opcodes.ACC_PRIVATE, "b", "Ljava/lang/String;", null, null))
        fields.add(FieldNode(Opcodes.ACC_PRIVATE, "c", "Ljava/lang/String;", null, null))
        methods.add(constructor())
        methods.add(getLimit())
    }

    private fun constructor(): MethodNode =
        MethodNode(
            Opcodes.ACC_PUBLIC,
            "<init>",
            "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            null,
            null,
        ).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(
                MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false),
            )
            for ([slot, field] in listOf(1 to "a", 2 to "b", 3 to "c")) {
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(VarInsnNode(Opcodes.ALOAD, slot))
                instructions.add(FieldInsnNode(Opcodes.PUTFIELD, OWNER, field, "Ljava/lang/String;"))
            }
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 2
            maxLocals = 4
        }

    private fun getLimit(): MethodNode = MethodNode(Opcodes.ACC_PUBLIC, "getLimit", "()I", null, null).apply {
        // Two branch targets: `toSixty` (frame loses this) and `toSeven` (frame brings this back)
        val toSixty = LabelNode()
        val toSeven = LabelNode()

        instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
        instructions.add(FieldInsnNode(Opcodes.GETFIELD, OWNER, "a", "Ljava/lang/String;"))
        instructions.add(JumpInsnNode(Opcodes.IFNONNULL, toSeven))

        instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
        instructions.add(FieldInsnNode(Opcodes.GETFIELD, OWNER, "b", "Ljava/lang/String;"))
        instructions.add(JumpInsnNode(Opcodes.IFNONNULL, toSixty))

        instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
        instructions.add(FieldInsnNode(Opcodes.GETFIELD, OWNER, "c", "Ljava/lang/String;"))
        instructions.add(JumpInsnNode(Opcodes.IFNULL, toSeven))

        // Branch target one (offset 21): locals = [] — the degenerate frame sits on this one
        instructions.add(toSixty)
        instructions.add(FrameNode(Opcodes.F_NEW, 0, emptyArray<Any>(), 0, emptyArray<Any>()))
        instructions.add(IntInsnNode(Opcodes.BIPUSH, 60))
        instructions.add(InsnNode(Opcodes.IRETURN))

        // Branch target two (offset 24): locals = [FrameTarget]
        instructions.add(toSeven)
        instructions.add(FrameNode(Opcodes.F_NEW, 1, arrayOf<Any>(OWNER), 0, emptyArray<Any>()))
        instructions.add(IntInsnNode(Opcodes.BIPUSH, 7))
        instructions.add(InsnNode(Opcodes.IRETURN))

        maxStack = 1
        maxLocals = 1
    }

    // ---------- define and run ----------

    private fun newInstance(clazz: Class<*>, args: Array<Any?>): Any =
        clazz.getDeclaredConstructor(
            String::class.java, String::class.java, String::class.java,
        ).newInstance(*args)

    /**
     * The write-back uses `COMPUTE_MAXS` (**consistent with the production path**): `COMPUTE_FRAMES`
     * would compute the frames for the caller, masking whether "the frames the injection block wrote
     * itself" are correct — and that is exactly the layer this case targets.
     *
     * Note that class verification happens at **first linking** (`newInstance` / reflective member
     * access), not at `defineClass`: that is when "whether this bytecode passes the verifier" is
     * decided.
     */
    private fun define(node: ClassNode, spec: InjectionSpec?): Class<*> {
        if (spec != null) {
            assertTrue(spec.transform(TransformContext(OWNER, node)), "the injection should happen")
        }
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(DegenerateFrameTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }
}
