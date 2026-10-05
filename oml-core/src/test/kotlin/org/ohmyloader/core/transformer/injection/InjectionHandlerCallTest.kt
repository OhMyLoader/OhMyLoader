package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.HandlerVariant
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.Payload
import org.ohmyloader.core.mixin.ClassMerger
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.*

/**
 * **Instance handlers merged into the target class** ([Payload.HandlerCall]) — the real Mixin
 * `this`-semantics. The promise is one sentence: **`this.<@Shadow field>` in the handler body reads the
 * target instance's field**. That holds only when the "merge + inject" chains are both correctly wired,
 * and failure is silent (reads null / 0, no exception), so every case must **really define the class and
 * really call it**, asserting on "what the handler actually read". One case per path: notifier (reads a
 * `@Shadow` field) / cancellable (short-circuits the method body) / returner (replaces the return
 * value); under the merge model `this` is the target instance and the injection point a single
 * `INVOKEVIRTUAL`.
 */
class InjectionHandlerCallTest {

    private val owner = "omltest/HookTarget"
    private val mixinName = "omltest/HookMixin"

    private val callbackInfo = "Lorg/ohmyloader/api/mixin/CallbackInfo;"
    private val callbackReturnable = "Lorg/ohmyloader/api/mixin/CallbackInfoReturnable;"

    // ---------- Notifier: the handler reads this.<@Shadow field> ----------

    @Test
    fun `merged handler reads the target field through this`() {
        val clazz = build(notifySpec())

        HandlerRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "greet" }.invoke(instance)
        }

        // The handler reads the **target instance's** tag ("orig") — not the @Shadow field on the
        // mixin instance, which is always null
        assertEquals(listOf("orig"), HandlerRecorder.recorded)
    }

    @Test
    fun `handler method is merged with public access`() {
        // Handlers are often written `private`, and invokevirtual cannot call a private method
        // (VerifyError) ⇒ the access flag is promoted at merge time
        val target = targetClass()
        ClassMerger.merge(mixinCandidate(), target, mutableMapOf())

        val handler = target.methods.first { it.name == "onGreet" }
        assertTrue(
            handler.access and Opcodes.ACC_PUBLIC != 0,
            "the handler's access flags should be raised to public: ${handler.access}",
        )
        assertEquals(0, handler.access and Opcodes.ACC_PRIVATE)
    }

    @Test
    fun `handler name is kept even when it could be uniquified`() {
        // The injection point hard-codes the handler name ⇒ the merge must not rename it (renaming =
        // runtime NoSuchMethodError)
        val target = targetClass()
        val result = ClassMerger.merge(mixinCandidate(), target, mutableMapOf())

        assertEquals(emptyList(), result.problems, result.problems.toString())
        assertNotNull(target.methods.firstOrNull { it.name == "onGreet" })
    }

    @Test
    fun `handler colliding with a target method is reported`() {
        val target = targetClass().apply {
            methods.add(
                MethodNode(Opcodes.ACC_PUBLIC, "onGreet", "($callbackInfo)V", null, null).apply {
                    instructions.add(InsnNode(Opcodes.RETURN))
                    maxStack = 0
                    maxLocals = 2
                },
            )
        }

        val result = ClassMerger.merge(mixinCandidate(), target, mutableMapOf())

        assertTrue(result.problems.single().contains("cannot be auto-renamed"), result.problems.toString())
    }

    // ---------- Cancellable: short-circuits the method body ----------

    @Test
    fun `cancellable handler short circuits the target body`() {
        val clazz = build(cancellableSpec())

        HandlerRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "bump" }.invoke(instance)
        }

        // The handler calls ci.cancel() ⇒ Recorder.body() in the method body must not run
        assertEquals(emptyList(), HandlerRecorder.recorded)
    }

    @Test
    fun `cancellable handler that does not cancel lets the body run`() {
        val clazz = build(cancellableSpec(cancel = false))

        HandlerRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "bump" }.invoke(instance)
        }

        assertEquals(listOf("body"), HandlerRecorder.recorded)
    }

    // ---------- Returner: replaces the return value ----------

    @Test
    fun `returnable handler replaces the return value`() {
        val clazz = build(returnableSpec())

        HandlerRecorder.recorded.clear()
        val result = clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "count" }.invoke(instance)
        }

        assertEquals(42, result, "the handler's setReturnValue(42) should make the target method return 42 directly")
    }

    @Test
    fun `returnable handler that does not cancel keeps the original value`() {
        val clazz = build(returnableSpec(setValue = false))

        val result = clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "count" }.invoke(instance)
        }

        assertEquals(7, result)
    }

    // ---------- Static target methods: rejected ----------

    @Test
    fun `injecting a static target is rejected`() {
        val spec = specOf {
            classTarget(owner) {
                method("staticGreet", desc = "()V") {
                    atHead { handlerCall(owner, "onGreet", "($callbackInfo)V") }
                }
            }
        }
        val target = targetClass().apply {
            methods.add(
                MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "staticGreet", "()V", null, null).apply {
                    instructions.add(InsnNode(Opcodes.RETURN))
                    maxStack = 0
                    maxLocals = 0
                },
            )
        }
        ClassMerger.merge(mixinCandidate(), target, mutableMapOf())

        val failure = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, target))
        }

        assertTrue(failure.message!!.contains("instance methods"), failure.message!!)
    }

    // ---------- Scaffolding ----------

    private fun notifySpec(): InjectionSpec = specOf {
        classTarget(owner) {
            method("greet", desc = "()Ljava/lang/String;") {
                atHead { handlerCall(owner, "onGreet", "($callbackInfo)V") }
            }
        }
    }

    private fun cancellableSpec(cancel: Boolean = true): InjectionSpec = specOf {
        classTarget(owner) {
            method("bump", desc = "()V") {
                atHead {
                    handlerCall(
                        owner, if (cancel) "onBumpCancel" else "onBumpPass",
                        "($callbackInfo)V", variant = HandlerVariant.CANCELLABLE,
                    )
                }
            }
        }
    }

    private fun returnableSpec(setValue: Boolean = true): InjectionSpec = specOf {
        classTarget(owner) {
            method("count", desc = "()I") {
                atHead {
                    handlerCall(
                        owner, if (setValue) "onCount" else "onCountPass",
                        "($callbackReturnable)V", variant = HandlerVariant.RETURNABLE,
                    )
                }
            }
        }
    }

    /** Merge + inject → write back → define. `VerifyError`/`AbstractMethodError` both surface here. */
    private fun build(spec: InjectionSpec): Class<*> {
        val target = targetClass()
        val result = ClassMerger.merge(mixinCandidate(), target, mutableMapOf())
        assertEquals(emptyList(), result.problems, result.problems.toString())
        spec.transform(TransformContext(owner, target))

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        target.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionHandlerCallTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    /**
     * The target class: `class HookTarget { private String tag = "orig"; String greet(); void bump();
     * int count(); }`
     *
     * `greet()` returns a constant (no side effects); the short-circuit cases watch whether
     * `Recorder.body()` inside the `bump()` body runs.
     */
    private fun targetClass(): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = owner
        superName = "java/lang/Object"
        fields.add(FieldNode(Opcodes.ACC_PRIVATE, "tag", "Ljava/lang/String;", null, null))
        methods.add(
            MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
                instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                instructions.add(LdcInsnNode("orig"))
                instructions.add(FieldInsnNode(Opcodes.PUTFIELD, owner, "tag", "Ljava/lang/String;"))
                instructions.add(InsnNode(Opcodes.RETURN))
                maxStack = 2
                maxLocals = 1
            },
        )
        methods.add(
            instance(
                "greet",
                "()Ljava/lang/String;",
            ) { add(LdcInsnNode("hello")); add(InsnNode(Opcodes.ARETURN)) },
        )
        methods.add(
            instance("bump", "()V") {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "body", "()V", false))
                add(InsnNode(Opcodes.RETURN))
            },
        )
        methods.add(instance("count", "()I") { add(IntInsnNode(Opcodes.BIPUSH, 7)); add(InsnNode(Opcodes.IRETURN)) })
    }

    /**
     * The merge candidate: registers the mixin's **instance handlers** as "referenced by injected
     * rules".
     *
     * Registering makes the merge do two things (without which the injection point would be
     * `NoSuchMethodError`/`VerifyError`): keep the name and promote the access flag to public — see
     * [ClassMerger.MixinClass.handlerMethods].
     */
    private fun mixinCandidate(): ClassMerger.MixinClass = ClassMerger.MixinClass(
        modId = "testmod",
        className = mixinName.replace('/', '.'),
        targetInternal = owner,
        node = mixin(),
        handlerMethods = setOf(
            "onGreet($callbackInfo)V",
            "onBumpCancel($callbackInfo)V",
            "onBumpPass($callbackInfo)V",
            "onCount($callbackReturnable)V",
            "onCountPass($callbackReturnable)V",
        ),
    )

    /**
     * The mixin class: `@Shadow tag` + four instance handlers.
     *
     * The handler bodies write `GETFIELD HookMixin.tag` (the mixin's own name) — the self-reference
     * rewrite at merge time turns it into `GETFIELD HookTarget.tag`, which is exactly the source of
     * "@Shadow fields really are readable".
     */
    private fun mixin(): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = mixinName
        superName = "java/lang/Object"
        // @Shadow field: only declares "the target class has it", not merged in
        fields.add(
            FieldNode(Opcodes.ACC_PRIVATE, "tag", "Ljava/lang/String;", null, null).apply {
                visibleAnnotations = listOf(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/mixin/Shadow;"))
            },
        )
        // Notifier: reads this.tag (through the @Shadow field) and records it — proving the handler's
        // `this` is the target instance
        methods.add(
            instance("onGreet", "($callbackInfo)V", Opcodes.ACC_PRIVATE) {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(FieldInsnNode(Opcodes.GETFIELD, mixinName, "tag", "Ljava/lang/String;"))
                add(MethodInsnNode(Opcodes.INVOKESTATIC, RECORDER, "record", "(Ljava/lang/String;)V", false))
                add(InsnNode(Opcodes.RETURN))
            },
        )
        // Cancellable: ci.cancel()
        methods.add(
            instance("onBumpCancel", "($callbackInfo)V", Opcodes.ACC_PRIVATE) {
                add(VarInsnNode(Opcodes.ALOAD, 1))
                add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, CALLBACK_INFO, "cancel", "()V", false))
                add(InsnNode(Opcodes.RETURN))
            },
        )
        // Cancellable but does not cancel: does nothing
        methods.add(
            instance("onBumpPass", "($callbackInfo)V", Opcodes.ACC_PRIVATE) { add(InsnNode(Opcodes.RETURN)) },
        )
        // Returner: cir.setReturnValue(42)
        methods.add(
            instance("onCount", "($callbackReturnable)V", Opcodes.ACC_PRIVATE) {
                add(VarInsnNode(Opcodes.ALOAD, 1))
                add(IntInsnNode(Opcodes.BIPUSH, 42))
                add(
                    MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        "java/lang/Integer",
                        "valueOf",
                        "(I)Ljava/lang/Integer;",
                        false,
                    ),
                )
                add(
                    MethodInsnNode(
                        Opcodes.INVOKEVIRTUAL, CALLBACK_RETURNABLE, "setReturnValue",
                        "(Ljava/lang/Object;)V", false,
                    ),
                )
                add(InsnNode(Opcodes.RETURN))
            },
        )
        // Returner but does not set a value: does nothing
        methods.add(
            instance("onCountPass", "($callbackReturnable)V", Opcodes.ACC_PRIVATE) { add(InsnNode(Opcodes.RETURN)) },
        )
    }

    private fun instance(
        name: String,
        desc: String,
        access: Int = Opcodes.ACC_PUBLIC,
        body: InsnList.() -> Unit,
    ): MethodNode = MethodNode(access, name, desc, null, null).apply {
        instructions.body()
        maxStack = 4
        maxLocals = 4
    }

    private companion object {
        const val RECORDER = "org/ohmyloader/core/transformer/injection/HandlerRecorder"
        const val CALLBACK_INFO = "org/ohmyloader/api/mixin/CallbackInfo"
        const val CALLBACK_RETURNABLE = "org/ohmyloader/api/mixin/CallbackInfoReturnable"
    }
}

/** Observation table: the handler body (running inside the target class) writes here, and the cases assert "what it actually read". */
object HandlerRecorder {
    val recorded = mutableListOf<String>()

    @JvmStatic
    fun record(value: String?) {
        recorded += value ?: "null"
    }

    @JvmStatic
    fun body() {
        recorded += "body"
    }
}
