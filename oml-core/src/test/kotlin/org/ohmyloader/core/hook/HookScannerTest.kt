package org.ohmyloader.core.hook

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.*
import org.ohmyloader.api.hook.HookContext
import java.lang.reflect.InvocationTargetException
import kotlin.test.*

/**
 * Mixin **annotation front-end**. The front-end only does "translate + report statically-detectable
 * problems", so the assertions here fall into two classes:
 * 1. **Is the translation right** — which anchor / which payload each annotation field ends up as;
 * 2. **Is the required problem reported** — the few hard requirements shared with Mixin (handler
 *    shape, captures must be explicitly declared…).
 *
 * Real weaving behavior is covered by the engine's tests (the `Injection*Test` ones); the bridge's
 * reflective dispatch lives in the second half of this file.
 */
class HookScannerTest {

    // ---------- @At → anchor ----------

    @Test
    fun `at plain values map to their anchors`() {
        assertIs<InjectionPoint.Head>(parse("HEAD"))
        assertIs<InjectionPoint.ConstructorHead>(parse("CTOR_HEAD"))
        assertIs<InjectionPoint.FinalReturn>(parse("TAIL"))
        assertEquals(InjectionPoint.Return(null), parse("RETURN"))
        assertEquals(InjectionPoint.Return(2), parse("RETURN", ordinal = 2))
    }

    @Test
    fun `at invoke parses the canonical target form`() {
        val point = parse(
            "INVOKE",
            target = "Lnet.minecraft.client.Minecraft;runTick(Z)V",
        )
        assertEquals(
            InjectionPoint.Call("net/minecraft/client/Minecraft", "runTick", "(Z)V", after = false, ordinal = null),
            point,
        )
    }

    @Test
    fun `at invoke allows owner-less and desc-less targets`() {
        // Name-only: neither owner nor descriptor is constrained (Mixin's notation)
        assertEquals(
            InjectionPoint.Call(null, "runTick", null, after = false, ordinal = null),
            parse("INVOKE", target = "runTick"),
        )
        // Dotted form is converted to slashes
        // Dots in the owner are converted to slashes (the engine uses internal names)
        assertEquals(
            InjectionPoint.Call("net/minecraft/client/Minecraft", "runTick", "(Z)V", false, null),
            parse("INVOKE", target = "Lnet.minecraft.client.Minecraft;runTick(Z)V"),
        )
    }

    @Test
    fun `invoke assign and shift after both mean after the call`() {
        assertTrue((parse("INVOKE_ASSIGN", target = "foo()V") as InjectionPoint.Call).after)
        assertTrue((parse("INVOKE", target = "foo()V", shift = "AFTER") as InjectionPoint.Call).after)
        assertFalse((parse("INVOKE", target = "foo()V", shift = "BEFORE") as InjectionPoint.Call).after)
    }

    @Test
    fun `unsupported shift is rejected instead of degrading to before`() {
        assertNull(parse("INVOKE", target = "foo()V", shift = "BY"))
        assertNull(parse("HEAD", shift = "BY"))
        assertNotNull(parse("INVOKE", target = "foo()V", shift = "AFTER"))
        assertNotNull(parse("INVOKE", target = "foo()V", shift = "BEFORE"))

        // The front-end names the unsupported shift as a problem rather than silently injecting BEFORE.
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD", shift = "BY"))
        }
        val node = mixinClass(annotated("h", "(Lorg/ohmyloader/api/hook/HookContext;)V", inject))
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.any { it.contains("shift = \"BY\"") }, parsed.problems.toString())
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `at field carries opcode and field descriptor`() {
        val point = parse("FIELD", target = "Lnet/minecraft/Foo;bar:I", opcode = 180) as InjectionPoint.FieldAccess
        assertEquals("net/minecraft/Foo" to "bar", point.owner to point.name)
        assertEquals("I" to 180, point.desc to point.opcode)
    }

    @Test
    fun `at new accepts owner-only and descriptor-only forms`() {
        assertEquals(
            InjectionPoint.NewInstance("net/minecraft/Foo", null, null),
            parse("NEW", target = "Lnet/minecraft/Foo;"),
        )
        assertEquals(
            InjectionPoint.NewInstance(null, "(I)V", null),
            parse("NEW", target = "(I)V"),
        )
    }

    @Test
    fun `at jump reads the opcode from the target or the opcode field`() {
        assertEquals(InjectionPoint.Jump(Opcodes.IF_ICMPGE, null), parse("JUMP", target = "IF_ICMPGE"))
        assertEquals(InjectionPoint.Jump(Opcodes.IFNULL, 2), parse("JUMP", opcode = Opcodes.IFNULL, ordinal = 2))
        assertEquals(InjectionPoint.Jump(null, null), parse("JUMP"))
        // A mismatched pair, a non-conditional opcode and shift = AFTER are all rejected, not degraded
        assertNull(parse("JUMP", target = "IF_ICMPGE", opcode = Opcodes.IF_ICMPLT))
        assertNull(parse("JUMP", opcode = Opcodes.GOTO))
        assertNull(parse("JUMP", target = "NOT_A_JUMP"))
        assertNull(parse("JUMP", target = "IFEQ", shift = "AFTER"))
    }

    @Test
    fun `at constant reads its value from args`() {
        assertEquals(InjectionPoint.Constant(42, null, after = false), parse("CONSTANT", args = listOf("intValue=42")))
        assertEquals(InjectionPoint.Constant(7L, null, false), parse("CONSTANT", args = listOf("longValue=7")))
        assertEquals(InjectionPoint.Constant("hi", null, false), parse("CONSTANT", args = listOf("stringValue=hi")))
        assertEquals(
            InjectionPoint.Constant(null, 1, false),
            parse("CONSTANT", args = listOf("nullValue=true", "ordinal=1")),
        )
    }

    @Test
    fun `at specifier suffixes FIRST and LAST select the match ends`() {
        assertEquals(
            InjectionPoint.Call(null, "foo", "()V", after = false, ordinal = 0),
            parse("INVOKE:FIRST", target = "foo()V"),
        )
        val last = parse("INVOKE:LAST", target = "foo()V")
        assertIs<InjectionPoint.Call>(last)
        assertTrue(last.last)
        // An unknown suffix is a rule error, not "match all": the whole @At goes unrecognized
        assertNull(parse("INVOKE:ORDINAL", target = "foo()V"))
        assertNull(parse("FIELD:LAST", target = "f:I"))
        // and the suffix combined with an explicit ordinal is contradictory, so unrecognized too
        assertNull(parse("INVOKE:FIRST", target = "foo()V", ordinal = 2))
    }

    @Test
    fun `unknown at value is rejected with a problem`() {
        assertNull(parse("BOGUS"))
        val parsed = HookScanner.parseMixinClass(
            injectClass(
                handler(
                    "h",
                    "(Lorg/ohmyloader/api/hook/HookContext;)V",
                    at = "BOGUS",
                ),
            ),
            "m",
        )
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.problems.single().contains("is not a recognized injection point"), parsed.problems.toString())
    }

    @Test
    fun `legacy dotted target is rejected with a problem`() {
        // `net.minecraft.Foo.bar(I)V` (no L…; wrapper) produces a target that can never match — reported
        // instead of silently emitting a dead anchor
        val node = injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V"))
        node.methods[0].visibleAnnotations[0].put("at", atNode("INVOKE", target = "net.minecraft.Foo.bar(I)V"))
        val parsed = HookScanner.parseMixinClass(node, "m")

        assertTrue(parsed.rules.isEmpty())
        val problem = parsed.problems.single()
        assertTrue(problem.contains("not a recognized injection point"), problem)
        assertTrue(problem.contains("net.minecraft.Foo.bar(I)V"), problem)
    }

    // ---------- @Inject ----------

    @Test
    fun `plain inject compiles to a notify rule`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V")), "m",
        )

        assertEquals(1, parsed.rules.size)
        assertEquals(emptyList(), parsed.problems)
        val rule = parsed.rules.single()
        assertEquals(listOf("runTick"), rule.methodNames)
        assertEquals("(Z)V", rule.methodDesc)
        assertIs<InjectionPoint.Head>(rule.anchor)
        assertIs<Payload.StaticCall>(rule.payload)
    }

    @Test
    fun `no hit policy means soft fail like Mixin`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V")), "m",
        )
        // No declared policy ⇒ require/allow/expect all empty, treated as optional on a miss (Mixin's
        // default behavior)
        assertEquals(null, parsed.rules.single().policy.require)
        assertEquals(null, parsed.rules.single().policy.allow)
    }

    @Test
    fun `hit policy fields are carried over`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(
                handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V", require = 1, expect = 2, allow = 3),
            ),
            "m",
        )
        val policy = parsed.rules.single().policy
        assertEquals(1, policy.require)
        assertEquals(2, policy.expect)
        assertEquals(3, policy.allow)
    }

    @Test
    fun `negative hit policy is reported`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V", require = -5)), "m",
        )
        assertTrue(parsed.problems.single().contains("require=-5"), parsed.problems.toString())
    }

    @Test
    fun `captures without declaring locals are rejected like Mixin`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(handler("h", "(ILorg/ohmyloader/api/hook/HookContext;)V")), "m",
        )

        assertTrue(parsed.rules.isEmpty())
        val problem = parsed.problems.single()
        assertTrue(problem.contains("locals"), problem)
        assertTrue(problem.contains("CAPTURE_FAILHARD"), problem)
    }

    @Test
    fun `capture failhard binds by type and marks strict`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(
                handler(
                    "h", "(Ljava/lang/String;ILjava/lang/String;Lorg/ohmyloader/api/hook/HookContext;)V",
                    locals = "CAPTURE_FAILHARD",
                ),
            ),
            "m",
        )

        val captures = parsed.rules.single().captures
        assertEquals(listOf("Ljava/lang/String;", "I", "Ljava/lang/String;"), captures.map { it.type })
        // The nth occurrence of a type is its ordinal (isomorphic to Mixin's greedy match)
        assertEquals(listOf(0, 0, 1), captures.map { it.ordinal })
        assertTrue(captures.all { it.strict })
        // The slot is not decided statically: the payload holds Local(type, ordinal), with no index
        val payload = parsed.rules.single().payload as Payload.StaticCall
        val locals = payload.args.filterIsInstance<DslValue.Local>()
        assertEquals(listOf(null, null, null), locals.map { it.index })
        assertEquals(listOf(0, 0, 1), locals.map { it.ordinal })
    }

    @Test
    fun `capture failsoft binds without strict`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(
                handler(
                    "h", "(ZLorg/ohmyloader/api/hook/HookContext;)V",
                    locals = "CAPTURE_FAILSOFT",
                ),
            ),
            "m",
        )
        val payload = parsed.rules.single().payload as Payload.StaticCall
        val local = payload.args.filterIsInstance<DslValue.Local>().single()
        assertEquals("Z", local.type)
        assertFalse(local.strict)
    }

    @Test
    fun `locals declared without any capture param is reported`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V", locals = "CAPTURE_FAILHARD")), "m",
        )
        assertTrue(parsed.problems.single().contains("no capture parameters"), parsed.problems.toString())
    }

    @Test
    fun `handler tail must be a callback handle`() {
        val parsed = HookScanner.parseMixinClass(injectClass(handler("h", "()V")), "m")

        assertTrue(parsed.rules.isEmpty())
        val problem = parsed.problems.single()
        assertTrue(problem.contains("HookContext"), problem)
        assertTrue(problem.contains("HookValue"), problem)
    }

    @Test
    fun `non void handler is reported`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)I")), "m",
        )
        assertTrue(parsed.problems.single().contains("must return void"), parsed.problems.toString())
    }

    @Test
    fun `cancelable on a non entry anchor degrades to notify`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(
                handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V", cancelable = true, at = "RETURN"),
            ),
            "m",
        )
        // Degraded to a notifier: the payload is still StaticCall (not CheckCall)
        assertIs<Payload.StaticCall>(parsed.rules.single().payload)
    }

    @Test
    fun `cancelable entry anchor uses the cancelable payload`() {
        val parsed = HookScanner.parseMixinClass(
            injectClass(
                handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V", cancelable = true),
                hooked = true,
            ),
            "m",
        )
        assertIs<Payload.CheckCall>(parsed.rules.single().payload)
    }

    @Test
    fun `missing method name is reported`() {
        val node = injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V"))
        // Erase the method value
        (node.methods[0].visibleAnnotations[0].values as MutableList<Any?>).let {
            it.clear()
            it.addAll(listOf("at", atNode("HEAD")))
        }
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.single().contains("must specify a target method name"), parsed.problems.toString())
    }

    // ---------- @Redirect ----------

    @Test
    fun `redirect on a static call mirrors the descriptor`() {
        val parsed = HookScanner.parseMixinClass(
            redirectClass("(ILjava/lang/String;)V", "INVOKE", "Lnet/minecraft/Foo;bar(ILjava/lang/String;)V"),
            "m",
        )

        assertEquals(emptyList(), parsed.problems)
        val rule = parsed.rules.single()
        assertIs<Payload.Redirect>(rule.payload)
        assertIs<InjectionPoint.Call>(rule.anchor)
        assertEquals("Redirect", rule.kind)
    }

    @Test
    fun `redirect on an instance call takes the receiver as first param`() {
        val parsed = HookScanner.parseMixinClass(
            redirectClass(
                "(Lnet/minecraft/Foo;I)V", "INVOKE", "Lnet/minecraft/Foo;bar(I)V",
            ),
            "m",
        )
        assertEquals(emptyList(), parsed.problems)
    }

    @Test
    fun `redirect handler shape mismatch is reported`() {
        val parsed = HookScanner.parseMixinClass(
            redirectClass("(I)V", "INVOKE", "Lnet/minecraft/Foo;bar(ILjava/lang/String;)V"), "m",
        )
        assertTrue(parsed.rules.isEmpty())
        assertTrue(parsed.problems.single().contains("must equal the replaced call"), parsed.problems.toString())
    }

    @Test
    fun `redirect without a full target is reported`() {
        val parsed = HookScanner.parseMixinClass(
            redirectClass("()V", "INVOKE", "bar"), "m",
        )
        assertTrue(parsed.problems.single().contains("must write a full target"), parsed.problems.toString())
    }

    // ---------- @ModifyArg / @ModifyArgs / @ModifyConstant ----------

    @Test
    fun `modify arg compiles to the modify payload with an explicit index`() {
        val parsed = HookScanner.parseMixinClass(
            modifyClass("ModifyArg", "(I)I", at = atNode("INVOKE", target = "Lnet/minecraft/Foo;bar(I)V"), index = 1),
            "m",
        )

        assertEquals(emptyList(), parsed.problems)
        val payload = parsed.rules.single().payload as Payload.ModifyArg
        assertEquals(1, payload.index)
        // That int in `extras` is the bridge id: the engine pushes it after the arguments, aligning with
        // the bridge descriptor `(T, I)T`
        assertEquals(1, payload.extras.size)
        assertIs<DslValue.IntVal>(payload.extras.single())
        assertEquals("ModifyArg", parsed.rules.single().kind)
    }

    @Test
    fun `modify arg handler shape mismatch is reported`() {
        val parsed = HookScanner.parseMixinClass(
            modifyClass("ModifyArg", "(II)I", at = atNode("INVOKE", target = "Lnet/minecraft/Foo;bar(I)V")), "m",
        )
        assertTrue(parsed.problems.single().contains("must be shaped (T[, sugar…])T"), parsed.problems.toString())
    }

    @Test
    fun `modify args requires the Args parameter`() {
        val ok = HookScanner.parseMixinClass(
            modifyClass(
                "ModifyArgs", "(Lorg/ohmyloader/api/hook/Args;)V",
                at = atNode("INVOKE", target = "Lnet/minecraft/Foo;bar(I)V"),
            ),
            "m",
        )
        assertEquals(emptyList(), ok.problems)
        assertIs<Payload.ModifyArgs>(ok.rules.single().payload)

        val bad = HookScanner.parseMixinClass(
            modifyClass("ModifyArgs", "(I)V", at = atNode("INVOKE", target = "Lnet/minecraft/Foo;bar(I)V")), "m",
        )
        assertTrue(bad.problems.single().contains("Args"), bad.problems.toString())
    }

    @Test
    fun `modify constant reads the Constant annotation and anchors after it`() {
        val node = modifyConstantClass("(I)I", intValue = 42)
        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(emptyList(), parsed.problems)
        // A constant can only be changed "after it is read" ⇒ after = true
        assertEquals(InjectionPoint.Constant(42, null, after = true), parsed.rules.single().anchor)
        assertIs<Payload.ModifyConstant>(parsed.rules.single().payload)
    }

    @Test
    fun `modify constant reads a class literal discriminator`() {
        val stringClass = org.objectweb.asm.Type.getObjectType("java/lang/String")
        val constant = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Constant;").apply {
            values = mutableListOf<Any?>("classValue", stringClass)
        }
        val annotation = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyConstant;").apply {
            values = mutableListOf<Any?>("method", "runTick", "constant", constant)
        }
        val parsed = HookScanner.parseMixinClass(
            mixinClass(annotated("modify", "(Ljava/lang/Class;)Ljava/lang/Class;", annotation)), "m",
        )

        assertEquals(emptyList(), parsed.problems)
        assertEquals(InjectionPoint.Constant(stringClass, null, after = true), parsed.rules.single().anchor)
        assertIs<Payload.ModifyConstant>(parsed.rules.single().payload)
    }

    // ---------- @ModifyVariable ----------

    @Test
    fun `modify variable merges the at anchor with its own discriminators`() {
        val parsed = HookScanner.parseMixinClass(
            asHooked(modifyVariableClass("(I)I", at = "STORE", index = 4, atOrdinal = 1)),
            "m",
        )

        assertEquals(emptyList(), parsed.problems)
        val rule = parsed.rules.single()
        // Three sources merge into one anchor: the type comes from the handler's return type (T), index
        // from @ModifyVariable, ordinal from @At ("which access") — matching Mixin's division of labor
        assertEquals(
            InjectionPoint.Store(index = 4, type = "I", localOrdinal = null, ordinal = 1),
            rule.anchor,
        )
        val payload = rule.payload as Payload.ModifyVariable
        // The bridge is shaped (T, id)T: the value first, id following right after
        assertEquals("(II)I", payload.desc)
        assertEquals(1, payload.extras.size)
        assertIs<DslValue.IntVal>(payload.extras.single())
        assertEquals("ModifyVariable", rule.kind)
    }

    @Test
    fun `modify variable maps ordinal to localOrdinal and passes argsOnly through`() {
        val parsed = HookScanner.parseMixinClass(
            modifyVariableClass(
                "(Ljava/lang/String;)Ljava/lang/String;",
                at = "LOAD",
                ordinal = 1,
                argsOnly = true,
            ),
            "m",
        )

        assertEquals(emptyList(), parsed.problems)
        assertEquals(
            InjectionPoint.Load(index = null, type = "Ljava/lang/String;", localOrdinal = 1, argsOnly = true),
            parsed.rules.single().anchor,
        )
    }

    @Test
    fun `modify variable rejects an anchor that is neither store nor load`() {
        val parsed = HookScanner.parseMixinClass(modifyVariableClass("(I)I", at = "HEAD"), "m")

        assertTrue(parsed.problems.single().contains("must be STORE"), parsed.problems.toString())
        assertEquals(emptyList(), parsed.rules)
    }

    @Test
    fun `modify variable rejects a handler that cannot return the value`() {
        val parsed = HookScanner.parseMixinClass(modifyVariableClass("(I)V", at = "STORE", index = 1), "m")

        assertTrue(
            parsed.problems.single().contains("must be shaped (T[, captures…])T"),
            parsed.problems.toString(),
        )
    }

    @Test
    fun `modify variable reports the unsupported name field`() {
        val parsed = HookScanner.parseMixinClass(
            modifyVariableClass("(I)I", at = "STORE", index = 1, names = listOf("localVar")),
            "m",
        )

        assertTrue(
            parsed.problems.single().contains("does not support locating by variable name"),
            parsed.problems.toString(),
        )
    }

    @Test
    fun `modify variable captures must declare a locals mode`() {
        val without = HookScanner.parseMixinClass(
            modifyVariableClass("(ILjava/lang/String;)I", at = "STORE", index = 1),
            "m",
        )
        assertTrue(without.problems.single().contains("NO_CAPTURE"), without.problems.toString())
        assertEquals(emptyList(), without.rules)

        val with = HookScanner.parseMixinClass(
            modifyVariableClass("(ILjava/lang/String;)I", at = "STORE", index = 1, locals = "CAPTURE_FAILSOFT"),
            "m",
        )
        assertEquals(emptyList(), with.problems)
        val payload = with.rules.single().payload as Payload.ModifyVariable
        // Bridge = value + captures + id
        assertEquals("(ILjava/lang/String;I)I", payload.desc)
        assertEquals(2, payload.extras.size)
    }

    // ---------- @ModifyReturnValue / @ModifyExpressionValue ----------

    /** Build a "value-kind" annotation handler: the annotation supplies [target] (the target method name) and (optionally) [at]. */
    private fun valueHandler(
        name: String,
        desc: String,
        annotationDesc: String,
        target: String,
        at: AnnotationNode? = null,
    ): MethodNode {
        val pairs = mutableListOf<Any?>("method", target)
        if (at != null) pairs.addAll(listOf("at", at))
        return annotated(name, desc, AnnotationNode(Opcodes.ASM9, annotationDesc).apply { values = pairs })
    }

    private val modifyReturnValue = "Lorg/ohmyloader/api/hook/ModifyReturnValue;"
    private val modifyExpressionValue = "Lorg/ohmyloader/api/hook/ModifyExpressionValue;"

    @Test
    fun `modify return value defaults to the RETURN anchor`() {
        // `at` is optional: Mixin's default IS RETURN (change every return)
        val node = mergeMixin().apply {
            methods.add(valueHandler("onReturn", "(I)I", modifyReturnValue, "getFramerateLimit"))
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(emptyList(), parsed.problems, parsed.problems.toString())
        val rule = parsed.rules.single()
        assertIs<InjectionPoint.Return>(rule.anchor.core)
        val payload = rule.payload
        assertIs<Payload.HandlerCall>(payload)
        assertEquals(HandlerKind.MODIFY_RETURN, payload.kind)
        assertEquals("net/minecraft/client/Minecraft", payload.owner)
        assertEquals("(I)I", payload.desc)
        // Merge candidate → the handler must be registered (the merge gives it name-keeping + promotion)
        assertEquals(setOf("onReturn(I)I"), parsed.merges.single().handlerMethods)
    }

    @Test
    fun `modify expression value anchors after the expression`() {
        // The value sits on the stack only **after the expression is produced**; the annotation has no
        // before/after layer, so the front-end pins `after` on the author's behalf
        val node = mergeMixin().apply {
            methods.add(
                valueHandler(
                    "onProxy", "(Ljava/lang/Object;)Ljava/lang/Object;", modifyExpressionValue, "runTick",
                    atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;getProxy()Ljava/net/Proxy;"),
                ),
            )
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(emptyList(), parsed.problems, parsed.problems.toString())
        val rule = parsed.rules.single()
        // `core` is a custom getter (it strips off Sliced); captured into a local only so there is a smart
        // cast
        val core = rule.anchor.core
        assertIs<InjectionPoint.Call>(core)
        assertEquals("getProxy", core.name)
        assertTrue(rule.anchor.after, "anchor must land after the expression: ${rule.anchor}")
        val payload = rule.payload
        assertIs<Payload.HandlerCall>(payload)
        assertEquals(HandlerKind.MODIFY_EXPR_VALUE, payload.kind)
    }

    @Test
    fun `modify return value rejects a handler that cannot return the value`() {
        val node = mergeMixin().apply {
            methods.add(valueHandler("onReturn", "(I)V", modifyReturnValue, "runTick"))
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertTrue(parsed.problems.any { it.contains("@ModifyReturnValue") }, parsed.problems.toString())
    }

    @Test
    fun `modify expression value rejects an anchor that produces no value`() {
        val node = mergeMixin().apply {
            methods.add(
                valueHandler("onTick", "(I)I", modifyExpressionValue, "runTick", atNode("HEAD")),
            )
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertTrue(parsed.problems.any { it.contains("@ModifyExpressionValue") }, parsed.problems.toString())
    }

    @Test
    fun `modify return value outside a merge candidate goes through the bridge`() {
        // Non-merged mixin: the handler stays in the mixin and is called through the bridge — the payload
        // is the static form with "the value already on the stack"
        val node = mixinClass(
            valueHandler("onReturn", "(I)I", modifyReturnValue, "getFramerateLimit"),
        )

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(emptyList(), parsed.problems, parsed.problems.toString())
        assertIs<Payload.TransformReturn>(parsed.rules.single().payload)
    }

    @Test
    fun `modify expression value outside a merge candidate uses the static payload`() {
        val node = mixinClass(
            valueHandler(
                "onProxy", "(Ljava/lang/Object;)Ljava/lang/Object;", modifyExpressionValue, "runTick",
                atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;getProxy()Ljava/net/Proxy;"),
            ),
        )

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(emptyList(), parsed.problems, parsed.problems.toString())
        val rule = parsed.rules.single()
        assertIs<Payload.ModifyExpressionValue>(rule.payload)
        assertTrue(rule.anchor.after, "static form likewise lands after the expression output: ${rule.anchor}")
    }

    // ---------- Class merging ----------

    @Test
    fun `mixin with merge annotations is a merge candidate`() {
        val parsed = HookScanner.parseMixinClass(mergeMixin(), "m")

        assertEquals(emptyList(), parsed.problems)
        val merge = parsed.merges.single()
        assertEquals("net/minecraft/client/Minecraft", merge.targetInternal)
        assertEquals("org.ohmyloader.testmod.TestMixin", merge.className)
    }

    @Test
    fun `injection only mixin is not merged`() {
        // A mixin with only injected handlers **should not** be merged — that would copy the handler
        // methods into the game class too, pure noise, and would quietly change existing behavior.
        // The criterion lives on the annotations (@Borrowed/@Replace/@Extra)
        val parsed = HookScanner.parseMixinClass(
            injectClass(handler("onTick", "(Lorg/ohmyloader/api/hook/HookContext;)V")),
            "m",
        )

        assertEquals(emptyList(), parsed.merges)
        assertEquals(1, parsed.rules.size)
    }

    @Test
    fun `merge candidate keeps producing its injection rules`() {
        // Merging and injection can coexist: @Replace replaces a method body, and a program handler
        // still injects into other methods as usual
        val node = mergeMixin().apply {
            methods.add(handler("onTick", "(Lorg/ohmyloader/api/hook/HookContext;)V"))
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(1, parsed.merges.size)
        assertEquals(1, parsed.rules.size, parsed.problems.toString())
    }

    @Test
    fun `instance inject handler in a merge candidate is called directly on the target`() {
        // A **instance** @Inject handler in a merge candidate moves into the target class with the class
        // merge, and the injection point is changed to a direct call (this = the target instance, so
        // @Borrowed fields really are readable). Two pieces of evidence: the handler is registered into
        // handlerMethods (the merge gives it name-keeping + promotion), and the rule payload is
        // HandlerCall (not a bridge).
        val node = mergeMixin().apply {
            methods.add(handler("onTick", "(Lorg/ohmyloader/api/hook/HookContext;)V"))
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(emptyList(), parsed.problems, parsed.problems.toString())
        assertEquals(
            setOf("onTick(Lorg/ohmyloader/api/hook/HookContext;)V"),
            parsed.merges.single().handlerMethods,
        )
        val payload = parsed.rules.single().payload
        assertIs<Payload.HandlerCall>(payload)
        assertEquals("net/minecraft/client/Minecraft", payload.owner)
        assertEquals("onTick", payload.method)
        // The descriptor must be **whole valid**: the handle sits inside the parens. A descriptor
        // spliced outside them (`()L…;）V`) must not pass — owner/method checks alone cannot see it.
        assertEquals("(Lorg/ohmyloader/api/hook/HookContext;)V", payload.desc)
        assertEquals(HandlerVariant.NOTIFY, payload.variant)
    }

    @Test
    fun `handler call descriptor carries captures before the handle`() {
        // The form with captures: the descriptor must be (captures…, handle)V — in the same order as they
        // are pushed on the stack
        val node = mergeMixin().apply {
            methods.add(
                handler("onTick", "(ILorg/ohmyloader/api/hook/HookContext;)V", locals = "CAPTURE_FAILHARD"),
            )
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(emptyList(), parsed.problems, parsed.problems.toString())
        val payload = parsed.rules.single().payload
        assertIs<Payload.HandlerCall>(payload)
        assertEquals("net/minecraft/client/Minecraft", payload.owner)
        assertEquals("(ILorg/ohmyloader/api/hook/HookContext;)V", payload.desc)
    }

    @Test
    fun `instance modify handler in a merge candidate is called directly`() {
        // Value kinds like @ModifyArg now also have a "direct call" path — the handler moves into the
        // target class with the merge, and the injection point calls it directly with INVOKEVIRTUAL
        // (this = the target instance), instead of going through a static bridge reflectively to a
        // mixin instance (that path reads @Borrowed fields as null by necessity). The only shape change
        // is that **the first parameter becomes the receiver**.
        val node = mergeMixin().apply {
            methods.add(
                handler("onArg", "(Lnet/minecraft/client/Minecraft;I)I").apply {
                    visibleAnnotations = listOf(
                        AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyArg;").apply {
                            values = mutableListOf<Any?>(
                                "method", "runTick", "at",
                                atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick()V"),
                            )
                        },
                    )
                },
            )
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        assertEquals(emptyList(), parsed.problems, parsed.problems.toString())
        val payload = parsed.rules.single().payload
        assertIs<Payload.HandlerCall>(payload)
        assertEquals(HandlerKind.MODIFY_ARG, payload.kind)
        assertEquals("net/minecraft/client/Minecraft", payload.owner)
        assertEquals("onArg", payload.method)
        assertEquals("(Lnet/minecraft/client/Minecraft;I)I", payload.desc)
        assertEquals(HandlerVariant.NOTIFY, payload.variant)
        // The handler is registered as "must keep its name + be promoted" — otherwise the injection point
        // cannot find it at runtime
        assertEquals(setOf("onArg(Lnet/minecraft/client/Minecraft;I)I"), parsed.merges.single().handlerMethods)
    }

    @Test
    fun `instance redirect of a static call is reported`() {
        // A static call has no receiver to accept, so an instance handler cannot wire to it — this must be
        // reported (rather than injecting bytecode whose stack misaligns)
        val node = mergeMixin().apply {
            methods.add(
                annotated(
                    "onCall",
                    "(I)V",
                    AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Redirect;").apply {
                        values = mutableListOf<Any?>(
                            "method", "runTick", "at",
                            atNode(
                                "INVOKE",
                                target = "Lnet/minecraft/Foo;bar(I)V",
                                opcode = Opcodes.INVOKESTATIC,
                            ),
                        )
                    },
                ),
            )
        }

        val parsed = HookScanner.parseMixinClass(node, "m")

        val problem = parsed.problems.single()
        assertTrue(problem.contains("static call"), problem)
        assertTrue(problem.contains("@Redirect"), problem)
    }

    // ---------- @Slice ----------

    @Test
    fun `slice becomes a search window on the anchor`() {
        val node = injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V", at = "RETURN"))
        (node.methods[0].visibleAnnotations[0].values as MutableList<Any?>).addAll(
            listOf("slice", sliceNode("HEAD", "INVOKE", "Lnet/minecraft/Foo;end()V")),
        )
        val parsed = HookScanner.parseMixinClass(node, "m")

        val anchor = parsed.rules.single().anchor
        assertIs<InjectionPoint.Sliced>(anchor)
        assertIs<InjectionPoint.Return>(anchor.inner)
        assertIs<InjectionPoint.Head>(anchor.from)
        assertIs<InjectionPoint.Call>(anchor.to)
    }

    @Test
    fun `slice referencing an unknown id is reported`() {
        val node = injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V"))
        node.methods[0].visibleAnnotations[0].put("at", atNode("HEAD", sliceRef = "missing"))
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.any { it.contains("found no matching @Slice") }, parsed.problems.toString())
    }

    @Test
    fun `a written but unrecognizable slice boundary is reported`() {
        // Dropping a boundary silently would widen the search window to the whole method — reported
        // like any other unrecognizable @At instead, so the self-check fails startup
        val node = injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V", at = "RETURN"))
        node.methods[0].visibleAnnotations[0].put("slice", sliceNode("BOGUS_FROM", "BOGUS"))
        val parsed = HookScanner.parseMixinClass(node, "m")

        assertTrue(
            parsed.problems.any { it.contains("@Slice(from") && it.contains("BOGUS_FROM") },
            parsed.problems.toString(),
        )
        assertTrue(parsed.problems.any { it.contains("@Slice(to") && it.contains("BOGUS") }, parsed.problems.toString())
    }

    @Test
    fun `scan survives a real class-file round trip`() {
        // Exercise the real ClassWriter → ClassReader path: hand-rolled ClassNode fixtures do not cover
        // the read path. The key is that checks like "the injected handler must have a method body"
        // depend on method state **after it is read in**: if the scan used SKIP_CODE (methods have no
        // instructions), every handler would be killed — the front-end would produce zero rules, and
        // the empty rules would cause problems to be swallowed by a `return null`, finally appearing
        // as "the game seems fine but all injections are gone".
        val node = injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V"))
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)

        val read = ClassNode(Opcodes.ASM9)
        ClassReader(writer.toByteArray()).accept(read, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)

        val parsed = HookScanner.parseMixinClass(read, "m")
        assertEquals(1, parsed.rules.size, parsed.problems.toString())
        assertEquals(emptyList(), parsed.problems)
    }

    @Test
    fun `an MR-JAR versioned copy is not scanned as a mixin`() {
        // A mixin class placed only under META-INF/versions/: scanning it would register the same mixin
        // a second time (duplicate @Replace ledger entries / double member synthesis). MR-JAR
        // versioning is the class loader's business, not the scanner's.
        val jar = java.io.File.createTempFile("oml-mixin-mrjar-", ".jar")
        jar.deleteOnExit()
        java.util.jar.JarOutputStream(jar.outputStream()).use { out ->
            val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
            injectClass(handler("h", "(Lorg/ohmyloader/api/hook/HookContext;)V")).accept(writer)
            out.putNextEntry(java.util.jar.JarEntry("META-INF/versions/17/org/ohmyloader/testmod/TestMixin.class"))
            out.write(writer.toByteArray())
            out.closeEntry()
        }
        val mod = org.ohmyloader.core.mod.ModContainer("m", "M", "1.0", "x.Y", jar)

        assertNull(HookScanner.createTransformer(listOf(mod)))
    }

    // ---------- Bridge: dispatch & exception isolation ----------

    @Test
    fun `bridge desc reflects captures and kind`() {
        newBridge()
        val plain = HookRegistry.register("testmod", "x", "h", "m", cancelable = false)
        installBridge()
        assertEquals("(I)V", HookRegistry.bridgeDesc(plain, false))
        assertEquals("(I)Z", HookRegistry.bridgeDesc(plain, true))
    }

    @Test
    fun `registry dispatch invokes handler with callback info`() {
        newBridge()
        RecordingMixin.calls.clear()
        val id = HookRegistry.register(
            modId = "testmod",
            mixinClass = RecordingMixin::class.java.name,
            handlerMethod = "handler",
            targetMethod = "someMethod",
            cancelable = false,
        )
        installBridge()

        invokeBridge(id, cancelable = false)

        assertEquals(1, RecordingMixin.calls.size)
        assertEquals("someMethod", RecordingMixin.calls[0].name)
    }

    @Test
    fun `cancelable dispatch propagates cancel flag`() {
        newBridge()
        val mixin = CancellingMixin()
        val id = HookRegistry.register(
            "testmod", mixin.javaClass.name, "handler", "someMethod", cancelable = true,
        )
        installBridge()

        assertEquals(true, invokeBridge(id, cancelable = true))
    }

    @Test
    fun `handler exception is isolated for notify`() {
        newBridge()
        val id = HookRegistry.register(
            "testmod", ThrowingMixin::class.java.name, "handler", "someMethod", cancelable = false,
        )
        installBridge()

        invokeBridge(id, cancelable = false) // must not throw
    }

    @Test
    fun `captures are forwarded to the handler in declaration order`() {
        newBridge()
        CapturingMixin.calls.clear()
        val id = HookRegistry.register(
            modId = "testmod",
            mixinClass = CapturingMixin::class.java.name,
            handlerMethod = "handler",
            targetMethod = "someMethod",
            cancelable = false,
            captureTypes = listOf("I", "Ljava/lang/String;"),
            handlerParamCount = 3,
        )
        installBridge()

        invokeBridge(id, cancelable = false, 42, "hello")

        assertEquals(listOf<Pair<Any?, Any?>>(42 to "hello"), CapturingMixin.calls)
    }

    @Test
    fun `primitive captures survive boxing`() {
        newBridge()
        PrimitiveCapturingMixin.calls.clear()
        val id = HookRegistry.register(
            "testmod", PrimitiveCapturingMixin::class.java.name, "handler", "someMethod", false,
            captureTypes = listOf("J", "Z"), handlerParamCount = 3,
        )
        installBridge()

        invokeBridge(id, cancelable = false, 9000000000000L, true)

        assertEquals(listOf<Pair<Any?, Any?>>(9000000000000L to true), PrimitiveCapturingMixin.calls)
    }

    @Test
    fun `mirror bridge returns the handler value without unwrapping`() {
        // @Redirect's bridge: the descriptor equals the replaced call (here (I)I), with no id parameter
        newBridge()
        val id = HookRegistry.register(
            "testmod", MirrorMixin::class.java.name, "handler", "someMethod", false,
            captureTypes = listOf("I"), handlerParamCount = 1, returnType = "I", mirror = true,
        )
        installBridge()

        assertEquals("(I)I", HookRegistry.bridgeDesc(id, HookRegistry.BridgeKind.MIRROR))
        assertEquals("redirect$$id", HookRegistry.bridgeMethodName(id, HookRegistry.BridgeKind.MIRROR))
        assertEquals(15, invokeBridgeMethod("redirect$$id", intArrayOf(5)))
    }

    @Test
    fun `modify bridge returns the handler value as a primitive`() {
        newBridge()
        val id = HookRegistry.register(
            "testmod", MirrorMixin::class.java.name, "bump", "someMethod", false,
            captureTypes = listOf("I"), handlerParamCount = 1, returnType = "I",
        )
        installBridge()

        assertEquals("(II)I", HookRegistry.bridgeDesc(id, HookRegistry.BridgeKind.MODIFY))
        assertEquals(6, invokeBridgeMethod("injectValue$$id", intArrayOf(5), id))
    }

    @Test
    fun `modify handler exception propagates instead of being swallowed`() {
        // A value-kind handler "replaces real behavior": swallowing the exception = that call silently
        // never happened
        newBridge()
        val id = HookRegistry.register(
            "testmod", ThrowingMixin::class.java.name, "boom", "someMethod", false,
            captureTypes = listOf("I"), handlerParamCount = 1, returnType = "I",
        )
        installBridge()

        // Reflection wraps the handler exception in an InvocationTargetException — after unwrapping it must
        // still be the original exception, not swallowed into "the call silently never happened"
        val failure = assertFailsWith<Exception> { invokeBridgeMethod("injectValue$$id", intArrayOf(1), id) }
        val cause = (failure as? InvocationTargetException)?.targetException ?: failure
        assertIs<IllegalStateException>(cause)
    }

    @Test
    fun `a handler descriptor matching no overload fails instead of binding the first candidate`() {
        // MirrorMixin.handler(I)I is the only same-name/same-param-count candidate, but the rule declared
        // a long capture: the wanted descriptor is (J)I, no candidate matches. Binding "the first one" in
        // an undefined order would silently invoke a method the rule did not declare — refuse instead.
        newBridge()
        val id = HookRegistry.register(
            "testmod", MirrorMixin::class.java.name, "handler", "someMethod", false,
            captureTypes = listOf("J"), handlerParamCount = 1, returnType = "I", mirror = true,
        )
        installBridge()

        val failure = assertFailsWith<Exception> { invokeBridgeMethod("redirect$$id", arrayOf(7L)) }
        val cause = (failure as? InvocationTargetException)?.targetException ?: failure
        assertIs<NoSuchMethodException>(cause)
        assertTrue(cause.message!!.contains("no overload"), cause.message)
        assertTrue(cause.message!!.contains("(J)I"), cause.message)
    }

    @Test
    fun `typed self handler is woven and receives the target instance through the bridge`() {
        newBridge()
        TypedSelfMixin.received = null
        val targetInternal = TypedSelfTarget::class.java.name.replace('.', '/')
        val carrierInternal = TypedSelfMixin::class.java.name.replace('.', '/')
        val selfDesc = "L$targetInternal;"
        val ciDesc = "Lorg/ohmyloader/api/hook/HookContext;"

        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "run", "at", atNode("HEAD"))
        }
        val node = ClassNode(Opcodes.ASM9).apply {
            name = carrierInternal
            superName = "java/lang/Object"
            version = Opcodes.V1_8
            visibleAnnotations = listOf(
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Hooked;").apply {
                    values = listOf("target", targetInternal.replace('/', '.'))
                },
            )
            methods.add(annotated("handler", "($selfDesc$ciDesc)V", inject))
        }

        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf(selfDesc), handler.captureTypes)
        assertEquals(2, handler.handlerParamCount, "self + HookContext")

        installBridge()
        val self = TypedSelfTarget()
        invokeBridge(1, cancelable = false, self)
        assertSame(self, TypedSelfMixin.received)
    }

    @Test
    fun `modifyReceiver parses and registers the receiver plus operands`() {
        newBridge()
        val node = injectClass(
            annotated(
                "h",
                "(Ljava/lang/String;I)Ljava/lang/String;",
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyReceiver;").apply {
                    values = mutableListOf<Any?>(
                        "method", "runTick",
                        "at", atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"),
                    )
                },
            ),
        )
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf("Ljava/lang/String;", "I"), handler.captureTypes)
        assertEquals(2, handler.handlerParamCount)
        assertEquals("Ljava/lang/String;", handler.returnType)
    }

    @Test
    fun `wrapWithCondition parses a boolean handler`() {
        newBridge()
        val node = injectClass(
            annotated(
                "h",
                "(Ljava/lang/String;I)Z",
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapWithCondition;").apply {
                    values = mutableListOf<Any?>(
                        "method", "runTick",
                        "at", atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"),
                    )
                },
            ),
        )
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf("Ljava/lang/String;", "I"), handler.captureTypes)
        assertEquals(2, handler.handlerParamCount)
        assertEquals("Z", handler.returnType)
    }

    @Test
    fun `every scanned injector annotation exists in the API`() {
        for ((descriptor, name) in HookScanner.INJECTION_ANNOTATIONS) {
            val annotation = Class.forName(org.objectweb.asm.Type.getType(descriptor).className)
            assertTrue(annotation.isAnnotation, "$name ($descriptor) is not an annotation type")
        }
    }

    @Test
    fun `the jump name tables of the parser and the engine agree`() {
        assertEquals(
            org.ohmyloader.core.transformer.injection.AnchorResolver.CONDITIONAL_JUMPS,
            AtParser.JUMP_OPCODES.entries.associate { (name, opcode) -> opcode to name },
        )
    }

    @Test
    fun `wrapOperation parses an Operation handler`() {
        newBridge()
        val node = injectClass(
            annotated(
                "h",
                "(ILorg/ohmyloader/api/hook/Operation;)I",
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapOperation;").apply {
                    values = mutableListOf<Any?>(
                        "method", "runTick",
                        "at", atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"),
                    )
                },
            ),
        )
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf("I", "Lorg/ohmyloader/api/hook/Operation;"), handler.captureTypes)
        assertEquals(2, handler.handlerParamCount)
        assertEquals("I", handler.returnType)
    }

    @Test
    fun `wrapBranch parses a boolean handler`() {
        newBridge()
        val node = injectClass(
            annotated(
                "h",
                "(II)Z",
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapBranch;").apply {
                    values = mutableListOf<Any?>(
                        "method", "runTick",
                        "at", atNode("JUMP", target = "IF_ICMPGE"),
                    )
                },
            ),
        )
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val payload = parsed.rules[0].payload as Payload.WrapBranch
        assertNotNull(payload.desc)
        assertEquals(1, payload.extras.count { it is DslValue.IntVal })
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf("I", "I"), handler.captureTypes)
        assertEquals("Z", handler.returnType)
        assertEquals(2, handler.handlerParamCount)
    }

    @Test
    fun `wrapBranch requires a conditional-jump anchor`() {
        newBridge()
        val node = injectClass(
            annotated(
                "h",
                "(II)Z",
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapBranch;").apply {
                    values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
                },
            ),
        )
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(
            parsed.problems.any { it.contains("conditional jump") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `expression anchor reads the pattern and definitions`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, 3)"))
        }
        val definition = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Definition;").apply {
            values = mutableListOf<Any?>("id", "max", "method", listOf("Ljava/lang/Math;max(II)I"))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyExpressionValue;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(modify, expression, definition)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        val rule = parsed.rules[0]
        val point = rule.anchor as InjectionPoint.Expression
        assertEquals(listOf("max(?, 3)"), point.expressions)
        assertTrue(point.after, "ModifyExpressionValue lands after the producing instruction")
        assertEquals(1, point.definitions.size)
        assertEquals("max", point.definitions[0].id)
        assertEquals(listOf("Ljava/lang/Math;max(II)I"), point.definitions[0].methods)
        assertIs<Payload.ModifyExpressionValue>(rule.payload)
    }

    @Test
    fun `redirect accepts an expression anchor`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, 3)"))
        }
        val definition = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Definition;").apply {
            values = mutableListOf<Any?>("id", "max", "method", listOf("Ljava/lang/Math;max(II)I"))
        }
        val redirect = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Redirect;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(II)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(redirect, expression, definition)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertIs<InjectionPoint.Expression>(parsed.rules[0].anchor.core)
        val payload = parsed.rules[0].payload as Payload.Redirect
        assertNotNull(payload.desc, "an expression anchor registers a bridge so the engine can check the call")
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf("I", "I"), handler.captureTypes)
        assertEquals("I", handler.returnType)
    }

    @Test
    fun `modifyArg accepts an expression anchor`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, 3)"))
        }
        val definition = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Definition;").apply {
            values = mutableListOf<Any?>("id", "max", "method", listOf("Ljava/lang/Math;max(II)I"))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyArg;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"), "index", 1)
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(modify, expression, definition)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertIs<InjectionPoint.Expression>(parsed.rules[0].anchor.core)
        assertIs<Payload.ModifyArg>(parsed.rules[0].payload)
    }

    @Test
    fun `modifyArgs accepts an expression anchor`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, 3)"))
        }
        val definition = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Definition;").apply {
            values = mutableListOf<Any?>("id", "max", "method", listOf("Ljava/lang/Math;max(II)I"))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyArgs;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h",
            "(Lorg/ohmyloader/api/hook/Args;)V", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(modify, expression, definition)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertIs<InjectionPoint.Expression>(parsed.rules[0].anchor.core)
        assertIs<Payload.ModifyArgs>(parsed.rules[0].payload)
    }

    @Test
    fun `an undeclared expression identifier is reported at scan time`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, 3)"))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyExpressionValue;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(modify, expression)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("undeclared identifier \"max\"") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `an undeclared expression type is reported at scan time`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("new StringBuilder(?)"))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyExpressionValue;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h",
            "(Ljava/lang/StringBuilder;)Ljava/lang/StringBuilder;", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.ARETURN))
            visibleAnnotations = listOf(modify, expression)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("undeclared type \"StringBuilder\"") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `definition local print is carried into the model`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("x * 2"))
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/DefinitionLocal;").apply {
            values = mutableListOf<Any?>("index", 1, "print", true)
        }
        val definition = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Definition;").apply {
            values = mutableListOf<Any?>("id", "x", "local", listOf(local))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyExpressionValue;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(modify, expression, definition)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        val point = parsed.rules[0].anchor as InjectionPoint.Expression
        val declared = point.definitions.single().locals.single()
        assertEquals(1, declared.index)
        assertTrue(declared.print)
    }

    @Test
    fun `an expression anchor without a pattern is reported`() {
        newBridge()
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyExpressionValue;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(modify)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("needs an @Expression") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `a malformed expression pattern is reported at scan time`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, "))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyExpressionValue;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(modify, expression)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("cannot be parsed") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `a container of expressions is selected by id`() {
        newBridge()
        val inner = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, 3)"), "id", "inner")
        }
        val outer = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, ?)"))
        }
        val container = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expressions;").apply {
            values = mutableListOf<Any?>("value", listOf(inner, outer))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyExpressionValue;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("EXPRESSION").put("id", "inner"),
            )
        }
        val definition = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Definition;").apply {
            values = mutableListOf<Any?>("id", "max", "method", listOf("Ljava/lang/Math;max(II)I"))
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(modify, container, definition)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        val point = parsed.rules[0].anchor as InjectionPoint.Expression
        assertEquals(listOf("max(?, 3)"), point.expressions)
    }

    @Test
    fun `lambda annotation retargets the rule`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "tick", "at", atNode("HEAD"))
        }
        val lambda = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Lambda;").apply {
            values = mutableListOf<Any?>(
                "interfaceType", org.objectweb.asm.Type.getObjectType("java/util/function/Supplier"),
                "ordinal", 1,
            )
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "h",
            "(Lorg/ohmyloader/api/hook/HookContext;)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject, lambda)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals("java/util/function/Supplier", parsed.rules[0].lambda?.interfaceInternal)
        assertEquals(1, parsed.rules[0].lambda?.ordinal)
    }

    @Test
    fun `a lambda handler on a merged carrier must be static`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "tick", "at", atNode("HEAD"))
        }
        val lambda = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Lambda;").apply {
            values = mutableListOf<Any?>("interfaceType", org.objectweb.asm.Type.getObjectType("java/lang/Runnable"))
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC, "h", "(Lorg/ohmyloader/api/hook/HookContext;)V", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject, lambda)
        }
        val parsed = HookScanner.parseMixinClass(asMerged(mixinClass(method)), "m")
        assertTrue(
            parsed.problems.any { it.contains("must be static") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `merged wrapBranch calls the handler directly`() {
        newBridge()
        val method = MethodNode(Opcodes.ACC_PUBLIC, "h", "(II)Z", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapBranch;").apply {
                    values = mutableListOf<Any?>(
                        "method", "runTick",
                        "at", atNode("JUMP", target = "IF_ICMPGE"),
                    )
                },
            )
        }
        val parsed = HookScanner.parseMixinClass(asMerged(mixinClass(method)), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        val payload = parsed.rules[0].payload as Payload.HandlerCall
        assertEquals(HandlerKind.WRAP_BRANCH, payload.kind)
        assertEquals("(II)Z", payload.desc)
    }

    @Test
    fun `merged ModifyReturnValue accepts an expression anchor`() {
        newBridge()
        val expression = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Expression;").apply {
            values = mutableListOf<Any?>("value", listOf("max(?, 3)"))
        }
        val definition = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Definition;").apply {
            values = mutableListOf<Any?>("id", "max", "method", listOf("Ljava/lang/Math;max(II)I"))
        }
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyReturnValue;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("EXPRESSION"))
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC, "h", "(I)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(modify, expression, definition)
        }
        val parsed = HookScanner.parseMixinClass(asMerged(mixinClass(method)), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertIs<InjectionPoint.Expression>(parsed.rules[0].anchor.core)
        val payload = parsed.rules[0].payload as Payload.HandlerCall
        assertEquals(HandlerKind.MODIFY_RETURN, payload.kind)
    }

    @Test
    fun `redirect accepts a NEW anchor`() {
        newBridge()
        val redirect = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Redirect;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("NEW", target = "Lnet/minecraft/Foo;(I)V"),
            )
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)Lnet/minecraft/Foo;", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.ARETURN))
            visibleAnnotations = listOf(redirect)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(
            InjectionPoint.NewInstance("net/minecraft/Foo", "(I)V"),
            parsed.rules[0].anchor,
        )
        assertNotNull((parsed.rules[0].payload as Payload.Redirect).desc)
    }

    @Test
    fun `redirect on NEW reports a handler shape mismatch`() {
        newBridge()
        val redirect = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Redirect;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("NEW", target = "Lnet/minecraft/Foo;(I)V"),
            )
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(J)Lnet/minecraft/Foo;", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.ARETURN))
            visibleAnnotations = listOf(redirect)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("constructor's arguments") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `implements is carried by a merge class`() {
        newBridge()
        val iface = org.objectweb.asm.Type.getObjectType("com/example/Extra")
        val implementsNode = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Implements;").apply {
            values = mutableListOf<Any?>("value", listOf(iface))
        }
        val node = asMerged(mixinClass())
        node.visibleAnnotations = node.visibleAnnotations + implementsNode
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(listOf("com/example/Extra"), parsed.merges.single().implementedInterfaces)
    }

    @Test
    fun `implements is carried by an interface carrier`() {
        newBridge()
        val iface = org.objectweb.asm.Type.getObjectType("com/example/Extra")
        val implementsNode = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Implements;").apply {
            values = mutableListOf<Any?>("value", listOf(iface))
        }
        val node = mixinClass().apply {
            access = Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT or Opcodes.ACC_INTERFACE
            visibleAnnotations = visibleAnnotations + implementsNode
        }
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(listOf("com/example/Extra"), parsed.merges.single().implementedInterfaces)
    }

    @Test
    fun `implements on a hooked class is reported`() {
        newBridge()
        val iface = org.objectweb.asm.Type.getObjectType("com/example/Extra")
        val implementsNode = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Implements;").apply {
            values = mutableListOf<Any?>("value", listOf(iface))
        }
        val node = mixinClass().apply { visibleAnnotations = visibleAnnotations + implementsNode }
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(
            parsed.problems.any { it.contains("needs a merge carrier") },
            parsed.problems.toString(),
        )
    }

    @Test
    fun `wrapWithCondition accepts an array creation anchor`() {
        newBridge()
        val wrap = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapWithCondition;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("NEW_ARRAY", target = "[I"),
            )
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)Z", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(wrap)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(
            InjectionPoint.ArrayCreate(Opcodes.NEWARRAY, atype = 10),
            parsed.rules[0].anchor,
        )
        assertIs<Payload.WrapWithCondition>(parsed.rules[0].payload)
    }

    @Test
    fun `wrapWithCondition accepts a NEW anchor`() {
        newBridge()
        val wrap = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapWithCondition;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("NEW", target = "Lnet/minecraft/Foo;()V"),
            )
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "()Z", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(wrap)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(
            InjectionPoint.NewInstance("net/minecraft/Foo", "()V"),
            parsed.rules[0].anchor,
        )
        assertIs<Payload.WrapWithCondition>(parsed.rules[0].payload)
    }

    @Test
    fun `wrapWithCondition on NEW reports a handler shape mismatch`() {
        newBridge()
        val wrap = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapWithCondition;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("NEW", target = "Lnet/minecraft/Foo;()V"),
            )
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "h", "(I)Z", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IRETURN))
            visibleAnnotations = listOf(wrap)
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("constructor's arguments") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `wrapMethod parses a whole-method Operation handler`() {
        newBridge()
        val node = injectClass(
            annotated(
                "h",
                "(ILorg/ohmyloader/api/hook/Operation;)I",
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapMethod;").apply {
                    values = mutableListOf<Any?>("method", "runTick", "desc", "(I)I")
                },
            ),
        )
        val parsed = HookScanner.parseMixinClass(node, "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf("I", "Lorg/ohmyloader/api/hook/Operation;"), handler.captureTypes)
        assertEquals(2, handler.handlerParamCount)
        assertEquals("I", handler.returnType)
    }

    @Test
    fun `local sugar binds a handler parameter by slot`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Local;").apply {
            values = mutableListOf<Any?>("index", 2, "type", "I")
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC, "h", "(ILorg/ohmyloader/api/hook/HookContext;)V", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject)
            visibleParameterAnnotations = arrayOf(mutableListOf(local), mutableListOf())
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val payload = parsed.rules[0].payload as Payload.StaticCall
        val bound = payload.args[0] as DslValue.Local
        assertEquals(2, bound.index)
        assertEquals("I", bound.type)
        assertTrue(bound.strict)
    }

    @Test
    fun `local sugar cannot bind the callback handle`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Local;").apply {
            values = mutableListOf<Any?>("index", 0)
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC, "h", "(Lorg/ohmyloader/api/hook/HookContext;)V", null, null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject)
            visibleParameterAnnotations = arrayOf(mutableListOf(local))
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("@Local cannot bind the trailing") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `redirect sugar must be a trailing suffix`() {
        newBridge()
        val redirect = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Redirect;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"),
            )
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Local;").apply {
            values = mutableListOf<Any?>("index", 1)
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(Lorg/ohmyloader/api/hook/LocalRef;Z)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(redirect)
            visibleParameterAnnotations = arrayOf(mutableListOf(local), mutableListOf())
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("contiguous trailing suffix") },
            parsed.problems.toString(),
        )
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `local sugar on a LocalRef parameter produces a mutable binding`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Local;").apply {
            values = mutableListOf<Any?>("index", 1, "type", "I")
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(Lorg/ohmyloader/api/hook/LocalRef;Lorg/ohmyloader/api/hook/HookContext;)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject)
            visibleParameterAnnotations = arrayOf(mutableListOf(local), mutableListOf())
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val payload = parsed.rules[0].payload as Payload.StaticCall
        val bound = payload.args[0] as DslValue.RefLocal
        assertEquals(1, bound.index)
        assertEquals("I", bound.type)
        assertTrue(bound.strict)
    }

    @Test
    fun `a LocalRef sugar without index or type is reported`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Local;")
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(Lorg/ohmyloader/api/hook/LocalRef;Lorg/ohmyloader/api/hook/HookContext;)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject)
            visibleParameterAnnotations = arrayOf(mutableListOf(local), mutableListOf())
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.any { it.contains("needs index or type") }, parsed.problems.toString())
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `share sugar produces a shared ref binding`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
        }
        val share = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Share;").apply {
            values = mutableListOf<Any?>("value", "x")
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(Lorg/ohmyloader/api/hook/LocalRef;Lorg/ohmyloader/api/hook/HookContext;)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject)
            visibleParameterAnnotations = arrayOf(mutableListOf(share), mutableListOf())
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val payload = parsed.rules[0].payload as Payload.StaticCall
        val bound = payload.args[0] as DslValue.SharedRef
        assertEquals("x", bound.id)
        assertEquals("org.ohmyloader.testmod.TestMixin", bound.namespace)
    }

    @Test
    fun `share sugar requires a LocalRef parameter`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
        }
        val share = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Share;").apply {
            values = mutableListOf<Any?>("value", "x")
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(Ljava/lang/String;Lorg/ohmyloader/api/hook/HookContext;)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject)
            visibleParameterAnnotations = arrayOf(mutableListOf(share), mutableListOf())
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.any { it.contains("must be LocalRef") }, parsed.problems.toString())
        assertEquals(0, parsed.rules.size)
    }

    @Test
    fun `local sugar trails a ModifyArg handler`() {
        newBridge()
        val modify = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyArg;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"),
            )
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Local;").apply {
            values = mutableListOf<Any?>("index", 1)
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC, "h", "(II)I", null, null).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(modify)
            visibleParameterAnnotations = arrayOf(mutableListOf(), mutableListOf(local))
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val payload = parsed.rules[0].payload as Payload.ModifyArg
        val bound = payload.extras[0] as DslValue.Local
        assertEquals(1, bound.index)
        assertEquals("I", bound.type)
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf("I", "I"), handler.captureTypes)
        assertEquals(2, handler.handlerParamCount)
    }

    @Test
    fun `share sugar trails a WrapOperation handler`() {
        newBridge()
        val wrap = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapOperation;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"),
            )
        }
        val share = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Share;").apply {
            values = mutableListOf<Any?>("value", "s")
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(ILorg/ohmyloader/api/hook/Operation;Lorg/ohmyloader/api/hook/LocalRef;)I",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(wrap)
            visibleParameterAnnotations = arrayOf(mutableListOf(), mutableListOf(), mutableListOf(share))
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val payload = parsed.rules[0].payload as Payload.WrapOperation
        val bound = payload.extras[0] as DslValue.SharedRef
        assertEquals("s", bound.id)
        val handler = HookRegistry.handler(1)!!
        assertEquals(
            listOf("I", "Lorg/ohmyloader/api/hook/Operation;", "Lorg/ohmyloader/api/hook/LocalRef;"),
            handler.captureTypes,
        )
        assertEquals(3, handler.handlerParamCount)
    }

    @Test
    fun `cancellable trails a WrapOperation handler`() {
        newBridge()
        val wrap = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapOperation;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "desc", "(Z)I",
                "at", atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)I"),
            )
        }
        val handle = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Cancellable;")
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(ILorg/ohmyloader/api/hook/Operation;Lorg/ohmyloader/api/hook/HookValue;)I",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(wrap)
            visibleParameterAnnotations = arrayOf(mutableListOf(), mutableListOf(), mutableListOf(handle))
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        assertEquals(1, parsed.rules.size)
        val payload = parsed.rules[0].payload as Payload.WrapOperation
        val bound = payload.extras[0] as DslValue.CancelableHandle
        assertEquals("Lorg/ohmyloader/api/hook/HookValue;", bound.descriptor)
        val handler = HookRegistry.handler(1)!!
        assertEquals(
            listOf("I", "Lorg/ohmyloader/api/hook/Operation;", "Lorg/ohmyloader/api/hook/HookValue;"),
            handler.captureTypes,
        )
        assertEquals(3, handler.handlerParamCount)
    }

    @Test
    fun `cancellable handle type must match the target return`() {
        newBridge()
        val wrap = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/WrapOperation;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "desc", "(Z)V",
                "at", atNode("INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"),
            )
        }
        val handle = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Cancellable;")
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(ILorg/ohmyloader/api/hook/Operation;Lorg/ohmyloader/api/hook/HookValue;)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(wrap)
            visibleParameterAnnotations = arrayOf(mutableListOf(), mutableListOf(), mutableListOf(handle))
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("must use HookContext") },
            parsed.problems.toString(),
        )
    }

    @Test
    fun `cancellable is rejected on non-WrapOperation handlers`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
        }
        val handle = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Cancellable;")
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(Lorg/ohmyloader/api/hook/HookContext;)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject)
            visibleParameterAnnotations = arrayOf(mutableListOf(handle))
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(
            parsed.problems.any { it.contains("only supported on @WrapOperation") },
            parsed.problems.toString(),
        )
    }

    @Test
    fun `typed local ref sugar carries the ref type`() {
        newBridge()
        val inject = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply {
            values = mutableListOf<Any?>("method", "runTick", "at", atNode("HEAD"))
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Local;").apply {
            values = mutableListOf<Any?>("index", 1)
        }
        val method = MethodNode(
            Opcodes.ACC_PUBLIC,
            "h",
            "(Lorg/ohmyloader/api/hook/LocalIntRef;Lorg/ohmyloader/api/hook/HookContext;)V",
            null,
            null,
        ).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(inject)
            visibleParameterAnnotations = arrayOf(mutableListOf(local), mutableListOf())
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        val payload = parsed.rules[0].payload as Payload.StaticCall
        val bound = payload.args[0] as DslValue.RefLocal
        assertEquals(1, bound.index)
        assertEquals("I", bound.type)
        assertEquals("Lorg/ohmyloader/api/hook/LocalIntRef;", bound.refType)
    }

    @Test
    fun `local sugar trails a Redirect handler`() {
        newBridge()
        val redirect = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Redirect;").apply {
            values = mutableListOf<Any?>(
                "method", "runTick",
                "at", atNode("INVOKE", target = "Ljava/lang/Integer;toString(I)Ljava/lang/String;"),
            )
        }
        val local = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Local;").apply {
            values = mutableListOf<Any?>("index", 1)
        }
        val method = MethodNode(Opcodes.ACC_PUBLIC, "h", "(II)Ljava/lang/String;", null, null).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            visibleAnnotations = listOf(redirect)
            visibleParameterAnnotations = arrayOf(mutableListOf(), mutableListOf(local))
        }
        val parsed = HookScanner.parseMixinClass(mixinClass(method), "m")
        assertTrue(parsed.problems.isEmpty(), parsed.problems.toString())
        val payload = parsed.rules[0].payload as Payload.Redirect
        assertNotNull(payload.desc)
        val bound = payload.extras[0] as DslValue.Local
        assertEquals(1, bound.index)
        assertEquals("I", bound.type)
        val handler = HookRegistry.handler(1)!!
        assertEquals(listOf("I", "I"), handler.captureTypes)
        assertEquals(2, handler.handlerParamCount)
    }

    // ---------- fixtures ----------

    private fun parse(
        value: String,
        target: String = "",
        args: List<String> = emptyList(),
        ordinal: Int = -1,
        opcode: Int = -1,
        shift: String = "",
    ): InjectionPoint? = AtParser.parse(atNode(value, target, args, ordinal, opcode, shift))

    /** Override a key in the annotation (appending it if absent) — for tweaking a single field in a fixture. */
    private fun AnnotationNode.put(name: String, value: Any?): AnnotationNode {
        val pairs = values as MutableList<Any?>
        var i = 0
        while (i + 1 < pairs.size) {
            if (pairs[i] == name) {
                pairs[i + 1] = value
                return this
            }
            i += 2
        }
        pairs.addAll(listOf(name, value))
        return this
    }

    private fun atNode(
        value: String,
        target: String = "",
        args: List<String> = emptyList(),
        ordinal: Int = -1,
        opcode: Int = -1,
        shift: String = "",
        sliceRef: String = "",
    ): AnnotationNode {
        val pairs = mutableListOf<Any?>("value", value)
        if (target.isNotEmpty()) pairs.addAll(listOf("target", target))
        if (args.isNotEmpty()) pairs.addAll(listOf("args", args))
        if (ordinal >= 0) pairs.addAll(listOf("ordinal", ordinal))
        if (opcode >= 0) pairs.addAll(listOf("opcode", opcode))
        if (shift.isNotEmpty()) pairs.addAll(listOf("shift", listOf($$"Lorg/ohmyloader/api/hook/At$Shift;", shift)))
        if (sliceRef.isNotEmpty()) pairs.addAll(listOf("slice", sliceRef))
        return AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/At;").apply { values = pairs }
    }

    private fun sliceNode(from: String, to: String, toTarget: String = "", id: String = ""): AnnotationNode {
        val pairs = mutableListOf<Any?>("from", atNode(from), "to", atNode(to, target = toTarget))
        if (id.isNotEmpty()) pairs.addAll(listOf("id", id))
        return AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Slice;").apply { values = pairs }
    }

    /** A `@Mixin(target)` class holding one method annotated with `@Inject`. */
    private fun injectClass(method: MethodNode, hooked: Boolean = false): ClassNode =
        if (hooked) asHooked(mixinClass(method)) else mixinClass(method)

    private fun asHooked(node: ClassNode): ClassNode = withModel(node, "Lorg/ohmyloader/api/hook/Hooked;")

    private fun asMerged(node: ClassNode): ClassNode = withModel(node, "Lorg/ohmyloader/api/hook/Merged;")

    private fun withModel(node: ClassNode, desc: String): ClassNode {
        val target = node.visibleAnnotations.first().values[1]
        node.visibleAnnotations = listOf(
            org.objectweb.asm.tree.AnnotationNode(Opcodes.ASM9, desc).apply {
                values = listOf("target", target)
            },
        )
        return node
    }

    private fun mixinClass(vararg methods: MethodNode): ClassNode = ClassNode(Opcodes.ASM9).apply {
        name = "org/ohmyloader/testmod/TestMixin"
        superName = "java/lang/Object"
        version = Opcodes.V1_8
        visibleAnnotations = listOf(
            AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Hooked;").apply {
                values = listOf("target", "net.minecraft.client.Minecraft")
            },
        )
        this.methods.addAll(methods)
    }

    private fun handler(
        name: String,
        desc: String,
        injectMethod: String = "runTick",
        targetDesc: String = "(Z)V",
        at: String = "HEAD",
        cancelable: Boolean = false,
        locals: String? = null,
        // Int.MIN_VALUE = "this field is not written" (so the test can pass negatives to verify the checks)
        require: Int = Int.MIN_VALUE,
        expect: Int = Int.MIN_VALUE,
        allow: Int = Int.MIN_VALUE,
    ): MethodNode {
        val pairs = mutableListOf<Any?>(
            "method", injectMethod,
            "desc", targetDesc,
            "at", atNode(at),
            "cancelable", cancelable,
        )
        if (locals != null) pairs.addAll(listOf("locals", listOf("Lorg/ohmyloader/api/hook/LocalCapture;", locals)))
        if (require != Int.MIN_VALUE) pairs.addAll(listOf("require", require))
        if (expect != Int.MIN_VALUE) pairs.addAll(listOf("expect", expect))
        if (allow != Int.MIN_VALUE) pairs.addAll(listOf("allow", allow))
        val annotation = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Inject;").apply { values = pairs }
        return annotated(name, desc, annotation)
    }

    private fun redirectClass(handlerDesc: String, at: String, target: String): ClassNode =
        mixinClass(
            annotated(
                "redirect",
                handlerDesc,
                AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Redirect;").apply {
                    values = mutableListOf<Any?>("method", "runTick", "at", atNode(at, target = target))
                },
            ),
        )

    private fun modifyClass(
        annotationName: String,
        handlerDesc: String,
        at: AnnotationNode,
        index: Int = -1,
    ): ClassNode {
        val pairs = mutableListOf<Any?>("method", "runTick", "at", at)
        if (index >= 0) pairs.addAll(listOf("index", index))
        val annotation =
            AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/$annotationName;").apply { values = pairs }
        return mixinClass(annotated("modify", handlerDesc, annotation))
    }

    private fun modifyConstantClass(handlerDesc: String, intValue: Int): ClassNode {
        val constant = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Constant;").apply {
            values = mutableListOf<Any?>("intValue", intValue)
        }
        val annotation = AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyConstant;").apply {
            values = mutableListOf<Any?>("method", "runTick", "constant", constant)
        }
        return mixinClass(annotated("modify", handlerDesc, annotation))
    }

    /**
     * The `@ModifyVariable` fixture: the discriminators (index/ordinal/argsOnly/name) go in its own
     * fields, and `@At` only supplies "STORE / LOAD + which access".
     */
    private fun modifyVariableClass(
        handlerDesc: String,
        at: String,
        index: Int = Int.MIN_VALUE,
        ordinal: Int = Int.MIN_VALUE,
        argsOnly: Boolean = false,
        names: List<String> = emptyList(),
        locals: String? = null,
        atOrdinal: Int = Int.MIN_VALUE,
    ): ClassNode {
        val pairs = mutableListOf<Any?>("method", "runTick", "at", atNode(at, ordinal = atOrdinal))
        if (index != Int.MIN_VALUE) pairs.addAll(listOf("index", index))
        if (ordinal != Int.MIN_VALUE) pairs.addAll(listOf("ordinal", ordinal))
        if (argsOnly) pairs.addAll(listOf("argsOnly", true))
        // Arrays in annotations: ASM reads them from a class file as a List, so hand-rolling must also use
        // a List to match the real path
        if (names.isNotEmpty()) pairs.addAll(listOf("name", names))
        if (locals != null) pairs.addAll(listOf("locals", listOf("Lorg/ohmyloader/api/hook/LocalCapture;", locals)))
        val annotation =
            AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/ModifyVariable;").apply { values = pairs }
        return mixinClass(annotated("modify", handlerDesc, annotation))
    }

    /** A mixin on the **class-merge** model: `@Replace`s one method + `@Borrowed`s one field. */
    private fun mergeMixin(): ClassNode = asMerged(mixinClass(
        MethodNode(Opcodes.ACC_PUBLIC, "value", "()I", null, null).apply {
            instructions.add(InsnNode(Opcodes.ICONST_1))
            instructions.add(InsnNode(Opcodes.IRETURN))
            maxStack = 1
            maxLocals = 1
            visibleAnnotations = listOf(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Replace;"))
        },
    ).apply {
        fields.add(
            FieldNode(Opcodes.ACC_PRIVATE, "player", "Ljava/lang/Object;", null, null).apply {
                visibleAnnotations = listOf(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Borrowed;"))
            },
        )
    })

    private fun annotated(name: String, desc: String, annotation: AnnotationNode): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null).apply {
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 0
            maxLocals = 0
            visibleAnnotations = listOf(annotation)
        }

    // ---------- Bridge scaffolding ----------

    private fun newBridge() = HookRegistry.resetForTests()

    private fun installBridge() {
        HookRegistry.installClassLoader(HookScannerTest::class.java.classLoader)
        HookRegistry.ensureBridgeGenerated()
    }

    /** Look up the generated bridge method by id and call it (simulating the injected bytecode's INVOKESTATIC). */
    private fun invokeBridge(id: Int, cancelable: Boolean, vararg captures: Any?): Any? =
        invokeBridgeMethod(HookRegistry.bridgeMethodName(id, cancelable), captures, id)

    private fun invokeBridgeMethod(name: String, captures: Array<out Any?>, id: Int = -1): Any? {
        val bridge = HookRegistry.bridgeClass ?: error("the bridge class has not been generated yet")
        val method = bridge.declaredMethods.first { it.name == name }
        return method.invoke(null, *captures, *if (id >= 0) arrayOf<Any?>(id) else emptyArray())
    }

    private fun invokeBridgeMethod(name: String, captures: IntArray, id: Int = -1): Any? =
        invokeBridgeMethod(name, Array<Any?>(captures.size) { captures[it] }, id)

    class RecordingMixin {
        companion object {
            val calls = mutableListOf<HookContext>()
        }

        fun handler(ci: HookContext) {
            calls += ci
        }
    }

    class CancellingMixin {
        fun handler(ci: HookContext) {
            ci.cancel()
        }
    }

    class ThrowingMixin {
        fun handler(ci: HookContext) {
            throw IllegalStateException("boom")
        }

        fun boom(v: Int): Int = throw IllegalStateException("boom")
    }

    class CapturingMixin {
        companion object {
            val calls = mutableListOf<Pair<Any?, Any?>>()
        }

        @Suppress("UNUSED_PARAMETER")
        fun handler(a: Int, b: String, ci: HookContext) {
            calls += a to b
        }
    }

    /** Primitive captures (long takes two slots, boolean is an int on the stack). */
    class PrimitiveCapturingMixin {
        companion object {
            val calls = mutableListOf<Pair<Any?, Any?>>()
        }

        @Suppress("UNUSED_PARAMETER")
        fun handler(a: Long, b: Boolean, ci: HookContext) {
            calls += a to b
        }
    }

    /** A handler of the `@Redirect` / `@ModifyArg` shape (no callback handle). */
    class MirrorMixin {
        @Suppress("UNUSED_PARAMETER")
        fun handler(v: Int): Int = v * 3

        @Suppress("UNUSED_PARAMETER")
        fun bump(v: Int): Int = v + 1
    }

    /** Target type for the typed-self regression: the handler's first parameter names this class. */
    class TypedSelfTarget

    class TypedSelfMixin {
        companion object {
            var received: TypedSelfTarget? = null
        }

        @Suppress("UNUSED_PARAMETER")
        fun handler(self: TypedSelfTarget, ci: HookContext) {
            received = self
        }
    }
}
