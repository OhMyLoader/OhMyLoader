package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.hook.LocalIntRef
import org.ohmyloader.api.hook.LocalRef
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Mutable local capture (`@Local` on a `LocalRef` parameter / `DslValue.RefLocal`). The ref is created and
 * initialized at the injection point, handed to the handler, and its final value written back to the local.
 * Defining and executing the class is the only way to prove the write-back actually reaches the local that
 * the following code reads.
 */
class InjectionLocalRefTest {

    private val owner = "omltest/LocalRefTarget"

    @Test
    fun `the handler's ref value is written back to the local`() {
        LocalRefSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        afterStore(index = 1, type = "I") {
                            call(
                                LocalRefHandlers.OWNER, "bump", "(Lorg/ohmyloader/api/hook/LocalRef;)V",
                                args = listOf(DslValue.RefLocal(index = 1, type = "I")),
                            )
                        }
                    }
                }
            },
            targetWithLocal(),
        )

        val result = invoke(clazz, "target", 5)

        // x = seed * 2 = 10; the write-back happens right after the store, so the later read,
        // record() and the return all see 11.
        assertEquals(listOf(11), LocalRefSink.calls)
        assertEquals(11, result)
    }

    @Test
    fun `the handler can replace the local with an unrelated value`() {
        LocalRefSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        afterStore(index = 1, type = "I") {
                            call(
                                LocalRefHandlers.OWNER, "force", "(Lorg/ohmyloader/api/hook/LocalRef;)V",
                                args = listOf(DslValue.RefLocal(index = 1, type = "I")),
                            )
                        }
                    }
                }
            },
            targetWithLocal(),
        )

        val result = invoke(clazz, "target", 5)

        assertEquals(listOf(99), LocalRefSink.calls)
        assertEquals(99, result)
    }

    @Test
    fun `a typed LocalIntRef avoids boxing and writes back`() {
        LocalRefSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(I)I") {
                        afterStore(index = 1, type = "I") {
                            call(
                                LocalRefHandlers.OWNER, "bumpInt",
                                "(Lorg/ohmyloader/api/hook/LocalIntRef;)V",
                                args = listOf(
                                    DslValue.RefLocal(
                                        index = 1,
                                        type = "I",
                                        refType = "Lorg/ohmyloader/api/hook/LocalIntRef;",
                                    ),
                                ),
                            )
                        }
                    }
                }
            },
            targetWithLocal(),
        )

        val result = invoke(clazz, "target", 5)

        assertEquals(listOf(11), LocalRefSink.calls)
        assertEquals(11, result)
    }

    @Test
    fun `mutable local sugar works on a ModifyArg handler`() {
        LocalRefSink.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("touch", desc = "(I)I") {
                        beforeCall(owner = LocalRefSink.OWNER, name = "record", desc = "(I)V") {
                            modifyArg(
                                LocalRefHandlers.OWNER, "bumpArg",
                                "(ILorg/ohmyloader/api/hook/LocalRef;)I",
                                index = 0,
                                extras = listOf(DslValue.RefLocal(index = 1, type = "I")),
                            )
                        }
                    }
                }
            },
            targetWithModifyArgLocal(),
        )

        val result = invoke(clazz, "touch", 5)

        assertEquals(listOf(7), LocalRefSink.calls, "the original call still sees its argument")
        assertEquals(99, result, "the handler's ref value is written back to the local")
    }

    @Test
    fun `redirect sugar can write back a local`() {
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("redirected", desc = "(I)I") {
                        redirectCall(
                            owner = "java/lang/Math",
                            name = "max",
                            desc = "(II)I",
                            handlerOwner = LocalRefHandlers.OWNER,
                            handlerMethod = "replaceMax",
                            handlerDesc = "(IILorg/ohmyloader/api/hook/LocalRef;)I",
                            extras = listOf(DslValue.RefLocal(index = 1, type = "I")),
                        )
                    }
                }
            },
            targetWithRedirectLocal(),
        )

        // x = seed; y = max(1, 2) replaced by the handler (returns 3, sets x = 99); return x + y.
        assertEquals(102, invoke(clazz, "redirected", 5))
    }

    // ---------- fixtures ----------

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

    /** `static int target(int seed) { int x = seed * 2; LocalRefSink.record(x); return x; }` */
    private fun targetWithLocal(): ClassNode = classNode(
        methodNode("target", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_2))
            add(InsnNode(Opcodes.IMUL))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, LocalRefSink.OWNER, "record", "(I)V", false))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int redirected(int seed) { int x = seed; int y = Math.max(1, 2); return x + y; }` */
    private fun targetWithRedirectLocal(): ClassNode = classNode(
        methodNode("redirected", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.ICONST_2))
            add(
                MethodInsnNode(
                    Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(II)I", false,
                ),
            )
            add(VarInsnNode(Opcodes.ISTORE, 2))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 2))
            add(InsnNode(Opcodes.IADD))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int touch(int seed) { int x = seed; LocalRefSink.record(7); return x; }` */
    private fun targetWithModifyArgLocal(): ClassNode = classNode(
        methodNode("touch", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(IntInsnNode(Opcodes.BIPUSH, 7))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, LocalRefSink.OWNER, "record", "(I)V", false))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionLocalRefTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    object LocalRefSink {
        val OWNER: String = LocalRefSink::class.java.name.replace('.', '/')

        val calls = mutableListOf<Int>()

        fun clear() = calls.clear()

        @JvmStatic
        fun record(x: Int) {
            calls += x
        }
    }

    object LocalRefHandlers {
        val OWNER: String = LocalRefHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun bump(ref: LocalRef<Int>) {
            ref.value = (ref.value ?: 0) + 1
        }

        @JvmStatic
        fun force(ref: LocalRef<Int>) {
            ref.value = 99
        }

        @JvmStatic
        fun bumpInt(ref: LocalIntRef) {
            ref.value += 1
        }

        @JvmStatic
        fun bumpArg(value: Int, ref: LocalRef<Int>): Int {
            ref.value = 99
            return value
        }

        @JvmStatic
        fun replaceMax(a: Int, b: Int, ref: LocalRef<Int>): Int {
            ref.value = 99
            return a + b
        }
    }
}