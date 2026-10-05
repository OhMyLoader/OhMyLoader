package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.HandlerKind
import org.ohmyloader.api.inject.HandlerVariant
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.MethodRuleBuilder
import org.ohmyloader.core.mixin.ClassMerger
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Value-rewriting **instance handlers** merged into the target class ([HandlerKind.REDIRECT] / the `MODIFY_*`
 * kinds). After the merge the handler is an **instance method**: the receiver is `this` (slot 0), supplied by
 * the `INVOKEVIRTUAL` receiver, so its parameter table holds **only values**, identical to the static form.
 * Counter-example: writing the receiver into the parameter table makes the JVM treat that slot as a **reference**
 * per the declaration while the engine pushes an int ⇒ `VerifyError: Bad local variable type`. The single
 * exception is `@Redirect`: the handler cannot obtain the replaced call's original receiver (`this` is the
 * target instance), so it is handed over as the first parameter (Mixin's `(originalReceiver, args…)R`).
 *
 * A mistake here is **silent** (nothing throws), so every case must be truly defined and truly run: [STUB]
 * records received arguments into [ModifyRecorder] and the assertion reads the input the target call observed.
 * Instance handlers must be invoked with `INVOKEVIRTUAL`: `INVOKESPECIAL` across `net.minecraft.*` classes
 * trips the package access check (`IllegalAccessError`).
 */
class InjectionHandlerModifyTest {

    // ---------- @Redirect: replace the whole call with the handler's call ----------

    @Test
    fun `instance redirect replaces the call and keeps the receiver`() {
        val clazz = build(redirectSpec())

        ModifyRecorder.recorded.clear()
        val result = clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "run" }.invoke(instance)
        }

        // The stub `Foo.value()` returns 1, and the handler returns `this.tag.length()` = 4
        // ⇒ returning 4 proves the **whole call was really replaced**, instead of the original call running
        assertEquals(
            4,
            (result as Number).toInt(),
            "the replaced call should return the value given by the handler (tag.length())",
        )
    }

    // ---------- @ModifyArg: rewrite one argument ----------

    @Test
    fun `instance modifyArg rewrites the argument`() {
        val clazz = build(modifyArgSpec())

        ModifyRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "run4" }.invoke(instance)
        }

        // The target writes sink(60), and the handler changes it to 60 * 2 + tag length ("orig" = 4) = 124
        assertEquals(listOf("sink(124)"), ModifyRecorder.recorded)
    }

    @Test
    fun `instance modifyArg keeps sibling arguments intact`() {
        val clazz = build(modifyArgSpec(second = true))

        ModifyRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "run2" }.invoke(instance)
        }

        // `run2` calls three(7, 5, 3); `index = 1` changes only the 2nd one:
        // the handler returns 1st + 3rd = 7 + 3 = 10 ⇒ the target call actually receives (7, 10, 3)
        // The 1st and 3rd go through untouched — exactly what "rewrite only one argument" should look like
        assertEquals(listOf("three(7,10,3)"), ModifyRecorder.recorded)
    }

    // ---------- @ModifyArgs: receive all arguments at once ----------

    @Test
    fun `instance modifyArgs receives a bare array and can rewrite it`() {
        val clazz = build(modifyArgsSpec())

        ModifyRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "run3" }.invoke(instance)
        }

        // The handler rewrites both entries of the Object[]: 10 → 110, 20 → 120 (each plus 100)
        assertEquals(listOf("pair(110,120)"), ModifyRecorder.recorded)
    }

    @Test
    fun `instance modifyArgs leaves the array untouched when the handler does nothing`() {
        val clazz = build(modifyArgsSpec(touch = false))

        ModifyRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "run3" }.invoke(instance)
        }

        assertEquals(listOf("pair(10,20)"), ModifyRecorder.recorded)
    }

    // ---------- @ModifyConstant: rewrite a constant ----------

    @Test
    fun `instance modifyConstant rewrites a literal`() {
        val clazz = build(modifyConstantSpec())

        ModifyRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "run4" }.invoke(instance)
        }

        // The method body writes sink(60), and the handler replaces constant 60 with 60 + tag length ("orig" = 4) = 64
        assertEquals(listOf("sink(64)"), ModifyRecorder.recorded)
    }

    // ---------- @ModifyVariable: rewrite a local variable ----------

    @Test
    fun `instance modifyVariable rewrites a local`() {
        val clazz = build(modifyVariableSpec())

        ModifyRecorder.recorded.clear()
        clazz.getDeclaredConstructor().newInstance().let { instance ->
            clazz.methods.first { it.name == "run5" }.invoke(instance)
        }

        // Local variable 5 → 5 * 3 = 15
        assertEquals(listOf("sink(15)"), ModifyRecorder.recorded)
    }

    // ---------- Shape & call instruction ----------

    @Test
    fun `instance handlers are invoked with invokevirtual`() {
        // INVOKESPECIAL trips the package access check (the handler lives in a different package)
        // ⇒ it must be INVOKEVIRTUAL.
        //
        // ⚠️ This must not go through [build]/[transformedTarget]: the former defines the class
        // (a bodyless int handler fails verification), the latter would also run the [modifyArgSpec]
        // rule. Here we only need "merge + inject once into run4", so we use a handler set that
        // **declares only the shape**: the merge moves name/descriptor into the target class, and the
        // injection point then has a callable target.
        val target = mergedTarget(signaturesOnly = true)
        specOn("run4", "()V") {
            beforeCall("net/minecraft/Foo", "sink", "(I)V", Opcodes.INVOKEVIRTUAL) {
                handlerCall(OWNER, "onArg", "(I)I", kind = HandlerKind.MODIFY_ARG)
            }
        }.transform(TransformContext(OWNER, target))

        val calls = target.methods.first { it.name == "run4" }.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
            .filter { it.name == "onArg" }

        assertTrue(calls.isNotEmpty(), "the ModifyArg injection should have produced a call to onArg")
        val call = calls.last()
        assertEquals(
            Opcodes.INVOKEVIRTUAL, call.opcode,
            "the instance handler must be invoked with INVOKEVIRTUAL (INVOKESPECIAL trips the package access check)",
        )
        assertEquals(
            OWNER,
            call.owner,
            "the handler has been merged into the target class, so the injection point should call the target class itself",
        )
        // `ALOAD 0` is the **receiver** of this INVOKEVIRTUAL (the handler's `this`), **not part of the
        // parameter table** ⇒ the table holds only the value being changed
        assertEquals("(I)I", call.desc)
    }

    @Test
    fun `modifyArg rejects a handler that declares the receiver as a parameter`() {
        // Writing an extra receiver into the parameter table ⇒ the value-parameter count no longer matches
        // (and if actually injected, the JVM would treat a "value" as a reference).
        // Only a static handler needs a receiver parameter; in the merged form the receiver is `this`.
        val target = mergedTarget()
        val spec = specOn("run4", "()V") {
            beforeCall("net/minecraft/Foo", "sink", "(I)V", Opcodes.INVOKEVIRTUAL) {
                handlerCall(OWNER, "onArg", "(L$OWNER;I)I", kind = HandlerKind.MODIFY_ARG)
            }
        }

        val failure = assertFailsWith<InjectionError> { spec.transform(TransformContext(OWNER, target)) }
        assertTrue(failure.message!!.contains("value-parameter count"), failure.message!!)
    }

    @Test
    fun `modifyArg rejects a handler whose value parameter type is wrong`() {
        // The value-parameter type must equal the type of the argument being changed. Here the return type
        // is right (`I`) but the value parameter is `long` ⇒ the single-argument shape check must reject
        // it instead of leaving a stack-type mismatch for class definition to explode on.
        // (The handler need not actually exist in the mixin: shape checking happens at emission time,
        // before "can the handler be resolved".)
        val target = mergedTarget()
        val spec = specOn("run4", "()V") {
            beforeCall("net/minecraft/Foo", "sink", "(I)V", Opcodes.INVOKEVIRTUAL) {
                handlerCall(OWNER, "onArgLong", "(J)I", kind = HandlerKind.MODIFY_ARG)
            }
        }

        val failure = assertFailsWith<InjectionError> { spec.transform(TransformContext(OWNER, target)) }
        assertTrue(failure.message!!.contains("single-argument mode"), failure.message!!)
    }

    @Test
    fun `modifyArgs rejects a handler that does not return void`() {
        // `@ModifyArgs` relies on the engine reading back that array; the handler itself returns nothing
        val target = mergedTarget()
        val spec = specOn("run3", "()V") {
            beforeCall("net/minecraft/Foo", "pair", "(II)V", Opcodes.INVOKEVIRTUAL) {
                handlerCall(OWNER, "onArgs", "([Ljava/lang/Object;)I", kind = HandlerKind.MODIFY_ARGS)
            }
        }

        val failure = assertFailsWith<InjectionError> { spec.transform(TransformContext(OWNER, target)) }
        assertTrue(failure.message!!.contains("void"), failure.message!!)
    }

    @Test
    fun `redirect rejects a handler whose descriptor does not match the replaced call`() {
        // The handler's parameter table must start with "the replaced call's receiver + its arguments";
        // here the receiver is missing ⇒ it cannot match and must be reported instead of emitting a
        // broken stack.
        val target = mergedTarget()
        val spec = specOn("run", "()I") {
            beforeCall("net/minecraft/Foo", "value", "()I", Opcodes.INVOKEVIRTUAL) {
                handlerCall(OWNER, "onCall2", "()I", kind = HandlerKind.REDIRECT)
            }
        }

        val failure = assertFailsWith<InjectionError> { spec.transform(TransformContext(OWNER, target)) }
        assertTrue(failure.message!!.contains("receiver + the replaced call's arguments"), failure.message!!)
    }

    @Test
    fun `redirect rejects a handler targeting a static call`() {
        // A static call has no receiver, yet a redirect handler must take one — reject it outright.
        //
        // `run6` holds a **real** `INVOKESTATIC`; the anchor declares `opcode = INVOKESTATIC` to match it
        // (if the anchor were wrong it would hit 0 times, and this case would pass falsely because
        // "nothing to report" — so the target method must really contain that static call).
        val target = mergedTarget()
        val spec = specOn("run6", "()I") {
            beforeCall("net/minecraft/Foo", "staticValue", "()I", Opcodes.INVOKESTATIC) {
                handlerCall(OWNER, "onCall2", "(Lnet/minecraft/Foo;)I", kind = HandlerKind.REDIRECT)
            }
        }

        val failure = assertFailsWith<InjectionError> { spec.transform(TransformContext(OWNER, target)) }
        assertTrue(failure.message!!.contains("static call"), failure.message!!)
    }

    @Test
    fun `temp slots handed out by the engine never collide`() {
        // `allocLocalSlot` does not merely "look up the next free slot"; it must **claim it immediately**:
        // query-without-claiming would give a second injection on the same method the same slot, piling
        // two unrelated values into one slot.
        // Such inconsistency only surfaces under the **frame-preserving** write-back mode (the production
        // path uses `COMPUTE_MAXS` ⇒ `VerifyError`), so this case asserts the allocation result directly
        // rather than relying on "explodes at class definition".
        val method = MethodNode(Opcodes.ACC_PUBLIC, "m", "()V", null, null)
        method.maxLocals = 1

        assertEquals(1, PayloadEmitter.allocLocalSlot(method))
        assertEquals(
            2,
            PayloadEmitter.allocLocalSlot(method),
            "a slot handed out must be claimed immediately, otherwise the next injection would get the same one",
        )
        assertEquals(3, PayloadEmitter.allocLocalSlot(method, size = 2), "a long/double occupies two slots")
    }

    @Test
    fun `an entry inject and a ModifyConstant on one method use different slots`() {
        // The entry short-circuit (`@Inject` cancellable) needs a temp slot for the callback handle,
        // and a value kind (`@ModifyConstant`) also needs one to hold "the constant already on the
        // stack". When both hang on the same method, they **must get different slots** — this is exactly
        // the combination that blew up in the real game.
        //
        // ⚠️ Order matters: the **value kind must come first** — query-without-claiming only collides
        // when "the side that grabs a slot first does not claim it"; reversed (entry first, and it
        // claims), there is no collision and the test would be pointless.
        val target = mergedTarget()
        specOn("run4", "()V") {
            afterConstant(60) {
                handlerCall(OWNER, "onConst", "(I)I", kind = HandlerKind.MODIFY_CONST)
            }
            atHead {
                handlerCall(
                    OWNER, "onCall2", "(Lorg/ohmyloader/api/mixin/CallbackInfo;)V",
                    variant = HandlerVariant.CANCELLABLE,
                )
            }
        }.transform(TransformContext(OWNER, target))

        val stores = target.methods.first { it.name == "run4" }.instructions.toArray()
            .filterIsInstance<VarInsnNode>()
            .filter { it.opcode == Opcodes.ASTORE || it.opcode == Opcodes.ISTORE }
            .map { it.`var` }

        assertTrue(stores.size >= 2, "each injection should have its own store: $stores")
        assertEquals(stores.size, stores.toSet().size, "the temp slots of the two injections must not collide: $stores")
    }

    // ---------- Specs ----------

    private fun redirectSpec(): InjectionSpec = specOf {
        classTarget(OWNER) {
            method("run", desc = "()I") {
                beforeCall("net/minecraft/Foo", "value", "()I", Opcodes.INVOKEVIRTUAL) {
                    handlerCall(
                        OWNER, "onCall2", "(Lnet/minecraft/Foo;)I",
                        kind = HandlerKind.REDIRECT,
                    )
                }
            }
        }
    }

    private fun modifyArgSpec(second: Boolean = false): InjectionSpec = specOf {
        classTarget(OWNER) {
            if (second) {
                method("run2", desc = "()V") {
                    beforeCall("net/minecraft/Foo", "three", "(III)V", Opcodes.INVOKEVIRTUAL) {
                        handlerCall(OWNER, "onArg2", "(III)I", kind = HandlerKind.MODIFY_ARG, index = 1)
                    }
                }
            } else {
                // The anchor must land on a method that **really calls `Foo.sink`** — `run4` contains
                // `sink(60)`. (`run` calls `Foo.value`; an anchor there would hit 0 times and inject nothing.)
                method("run4", desc = "()V") {
                    beforeCall("net/minecraft/Foo", "sink", "(I)V", Opcodes.INVOKEVIRTUAL) {
                        handlerCall(OWNER, "onArg", "(I)I", kind = HandlerKind.MODIFY_ARG)
                    }
                }
            }
        }
    }

    private fun modifyArgsSpec(touch: Boolean = true): InjectionSpec = specOf {
        classTarget(OWNER) {
            method("run3", desc = "()V") {
                beforeCall("net/minecraft/Foo", "pair", "(II)V", Opcodes.INVOKEVIRTUAL) {
                    handlerCall(
                        OWNER, if (touch) "onArgs" else "onArgsNoop", "([Ljava/lang/Object;)V",
                        kind = HandlerKind.MODIFY_ARGS,
                    )
                }
            }
        }
    }

    private fun modifyConstantSpec(): InjectionSpec = specOf {
        classTarget(OWNER) {
            method("run4", desc = "()V") {
                afterConstant(60) {
                    handlerCall(OWNER, "onConst", "(I)I", kind = HandlerKind.MODIFY_CONST)
                }
            }
        }
    }

    private fun modifyVariableSpec(): InjectionSpec = specOf {
        classTarget(OWNER) {
            method("run5", desc = "()V") {
                // The anchor is directly "after slot 1 is written" — `@ModifyVariable` changes exactly **that
                // local**, the value sits in the slot, and reading it out, changing it, and writing it back
                // is enough (no need for [beforeCall] to narrow the scope).
                afterStore(index = 1, type = "I") {
                    handlerCall(OWNER, "onVar", "(I)I", kind = HandlerKind.MODIFY_VAR)
                }
            }
        }
    }

    // ---------- Scaffolding ----------

    /** Merge + inject (surfacing `VerifyError` here), returning the transformed target class. */
    private fun transformedTarget(spec: InjectionSpec): ClassNode {
        val target = mergedTarget()
        spec.transform(TransformContext(OWNER, target))
        return target
    }

    /**
     * Merge only, no injection — for cases that are **supposed to be rejected**.
     *
     * These cases require `spec.transform` to throw `InjectionError` itself; if it were injected
     * first, the throw would happen at the preceding legal call and the assertion would become
     * "the error happened to throw too".
     *
     * With [signaturesOnly] the handlers have **only declarations, no method body**: used to check
     * **shape** questions like "is the injection point an `INVOKEVIRTUAL` calling the target class
     * itself" — such cases never really run code, so a bodyless int handler (which fails
     * verification) does not interfere.
     */
    private fun mergedTarget(signaturesOnly: Boolean = false): ClassNode {
        val target = targetClass()
        val result = ClassMerger.merge(mixinCandidate(signaturesOnly), target, mutableMapOf())
        assertEquals(emptyList(), result.problems, result.problems.toString())
        return target
    }

    /**
     * Hang a single rule on one method — to set up the shape for **should-be-rejected** cases.
     *
     * [block] writes `atHead { ... }` / `beforeCall(...) { ... }` in full; do not try to wrap another
     * layer of "lambda taking `MethodRuleBuilder`": `classTarget { method(...) { } }` is itself a
     * receiver-bearing lambda nest, and one more forwarding layer only loses the DSL receiver.
     */
    private fun specOn(methodName: String, methodDesc: String, block: MethodRuleBuilder.() -> Unit): InjectionSpec =
        specOf {
            classTarget(OWNER) {
                method(methodName, desc = methodDesc) {
                    block(this)
                }
            }
        }

    /** Merge + inject → write back → define (the target class together with the [STUB], one ClassLoader). */
    private fun build(spec: InjectionSpec): Class<*> {
        val target = transformedTarget(spec)
        return object : ClassLoader(InjectionHandlerModifyTest::class.java.classLoader) {
            fun defineTarget(): Class<*> {
                val stub = stubBytes()
                defineClass(STUB.replace('/', '.'), stub, 0, stub.size)
                val writer = frameWriter()
                target.accept(writer)
                val bytes = writer.toByteArray()
                return defineClass(OWNER.replace('/', '.'), bytes, 0, bytes.size)
            }
        }.defineTarget()
    }

    /**
     * The `ClassWriter` used to write back the target class — **must match the game path**
     * (`COMPUTE_MAXS`, see `OMLClassLoader`). **`COMPUTE_FRAMES` must not be used**: recomputing frames
     * would **hide** errors like "a local slot added by injection is inconsistent with the existing
     * `StackMapTable`" (the same bytecode writes back all-green with `COMPUTE_FRAMES` but `VerifyError`s
     * on the production path), so the test's write-back must replicate production.
     */
    private fun frameWriter(): ClassWriter = object :
        ClassWriter(COMPUTE_MAXS) {
        override fun getClassLoader(): ClassLoader = InjectionHandlerModifyTest::class.java.classLoader
    }

    /**
     * The stub for the "game method" called by the target class — `class net.minecraft.Foo`.
     *
     * It must really be defined and **record received arguments into [ModifyRecorder]**: a value kind
     * that "changes the wrong thing" is silent, and only asserting "what the target call actually
     * received" can catch it.
     * (`@Redirect` replaces the whole call, so only the non-redirect cases actually reach here — but
     * as long as **one** path reaches it, class loading must be able to resolve it, otherwise
     * `NoClassDefFoundError`.)
     */
    private fun stubBytes(): ByteArray {
        val node = ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = STUB
            superName = "java/lang/Object"
            methods.add(
                MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                    instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                    instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
                    instructions.add(InsnNode(Opcodes.RETURN))
                    maxStack = 1
                    maxLocals = 1
                },
            )
            // int value() — constant 1; the redirect cases use "return not 1" to prove the call was really replaced
            methods.add(
                MethodNode(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
                    instructions.add(InsnNode(Opcodes.ICONST_1))
                    instructions.add(InsnNode(Opcodes.IRETURN))
                    maxStack = 1
                    maxLocals = 1
                },
            )
            // static int staticValue() — only used as the anchor for "an instance handler cannot target a static call"
            methods.add(
                MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "staticValue", "()I", null, null).apply {
                    instructions.add(InsnNode(Opcodes.ICONST_2))
                    instructions.add(InsnNode(Opcodes.IRETURN))
                    maxStack = 1
                    maxLocals = 0
                },
            )
            // void sink(int) / three(int,int,int) / pair(int,int) — record the received arguments
            for ([n, d] in listOf("sink" to "(I)V", "three" to "(III)V", "pair" to "(II)V")) {
                val args = org.objectweb.asm.Type.getArgumentTypes(d)
                methods.add(
                    MethodNode(Opcodes.ACC_PUBLIC, n, d, null, null).apply {
                        var slot = 1
                        for (t in args) {
                            instructions.add(VarInsnNode(Opcodes.ILOAD, slot))
                            slot += t.size
                        }
                        instructions.add(
                            MethodInsnNode(
                                Opcodes.INVOKESTATIC, RECORDER, n,
                                "(${args.joinToString("") { it.descriptor }})V", false,
                            ),
                        )
                        instructions.add(InsnNode(Opcodes.RETURN))
                        maxStack = args.size
                        maxLocals = slot
                    },
                )
            }
        }
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    /**
     * The target class: `class ModifyTarget { private String tag = "orig"; … }`
     *
     * Each method calls `net/minecraft.Foo` once — the engine only looks at **instruction shape**,
     * and the stub is provided by [stubBytes].
     */
    private fun targetClass(): ClassNode = classNode(OWNER) {
        // run()I: returns the int from Foo.value() (replaced wholesale by @Redirect)
        method("run", "()I") {
            it.add(VarInsnNode(Opcodes.ALOAD, 0))
            it.add(FieldInsnNode(Opcodes.GETFIELD, OWNER, "foo", "L$STUB;"))
            it.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, STUB, "value", "()I", false))
            it.add(InsnNode(Opcodes.IRETURN))
        }
        // run2()V: Foo.three(7, 5, 3) — @ModifyArg(index = 1) changes only the 2nd one
        method("run2", "()V") {
            it.add(VarInsnNode(Opcodes.ALOAD, 0))
            it.add(FieldInsnNode(Opcodes.GETFIELD, OWNER, "foo", "L$STUB;"))
            it.add(IntInsnNode(Opcodes.BIPUSH, 7))
            it.add(IntInsnNode(Opcodes.BIPUSH, 5))
            it.add(IntInsnNode(Opcodes.BIPUSH, 3))
            it.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, STUB, "three", "(III)V", false))
            it.add(InsnNode(Opcodes.RETURN))
        }
        // run3()V: Foo.pair(10, 20) — @ModifyArgs takes the Object[] and rewrites it as a whole
        method("run3", "()V") {
            it.add(VarInsnNode(Opcodes.ALOAD, 0))
            it.add(FieldInsnNode(Opcodes.GETFIELD, OWNER, "foo", "L$STUB;"))
            it.add(IntInsnNode(Opcodes.BIPUSH, 10))
            it.add(IntInsnNode(Opcodes.BIPUSH, 20))
            it.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, STUB, "pair", "(II)V", false))
            it.add(InsnNode(Opcodes.RETURN))
        }
        // run4()V: Foo.sink(60) — 60 is both the target of @ModifyArg and @ModifyConstant
        method("run4", "()V") {
            it.add(VarInsnNode(Opcodes.ALOAD, 0))
            it.add(FieldInsnNode(Opcodes.GETFIELD, OWNER, "foo", "L$STUB;"))
            it.add(IntInsnNode(Opcodes.BIPUSH, 60))
            it.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, STUB, "sink", "(I)V", false))
            it.add(InsnNode(Opcodes.RETURN))
        }
        // run5()V: local slot 1 stores 5, read out and handed to Foo.sink — @ModifyVariable changes this slot
        //
        // There must be a real `ISTORE` here: `@ModifyVariable`'s anchor is "after the local variable is
        // written", and pushing `5` straight onto the stack as an argument would have **no** store
        // instruction.
        method("run5", "()V") {
            it.add(IntInsnNode(Opcodes.BIPUSH, 5))
            it.add(VarInsnNode(Opcodes.ISTORE, 1))
            it.add(VarInsnNode(Opcodes.ALOAD, 0))
            it.add(FieldInsnNode(Opcodes.GETFIELD, OWNER, "foo", "L$STUB;"))
            it.add(VarInsnNode(Opcodes.ILOAD, 1))
            it.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, STUB, "sink", "(I)V", false))
            it.add(InsnNode(Opcodes.RETURN))
        }
        // run6()I: Foo.staticValue() — the only **static** call
        method("run6", "()I") {
            it.add(MethodInsnNode(Opcodes.INVOKESTATIC, STUB, "staticValue", "()I", false))
            it.add(InsnNode(Opcodes.IRETURN))
        }
    }

    /**
     * Lay out a class skeleton: `<init>` sets `tag` to `"orig"` and `foo` to a new object; the rest
     * of the methods are left to [block].
     *
     * Use the nested `method(...)` rather than a bare `MethodNode` — the latter's
     * `Type.getArgumentTypes` assigns **one slot per parameter** by descriptor, so repeated
     * reference types would make the local slot numbers disagree with the source `iload_N`
     * (`VerifyError`). Just computing slot numbers faithfully from the descriptor is simplest.
     */
    private inline fun classNode(name: String, block: ClassNode.() -> Unit): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            this.name = name
            superName = "java/lang/Object"
            fields.add(FieldNode(Opcodes.ACC_PRIVATE, "tag", "Ljava/lang/String;", null, null))
            fields.add(FieldNode(Opcodes.ACC_PRIVATE, "foo", "L$STUB;", null, null))
            methods.add(
                MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
                    instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                    instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
                    instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                    instructions.add(LdcInsnNode("orig"))
                    instructions.add(FieldInsnNode(Opcodes.PUTFIELD, name, "tag", "Ljava/lang/String;"))
                    instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                    instructions.add(TypeInsnNode(Opcodes.NEW, STUB))
                    instructions.add(InsnNode(Opcodes.DUP))
                    instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, STUB, "<init>", "()V", false))
                    instructions.add(FieldInsnNode(Opcodes.PUTFIELD, name, "foo", "L$STUB;"))
                    instructions.add(InsnNode(Opcodes.RETURN))
                    maxStack = 3
                    maxLocals = 1
                },
            )
            block()
        }

    /** Append a method inside [classNode]'s block; parameter slot numbers are computed from the descriptor (see [classNode]'s note). */
    private inline fun ClassNode.method(
        name: String,
        desc: String,
        access: Int = Opcodes.ACC_PUBLIC,
        block: (InsnList) -> Unit,
    ) {
        val node = MethodNode(access, name, desc, null, null)
        node.maxStack = 8
        node.maxLocals = 2 + org.objectweb.asm.Type.getArgumentTypes(desc).sumOf { it.size }
        block(node.instructions)
        methods.add(node)
    }

    /**
     * The merge candidate: `@Shadow tag` + seven value-kind **instance** handlers.
     *
     * The handler body reads `GETFIELD ModifyMixin.tag` (the mixin's own name) — the self-reference
     * rewrite at merge time turns it into `GETFIELD ModifyTarget.tag`, so "the handler really runs on
     * the target instance" shows up in the string it reads back (`"orig"`).
     */
    private fun mixinCandidate(signaturesOnly: Boolean = false): ClassMerger.MixinClass = ClassMerger.MixinClass(
        modId = "testmod",
        className = MIXIN.replace('/', '.'),
        targetInternal = OWNER,
        node = mixin(signaturesOnly),
        handlerMethods = HANDLERS,
    )

    private fun mixin(signaturesOnly: Boolean = false): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = MIXIN
        superName = "java/lang/Object"
        fields.add(
            FieldNode(Opcodes.ACC_PRIVATE, "tag", "Ljava/lang/String;", null, null).apply {
                visibleAnnotations = listOf(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/mixin/Shadow;"))
            },
        )

        if (signaturesOnly) {
            // Declare only the shape — used to verify the injection point's call shape, without running code.
            //
            // The body **must not be empty**: the merger treats "no instruction body" as "forgot to
            // write @Shadow" and reports a problem. So give it a minimal legal body (return a default
            // value); this path never actually executes.
            for ([n, d] in listOf(
                "onCall2" to "(L$STUB;)I",
                "onArg" to "(I)I",
                "onArg2" to "(III)I",
                "onArgs" to "([Ljava/lang/Object;)V",
                "onArgsNoop" to "([Ljava/lang/Object;)V",
                "onConst" to "(I)I",
                "onVar" to "(I)I",
            )) {
                methods.add(
                    MethodNode(Opcodes.ACC_PRIVATE, n, d, null, null).apply {
                        val ret = org.objectweb.asm.Type.getReturnType(d)
                        if (ret == org.objectweb.asm.Type.VOID_TYPE) {
                            instructions.add(InsnNode(Opcodes.RETURN))
                        } else {
                            instructions.add(InsnNode(Opcodes.ICONST_0))
                            instructions.add(InsnNode(Opcodes.IRETURN))
                        }
                        maxStack = 1
                        maxLocals = 1 + org.objectweb.asm.Type.getArgumentTypes(d).sumOf { it.size }
                    },
                )
            }
            return@apply
        }

        // @Redirect: replaces the return value of the replaced call with `this.tag.length()`
        //
        // `Foo` in the parameter table is the **replaced call's receiver** (not `this`) — the handler
        // may need it to perform that call, so the engine passes it as the first parameter. This case
        // does not use it, but the signature must match.
        methods.add(
            instance("onCall2", "(L$STUB;)I") {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(FieldInsnNode(Opcodes.GETFIELD, MIXIN, "tag", "Ljava/lang/String;"))
                add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false))
                add(InsnNode(Opcodes.IRETURN))
            },
        )

        // @ModifyArg single argument: value * 2 + tag.length() — slot 1 holds that value (slot 0 is this)
        methods.add(
            instance("onArg", "(I)I") {
                add(VarInsnNode(Opcodes.ILOAD, 1))
                add(InsnNode(Opcodes.ICONST_2))
                add(InsnNode(Opcodes.IMUL))
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(FieldInsnNode(Opcodes.GETFIELD, MIXIN, "tag", "Ljava/lang/String;"))
                add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false))
                add(InsnNode(Opcodes.IADD))
                add(InsnNode(Opcodes.IRETURN))
            },
        )

        // @ModifyArg multi-argument: returns 1st + 3rd (proving sibling arguments are readable and only the specified one is changed)
        methods.add(
            instance("onArg2", "(III)I") {
                add(VarInsnNode(Opcodes.ILOAD, 1))
                add(VarInsnNode(Opcodes.ILOAD, 3))
                add(InsnNode(Opcodes.IADD))
                add(InsnNode(Opcodes.IRETURN))
            },
        )

        // @ModifyArgs: adds 100 to both entries of the Object[] (bare array, slot 1)
        methods.add(
            instance("onArgs", "([Ljava/lang/Object;)V") {
                for (i in 0..1) {
                    listAddBox100(this, i)
                }
                add(InsnNode(Opcodes.RETURN))
            },
        )
        // Changes nothing but is still read back by the engine — proves the original values pass through
        // untouched when the handler does not change them
        methods.add(
            instance("onArgsNoop", "([Ljava/lang/Object;)V") {
                add(InsnNode(Opcodes.RETURN))
            },
        )

        // @ModifyConstant: value + tag.length()
        methods.add(
            instance("onConst", "(I)I") {
                add(VarInsnNode(Opcodes.ILOAD, 1))
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(FieldInsnNode(Opcodes.GETFIELD, MIXIN, "tag", "Ljava/lang/String;"))
                add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false))
                add(InsnNode(Opcodes.IADD))
                add(InsnNode(Opcodes.IRETURN))
            },
        )

        // @ModifyVariable: value * 3
        methods.add(
            instance("onVar", "(I)I") {
                add(VarInsnNode(Opcodes.ILOAD, 1))
                add(InsnNode(Opcodes.ICONST_3))
                add(InsnNode(Opcodes.IMUL))
                add(InsnNode(Opcodes.IRETURN))
            },
        )
    }

    /** `array[i] = (Integer) array[i] + 100` — used by `onArgs`. */
    private fun listAddBox100(body: InsnList, index: Int) {
        with(body) {
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(InsnNode(if (index == 0) Opcodes.ICONST_0 else Opcodes.ICONST_1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(InsnNode(if (index == 0) Opcodes.ICONST_0 else Opcodes.ICONST_1))
            add(InsnNode(Opcodes.AALOAD))
            add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"))
            add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false))
            add(IntInsnNode(Opcodes.BIPUSH, 100))
            add(InsnNode(Opcodes.IADD))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
            add(InsnNode(Opcodes.AASTORE))
        }
    }

    /**
     * Build an "instance handler" method node.
     *
     * [desc] does **not include the receiver** — after merging into the target class the handler is
     * an instance method, so the receiver is `this` (slot 0).
     */
    private fun instance(
        name: String,
        desc: String,
        access: Int = Opcodes.ACC_PRIVATE,
        body: InsnList.() -> Unit,
    ): MethodNode = MethodNode(access, name, desc, null, null).apply {
        instructions.body()
        maxStack = 6
        maxLocals = 6
    }
}

private const val RECORDER = "org/ohmyloader/core/transformer/injection/ModifyRecorder"

/** The "game method" stub called by the target class (see `stubBytes()`). */
private const val STUB = "net/minecraft/Foo"

/** The target class's internal name; after member merging the handler's `this` is its instance. */
private const val OWNER = "omltest/ModifyTarget"

/** The merge candidate's own internal name. */
private const val MIXIN = "omltest/ModifyMixin"

/**
 * A handler must **keep its name + be promoted to public** to be callable by the injection point's
 * `INVOKEVIRTUAL`, and its parameter table must **exclude the receiver**: after merging into the target
 * class the handler is an **instance method**, the receiver is `this` (slot 0) supplied by the
 * `INVOKEVIRTUAL` receiver — putting it in the parameter table would make the JVM treat a "value" as a
 * reference (`VerifyError`). This matches Mixin's convention (`@ModifyArg` et al. parameter tables hold
 * only values). The sole exception is `@Redirect`: the **replaced call's original receiver** (here
 * `net/minecraft/Foo`) must be the first parameter — `this` is the target instance and cannot obtain it.
 */
private val HANDLERS = setOf(
    "onCall2(L$STUB;)I",
    "onArg(I)I",
    "onArg2(III)I",
    "onArgs([Ljava/lang/Object;)V",
    "onArgsNoop([Ljava/lang/Object;)V",
    "onConst(I)I",
    "onVar(I)I",
)

/**
 * Observation table: **the arguments the target call actually received** are written here.
 *
 * Deliberately recorded in the stub rather than the handler — a value kind that "changes the wrong
 * thing" throws nothing, and only asserting "what the caller finally saw" can catch it. Whatever the
 * handler computed itself is irrelevant; what matters is what the target call received.
 */
object ModifyRecorder {
    val recorded = mutableListOf<String>()

    @JvmStatic
    fun sink(value: Int) {
        recorded += "sink($value)"
    }

    @JvmStatic
    fun three(first: Int, second: Int, third: Int) {
        recorded += "three($first,$second,$third)"
    }

    @JvmStatic
    fun pair(first: Int, second: Int) {
        recorded += "pair($first,$second)"
    }
}
