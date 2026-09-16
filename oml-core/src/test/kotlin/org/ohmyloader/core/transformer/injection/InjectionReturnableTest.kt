package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.mixin.CallbackInfoReturnable
import org.ohmyloader.core.mixin.OMLMixinRegistry
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.*

/**
 * Value-bearing short-circuit for non-void targets (`CancellableReturn` + `CallbackInfoReturnable`).
 *
 * These cases assert more than "the instructions were inserted" — they **actually define the class
 * and execute it** — because this area most often fails with **invalid stack frames**
 * (`VerifyError`), which only surfaces when the class is defined. Merely checking the instruction
 * list cannot catch it.
 */
class InjectionReturnableTest {

    /** The target class and OML's bridge class sit on the same visible chain (see [defineTarget]). */
    private val owner = "omltest/Target"

    // ---------- end to end: define the class + really execute ----------

    @Test
    fun `cancelled handler replaces the return value`() {
        val spec = returnableSpec("answer", "()I", capturesTargetArg = false, handler = "returnInt")
        val clazz = defineTarget(
            spec,
            methodNode("answer", "()I") { insn(Opcodes.ICONST_1, Opcodes.IRETURN) },
        )

        assertEquals(99, invoke(clazz, "answer"))
    }

    @Test
    fun `uncancelled handler leaves the original behaviour untouched`() {
        val spec = returnableSpec("answer", "()I", capturesTargetArg = false, handler = "leaveAlone")
        val clazz = defineTarget(
            spec,
            methodNode("answer", "()I") { insn(Opcodes.ICONST_1, Opcodes.IRETURN) },
        )

        assertEquals(1, invoke(clazz, "answer"))
    }

    @Test
    fun `long return value round trips through the two-slot local`() {
        val spec = returnableSpec("big", "()J", capturesTargetArg = false, handler = "returnLong")
        val clazz = defineTarget(
            spec,
            methodNode("big", "()J") { insn(Opcodes.LCONST_0, Opcodes.LRETURN) },
        )

        assertEquals(9_000_000_000L, invoke(clazz, "big"))
    }

    @Test
    fun `double return value round trips`() {
        val spec = returnableSpec("ratio", "()D", capturesTargetArg = false, handler = "returnDouble")
        val clazz = defineTarget(
            spec,
            methodNode("ratio", "()D") { insn(Opcodes.DCONST_0, Opcodes.DRETURN) },
        )

        assertEquals(1.5, invoke(clazz, "ratio"))
    }

    @Test
    fun `object return value is cast back to the declared type`() {
        val spec = returnableSpec("label", "()Ljava/lang/String;", capturesTargetArg = false, handler = "returnString")
        val clazz = defineTarget(
            spec,
            methodNode("label", "()Ljava/lang/String;") { insn(Opcodes.ACONST_NULL, Opcodes.ARETURN) },
        )

        assertEquals("hooked", invoke(clazz, "label"))
    }

    @Test
    fun `boolean target is intercepted`() {
        val spec = returnableSpec("flag", "()Z", capturesTargetArg = false, handler = "returnTrue")
        val clazz = defineTarget(
            spec,
            methodNode("flag", "()Z") { insn(Opcodes.ICONST_0, Opcodes.IRETURN) },
        )

        assertEquals(true, invoke(clazz, "flag"))
    }

    @Test
    fun `handler may capture a target argument`() {
        // Target (I)I: capture argument 0, the handler multiplies it by 10 and returns
        val spec = returnableSpec("scale", "(I)I", capturesTargetArg = true, handler = "timesTen")
        val clazz = defineTarget(
            spec,
            methodNode("scale", "(I)I") { load(0, Opcodes.ILOAD); insn(Opcodes.IRETURN) },
        )

        val method = clazz.getMethod("scale", Int::class.javaPrimitiveType)
        assertEquals(70, method.invoke(null, 7))
    }

    @Test
    fun `frame stays valid when the method has extra locals beyond its arguments`() {
        // Key regression: the body also has a slot 2 (larger than this+args), so the new handle must land
        // in slot 3. If the frame wrote the handle at the "right after the parameters" position
        // (slot 2), it would be misaligned with the actual ASTORE -> VerifyError.
        val spec = returnableSpec("withLocal", "(I)I", capturesTargetArg = false, handler = "returnInt")
        val clazz = defineTarget(
            spec,
            methodNode("withLocal", "(I)I") {
                load(0, Opcodes.ILOAD)
                store(2, Opcodes.ISTORE)
                load(2, Opcodes.ILOAD)
                insn(Opcodes.IRETURN)
            },
        )

        assertEquals(99, invoke(clazz, "withLocal", 5))
    }

    @Test
    fun `frame stays valid when the original body contains branches`() {
        // The original method has branches (hence its own stack frames). The injection block's frames
        // must coexist with them.
        val spec = returnableSpec("branchy", "(I)I", capturesTargetArg = false, handler = "returnInt")
        val clazz = defineTarget(
            spec,
            methodNode("branchy", "(I)I") {
                val notPositive = LabelNode()
                load(0, Opcodes.ILOAD)
                add(JumpInsnNode(Opcodes.IFLE, notPositive))
                add(InsnNode(Opcodes.ICONST_1))
                add(InsnNode(Opcodes.IRETURN))
                add(notPositive)
                add(FrameNode(Opcodes.F_NEW, 1, arrayOf<Any>(Opcodes.INTEGER), 0, emptyArray()))
                add(InsnNode(Opcodes.ICONST_2))
                add(InsnNode(Opcodes.IRETURN))
            },
        )

        // The handler cancels, so neither branch is reached: returns 99
        assertEquals(99, invoke(clazz, "branchy", -1))
    }

    @Test
    fun `instance method target uses slot zero for this`() {
        val spec = returnableSpec("answer", "()I", capturesTargetArg = false, handler = "returnInt")
        val node = targetClass(
            MethodNode(
                Opcodes.ACC_PUBLIC, "answer", "()I", null, null
            ).apply {
                instructions.insn(Opcodes.ICONST_1, Opcodes.IRETURN)
                maxStack = 1
                maxLocals = 1
            }
        )
        val clazz = define(node, spec)
        val instance = clazz.getDeclaredConstructor().newInstance()

        assertEquals(99, clazz.getMethod("answer").invoke(instance))
    }

    @Test
    fun `cancelling without setting a value yields the type's zero value`() {
        // Cancel without setting a value: the typed accessor falls back to the zero value (not an NPE) —
        // this is exactly why the typed accessor was chosen over Object + unboxing
        val spec = returnableSpec("answer", "()I", capturesTargetArg = false, handler = "cancelOnly")
        val clazz = defineTarget(
            spec,
            methodNode("answer", "()I") { insn(Opcodes.ICONST_1, Opcodes.IRETURN) },
        )

        assertEquals(0, invoke(clazz, "answer"))
    }

    // ---------- rule-level validation ----------

    @Test
    fun `cancellableReturn on a void target is rejected without injecting`() {
        val spec = returnableSpec("answer", "()I", capturesTargetArg = false, handler = "returnInt")
        val node = targetClass(methodNode("noop", "()V") { insn(Opcodes.RETURN) })

        // Void target -> the engine rejects the payload (printing the reason), injects nothing
        assertFalse(spec.transform(TransformContext(owner, node)))
    }

    @Test
    fun `cancellableReturn bridge desc must end with the returnable handle`() {
        val method = methodNode("answer", "()I") { insn(Opcodes.ICONST_1, Opcodes.IRETURN) }
        val node = targetClass(method)
        val spec = specOf {
            classTarget(owner) {
                method("answer", desc = "()I") {
                    atHead {
                        // Missing handle: the descriptor ends with (I)V — provably invalid, must fail hard
                        cancellableReturn(ReturnHandlers.OWNER, "returnInt", "(I)V", listOf(DslValue.IntVal(1)))
                    }
                }
            }
        }

        val error = assertFailsWith<InjectionError> { spec.transform(TransformContext(owner, node)) }
        assertTrue(error.message!!.contains("CallbackInfoReturnable"), error.message)
    }

    @Test
    fun `verify reports a non head anchored cancellableReturn`() {
        val spec = specOf {
            classTarget(owner) {
                method("answer", desc = "()I") {
                    atTail {
                        cancellableReturn(
                            ReturnHandlers.OWNER, "returnInt",
                            "(ILorg/ohmyloader/api/mixin/CallbackInfoReturnable;)V",
                            listOf(DslValue.IntVal(1)),
                        )
                    }
                }
            }
        }

        val problems = spec.verify()
        assertTrue(
            problems.any { it.contains("CancellableReturn only supports method-entry anchors") },
            problems.toString()
        )
    }

    @Test
    fun `verify reports a bridge desc missing the handle`() {
        val spec = specOf {
            classTarget(owner) {
                method("answer", desc = "()I") {
                    atHead {
                        cancellableReturn(ReturnHandlers.OWNER, "returnInt", "(I)V", listOf(DslValue.IntVal(1)))
                    }
                }
            }
        }

        val problems = spec.verify()
        assertTrue(problems.any { it.contains("CallbackInfoReturnable") }, problems.toString())
    }

    // ---------- bridge contract (registry side) ----------

    @Test
    fun `returnable bridge gets the handle in its descriptor`() {
        OMLMixinRegistry.resetForTests()
        val id = OMLMixinRegistry.register(
            "testmod", ReturnHandlers::class.java.name, "returnInt", "answer", cancellable = true,
            returnsValue = true, handlerParamCount = 1,
        )

        val kind = OMLMixinRegistry.bridgeKind(id)
        assertEquals(OMLMixinRegistry.BridgeKind.RETURNABLE, kind)
        assertEquals(
            "(ILorg/ohmyloader/api/mixin/CallbackInfoReturnable;)V",
            OMLMixinRegistry.bridgeDesc(id, OMLMixinRegistry.BridgeKind.RETURNABLE),
        )
        assertEquals(
            "injectReturn$$id",
            OMLMixinRegistry.bridgeMethodName(id, OMLMixinRegistry.BridgeKind.RETURNABLE),
        )
    }

    @Test
    fun `returnsValue wins over cancellable when deciding the bridge kind`() {
        OMLMixinRegistry.resetForTests()
        val id = OMLMixinRegistry.register(
            "testmod", ReturnHandlers::class.java.name, "returnInt", "answer",
            cancellable = true, returnsValue = true, handlerParamCount = 1,
        )
        assertEquals(OMLMixinRegistry.BridgeKind.RETURNABLE, OMLMixinRegistry.bridgeKind(id))
    }

    // ---------- scaffolding ----------

    /**
     * Runs the whole chain through the real registry + generated bridge class (no stubbing):
     * register handler → generate bridge class → build a `CancellableReturn` rule.
     *
     * @param targetName target method name (the rule's selector uses it)
     * @param targetDesc target method descriptor (determines the return type and capture slots)
     * @param capturesTargetArg whether the handler captures the target method's 0th argument
     */
    private fun returnableSpec(
        targetName: String,
        targetDesc: String,
        capturesTargetArg: Boolean,
        handler: String,
    ): InjectionSpec {
        OMLMixinRegistry.resetForTests()
        OMLMixinRegistry.installClassLoader(InjectionReturnableTest::class.java.classLoader)
        val id = OMLMixinRegistry.register(
            modId = "testmod",
            mixinClass = ReturnHandlers::class.java.name,
            handlerMethod = handler,
            targetMethod = targetName,
            cancellable = true,
            captureTypes = if (capturesTargetArg) listOf("I") else emptyList(),
            handlerParamCount = if (capturesTargetArg) 2 else 1,
            returnsValue = true,
        )
        OMLMixinRegistry.ensureBridgeGenerated()

        val kind = OMLMixinRegistry.BridgeKind.RETURNABLE
        val bridgeDesc = OMLMixinRegistry.bridgeDesc(id, kind)
        val bridgeMethod = OMLMixinRegistry.bridgeMethodName(id, kind)
        val args = if (capturesTargetArg) {
            listOf(DslValue.Arg(0), DslValue.IntVal(id))
        } else {
            listOf(DslValue.IntVal(id))
        }
        return specOf {
            classTarget(owner) {
                method(targetName, desc = targetDesc) {
                    atHead {
                        cancellableReturn(OMLMixinRegistry.BRIDGE_CLASS, bridgeMethod, bridgeDesc, args)
                    }
                }
            }
        }
    }

    private fun targetClass(vararg methods: MethodNode): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = owner
            superName = "java/lang/Object"
            // Default constructor: the instance-method cases need to new up the target class
            this.methods.add(
                MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                    instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                    instructions.add(
                        MethodInsnNode(
                            Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false
                        )
                    )
                    instructions.add(InsnNode(Opcodes.RETURN))
                    maxStack = 1
                    maxLocals = 1
                }
            )
            this.methods.addAll(methods)
        }

    private fun methodNode(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, desc, null, null).apply {
            instructions.build()
            maxStack = 4
            maxLocals = maxOf(4, Type.getArgumentsAndReturnSizes(desc) shr 2)
        }

    private fun defineTarget(spec: InjectionSpec, method: MethodNode): Class<*> =
        define(targetClass(method), spec)

    private fun define(node: ClassNode, spec: InjectionSpec): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        // The target class must be able to see the generated bridge class — so the loader's parent is set
        // to the loader that owns the bridge class. This also proves the bridge class was really
        // defined into some loader and is resolvable.
        val parent = OMLMixinRegistry.bridgeClass?.classLoader
            ?: InjectionReturnableTest::class.java.classLoader
        return object : ClassLoader(parent) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invoke(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)
}

private fun InsnList.insn(vararg opcodes: Int) {
    for (op in opcodes) add(InsnNode(op))
}

private fun InsnList.load(slot: Int, opcode: Int) {
    add(VarInsnNode(opcode, slot))
}

private fun InsnList.store(slot: Int, opcode: Int) {
    add(VarInsnNode(opcode, slot))
}

/**
 * Test handler set.
 *
 * Shape matches **real handlers**: `(captures…, callback handle)V` — note that it does **not
 * include the bridge id**; the id exists only in the bridge method's parameter list and is consumed
 * by the bridge itself. This is one of the contracts being verified.
 */
object ReturnHandlers {
    const val OWNER: String = "org/ohmyloader/core/transformer/injection/ReturnHandlers"

    @JvmStatic
    fun returnInt(ci: CallbackInfoReturnable<Any?>) {
        ci.setReturnValue(99)
    }

    @JvmStatic
    fun returnLong(ci: CallbackInfoReturnable<Any?>) {
        ci.setReturnValue(9_000_000_000L)
    }

    @JvmStatic
    fun returnDouble(ci: CallbackInfoReturnable<Any?>) {
        ci.setReturnValue(1.5)
    }

    @JvmStatic
    fun returnString(ci: CallbackInfoReturnable<Any?>) {
        ci.setReturnValue("hooked")
    }

    @JvmStatic
    fun returnTrue(ci: CallbackInfoReturnable<Any?>) {
        ci.setReturnValue(true)
    }

    @JvmStatic
    fun leaveAlone(ci: CallbackInfoReturnable<Any?>) {
        // No cancel -> the original method body runs as usual
    }

    @JvmStatic
    fun cancelOnly(ci: CallbackInfoReturnable<Any?>) {
        ci.cancel()
    }

    /** Handler with capture (the target's 0th argument). */
    @JvmStatic
    fun timesTen(value: Int, ci: CallbackInfoReturnable<Any?>) {
        ci.setReturnValue(value * 10)
    }
}
