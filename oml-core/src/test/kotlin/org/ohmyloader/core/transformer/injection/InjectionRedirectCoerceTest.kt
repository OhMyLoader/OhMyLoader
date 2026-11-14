package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `@Coerce` on `@Redirect`: reference parameters may be supertypes and the result is cast back. */
class InjectionRedirectCoerceTest {

    private val owner = "omltest/RedirectCoerceTarget"

    @Test
    fun `redirect coerces a reference argument and return`() {
        CoerceSink.calls.clear()
        val clazz = define(
            specOf {
                classTarget(owner) {
                    method("target", desc = "(Ljava/lang/String;)Ljava/lang/String;") {
                        beforeCall(
                            owner = CoerceSink.OWNER, name = "echo",
                            desc = "(Ljava/lang/String;)Ljava/lang/String;",
                        ) {
                            redirect(
                                CoerceRedirectHandlers.OWNER, "upper",
                                "(Ljava/lang/Object;)Ljava/lang/Object;",
                                coerceParams = setOf(0),
                                coerceReturn = true,
                            )
                        }
                    }
                }
            },
            target(),
        )

        assertEquals("ABC", invoke(clazz, "target", "abc"))
        assertEquals(emptyList(), CoerceSink.calls, "the original call must be replaced")
    }

    private fun target(): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = owner
        superName = "java/lang/Object"
        methods.add(
            MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "target", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC, CoerceSink.OWNER, "echo",
                        "(Ljava/lang/String;)Ljava/lang/String;", false,
                    ),
                )
                instructions.add(InsnNode(Opcodes.ARETURN))
                maxStack = 4
                maxLocals = 4
            },
        )
    }

    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionRedirectCoerceTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)

    object CoerceSink {
        val OWNER: String = CoerceSink::class.java.name.replace('.', '/')
        val calls = mutableListOf<String>()

        @JvmStatic
        fun echo(value: String): String {
            calls += value
            return value
        }
    }

    object CoerceRedirectHandlers {
        val OWNER: String = CoerceRedirectHandlers::class.java.name.replace('.', '/')

        @JvmStatic
        fun upper(value: Any?): Any? = (value as String).uppercase()
    }
}
