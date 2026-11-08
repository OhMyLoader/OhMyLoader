package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.hook.LocalRef
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared refs (`DslValue.SharedRef` / `@Share`). One `LocalRef` is created at the method head and the same
 * instance reaches every handler in the invocation. The branch case also exercises the StackMapTable patch:
 * a frame between the initializer and the use must declare the slot, or the verifier rejects the class.
 */
class InjectionShareTest {

    private val owner = "omltest/ShareTarget"

    @Test
    fun `a head handler and a later handler share the same ref`() {
        ShareSink.clear()
        ShareHandlers.observed = -1
        val clazz = define(shareSpec(), targetWithCall())

        val result = invoke(clazz, "target", 5)

        assertEquals(42, ShareHandlers.observed, "the later handler must see the value the head handler stored")
        assertEquals(listOf(5), ShareSink.calls, "the original call still runs")
        assertEquals(5, result)
    }

    @Test
    fun `the shared slot survives a stack-map frame`() {
        ShareSink.clear()
        ShareHandlers.observed = -1
        val clazz = define(shareSpec(), targetWithBranch())

        val result = invoke(clazz, "target", 5)

        assertEquals(42, ShareHandlers.observed)
        assertEquals(listOf(6), ShareSink.calls)
        assertEquals(6, result)
    }

    // ---------- fixtures ----------

    private fun shareSpec(): InjectionSpec = specOf {
        classTarget(owner) {
            method("target", desc = "(I)I") {
                atHead {
                    call(
                        ShareHandlers.OWNER,
                        "set",
                        "(Lorg/ohmyloader/api/hook/LocalRef;)V",
                        args = listOf(DslValue.SharedRef("test", "x")),
                    )
                }
                beforeCall(owner = ShareSink.OWNER, name = "record", desc = "(I)V") {
                    call(
                        ShareHandlers.OWNER,
                        "read",
                        "(ILorg/ohmyloader/api/hook/LocalRef;)V",
                        args = listOf(DslValue.Arg(0), DslValue.SharedRef("test", "x")),
                    )
                }
            }
        }
    }

    private fun classNode(vararg methods: MethodNode): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = owner
            superName = "java/lang/Object"
            this.methods.addAll(methods)
        }

    private fun methodNode(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, desc, null, null).apply {
            instructions.build()
            maxStack = 8
            maxLocals = 8
        }

    /** `static int target(int seed) { ShareSink.record(seed); return seed; }` */
    private fun targetWithCall(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, ShareSink.OWNER, "record", "(I)V", false))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /**
     * `static int target(int seed) { int x = seed; if (x > 0) x++; ShareSink.record(x); return x; }`
     * A frame sits before the call, after the shared ref is created.
     */
    private fun targetWithBranch(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            val elseLabel = LabelNode()
            add(JumpInsnNode(Opcodes.IFLE, elseLabel))
            add(IincInsnNode(1, 1))
            add(elseLabel)
            add(FrameNode(Opcodes.F_NEW, 2, arrayOf(Opcodes.INTEGER, Opcodes.INTEGER), 0, emptyArray()))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, ShareSink.OWNER, "record", "(I)V", false))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionShareTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    object ShareSink {
        val OWNER: String = ShareSink::class.java.name.replace('.', '/')

        val calls = mutableListOf<Int>()

        fun clear() = calls.clear()

        @JvmStatic
        fun record(x: Int) {
            calls += x
        }
    }

    object ShareHandlers {
        val OWNER: String = ShareHandlers::class.java.name.replace('.', '/')

        @Volatile
        var observed: Int = -1

        @JvmStatic
        fun set(ref: LocalRef<Int>) {
            ref.value = 42
        }

        @JvmStatic
        fun read(seed: Int, ref: LocalRef<Int>) {
            observed = ref.value ?: -1
        }
    }
}