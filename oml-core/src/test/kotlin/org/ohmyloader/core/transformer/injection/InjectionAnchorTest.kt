package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.Anchor
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionBuilder
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Anchor completion (`RETURN` / `TAIL` / `CTOR_HEAD` / `FIELD` / `NEW` / `CONSTANT` + `ordinal`).
 *
 * Many cases still **define the class and run it** — because for these anchor types the value is not
 * just "the position was found", but "the bytecode inserted there is still valid" (especially
 * `CTOR_HEAD`: inserting before `super()` would fail directly with `VerifyError`).
 */
class InjectionAnchorTest {

    private val owner = "omltest/Anchor"

    // ---------- instruction level: ordinal and filtering ----------

    @Test
    fun `ordinal on call picks the nth matching invocation`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    beforeCall(name = "tick", ordinal = 1) { omlCall("hook") }
                }
            }
        }
        val first = MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "tick", "()V", false)
        val second = MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "tick", "()V", false)
        val cls = classNode(
            methodNode("m", "()V") {
                add(first); add(second); add(InsnNode(Opcodes.RETURN))
            }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(1, hooks.size)
        assertEquals(second, hooks[0].next, "should be inserted before the second tick")
    }

    @Test
    fun `field access is matched by owner name and opcode`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    beforeField(owner = "x/Y", name = "counter", opcode = Opcodes.GETFIELD) { omlCall("hook") }
                }
            }
        }
        val read = FieldInsnNode(Opcodes.GETFIELD, "x/Y", "counter", "I")
        val write = FieldInsnNode(Opcodes.PUTFIELD, "x/Y", "counter", "I")
        val other = FieldInsnNode(Opcodes.GETFIELD, "x/Y", "other", "I")
        val cls = classNode(
            methodNode("m", "()V") {
                add(read); add(write); add(other); add(InsnNode(Opcodes.RETURN))
            }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(1, hooks.size, "only the GETFIELD counter should match")
        assertEquals(read, hooks[0].next)
    }

    @Test
    fun `afterField places the hook right after the access`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    afterField(owner = "x/Y", name = "counter") { omlCall("hook") }
                }
            }
        }
        val read = FieldInsnNode(Opcodes.GETFIELD, "x/Y", "counter", "I")
        val cls = classNode(
            methodNode("m", "()V") {
                add(read); add(InsnNode(Opcodes.POP)); add(InsnNode(Opcodes.RETURN))
            }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(1, hooks.size)
        assertEquals(read, hooks[0].previous)
    }

    @Test
    fun `new instance is matched by owner`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    beforeNew(owner = "java/util/ArrayList") { omlCall("hook") }
                }
            }
        }
        val target = TypeInsnNode(Opcodes.NEW, "java/util/ArrayList")
        val other = TypeInsnNode(Opcodes.NEW, "java/lang/Object")
        val cls = classNode(
            methodNode("m", "()V") { add(target); add(other); add(InsnNode(Opcodes.RETURN)) }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(1, hooks.size)
        assertEquals(target, hooks[0].next)
    }

    @Test
    fun `new instance with desc requires a matching init call`() {
        // With a nested new inserted between NEW and <init>, only depth pairing can recognize the
        // real constructor call
        val target = TypeInsnNode(Opcodes.NEW, "x/Y")
        val nested = TypeInsnNode(Opcodes.NEW, "x/Z")
        val nestedInit = MethodInsnNode(Opcodes.INVOKESPECIAL, "x/Z", "<init>", "()V", false)
        val outerInit = MethodInsnNode(Opcodes.INVOKESPECIAL, "x/Y", "<init>", "(I)V", false)
        val insns = listOf(target, nested, nestedInit, outerInit)

        assertFindsNew(insns, desc = "(I)V", expected = target)
        assertFindsNew(insns, desc = "()V", expected = null)
    }

    @Test
    fun `constant matches short instruction forms too`() {
        // javac generates ICONST_1 for 1 instead of LDC — recognizing only LDC would miss it
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    beforeConstant(value = 1) { omlCall("hook") }
                }
            }
        }
        val iconst = InsnNode(Opcodes.ICONST_1)
        val ldcLong = LdcInsnNode(1L)
        val bipush = IntInsnNode(Opcodes.BIPUSH, 1)
        val cls = classNode(
            methodNode("m", "()V") {
                add(iconst); add(ldcLong); add(bipush); add(InsnNode(Opcodes.RETURN))
            }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(2, hooks.size, "ICONST_1 and BIPUSH 1 should match; LDC 1L should not (different type)")
        assertEquals(iconst, hooks[0].next)
        assertEquals(bipush, hooks[1].next)
    }

    @Test
    fun `constant distinguishes null from other constants`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") { beforeConstant(value = null) { omlCall("hook") } }
            }
        }
        val nullConst = InsnNode(Opcodes.ACONST_NULL)
        val cls = classNode(
            methodNode("m", "()V") { add(nullConst); add(InsnNode(Opcodes.RETURN)) }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))
        assertEquals(nullConst, hookCalls(cls, "m").single().next)
    }

    @Test
    fun `constant matches string and class constants`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    beforeConstant(value = "textures/x.png") { omlCall("hook") }
                }
            }
        }
        val wanted = LdcInsnNode("textures/x.png")
        val other = LdcInsnNode("textures/y.png")
        val cls = classNode(
            methodNode("m", "()V") { add(wanted); add(other); add(InsnNode(Opcodes.RETURN)) }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))
        val hooks = hookCalls(cls, "m")
        assertEquals(1, hooks.size)
        assertEquals(wanted, hooks[0].next)
    }

    // ---------- define and run: CTOR_HEAD ----------

    @Test
    fun `constructorHead lands after the delegate call and this is usable there`() {
        // Target: after super() in the constructor, call handler(This) (passing this to a static method).
        // If the anchor landed before super(), defining the class would throw VerifyError — so this
        // case "defining and running it successfully is itself the conclusion."
        val spec = spec {
            classTarget(owner) {
                constructor(desc = "()V") {
                    atConstructorHead {
                        call(
                            Handlers.OWNER,
                            "onConstructed",
                            "(Ljava/lang/Object;)V",
                            listOf(DslValue.This)
                        )
                    }
                }
            }
        }
        val node = classNode(newConstructor())
        val clazz = define(node, spec)

        Handlers.constructed.clear()
        clazz.getDeclaredConstructor().newInstance()

        assertEquals(1, Handlers.constructed.size)
    }

    @Test
    fun `plain head in a constructor is reported as a rule problem`() {
        val spec = spec {
            classTarget(owner) {
                constructor(desc = "()V") {
                    atHead { call(Handlers.OWNER, "onConstructed", "(Ljava/lang/Object;)V", listOf(DslValue.This)) }
                }
            }
        }

        val problems = spec.verify()
        assertTrue(problems.any { it.contains("do not use the HEAD anchor in a constructor") }, problems.toString())
    }

    @Test
    fun `constructorHead degrades to head for non constructors`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") { atConstructorHead { omlCall("hook") } }
            }
        }
        val first = InsnNode(Opcodes.NOP)
        val cls = classNode(methodNode("m", "()V") { add(first); add(InsnNode(Opcodes.RETURN)) })

        assertTrue(spec.transform(TransformContext(owner, cls)))
        assertEquals(first, hookCalls(cls, "m").single().next)
    }

    // ---------- rule-level validation ----------

    @Test
    fun `negative ordinal is reported`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") { atReturn(ordinal = -1) { omlCall("hook") } }
            }
        }
        assertTrue(spec.verify().any { it.contains("cannot be negative") }, spec.verify().toString())
    }

    @Test
    fun `new without any qualifier is reported`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") { beforeNew { omlCall("hook") } }
            }
        }
        assertTrue(spec.verify().any { it.contains("NEW gave no qualifier") }, spec.verify().toString())
    }

    @Test
    fun `constant with an unsupported value type is reported`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") { beforeConstant(value = listOf(1, 2)) { omlCall("hook") } }
            }
        }
        assertTrue(spec.verify().any { it.contains("CONSTANT only supports") }, spec.verify().toString())
    }

    @Test
    fun `field with an out of range opcode is reported`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    beforeField(owner = "x/Y", name = "n", opcode = Opcodes.INVOKEVIRTUAL) { omlCall("hook") }
                }
            }
        }
        assertTrue(spec.verify().any { it.contains("FIELD opcode can only be") }, spec.verify().toString())
    }

    @Test
    fun `transformReturn is accepted on the tail anchor`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()I") {
                    atTail { transformReturn("x/Y", "tweak", "(I)I") }
                }
            }
        }
        // TAIL is a return-class anchor, so it must not be reported
        val problems = spec.verify()
        assertFalse(problems.any { it.contains("needs a return anchor") }, problems.toString())
    }

    @Test
    fun `transformReturn on a call anchor is reported`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()I") {
                    beforeCall(name = "tick") { transformReturn("x/Y", "tweak", "(I)I") }
                }
            }
        }
        assertTrue(spec.verify().any { it.contains("needs a return anchor") }, spec.verify().toString())
    }

    // ---------- within: search window ----------

    @Test
    fun `within limits the search to the region between the bounds`() {
        // Three returns, only the one between begin/end should be hit
        val begin = MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "begin", "()V", false)
        val end = MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "end", "()V", false)
        val retBefore = InsnNode(Opcodes.RETURN)
        val retInside = InsnNode(Opcodes.RETURN)
        val retAfter = InsnNode(Opcodes.RETURN)

        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    within(from = Anchor.call(name = "begin"), to = Anchor.call(name = "end")) {
                        atReturn { omlCall("hook") }
                    }
                }
            }
        }
        val cls = classNode(
            methodNode("m", "()V") {
                add(retBefore)
                add(begin)
                add(retInside)
                add(end)
                add(retAfter)
            }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(1, hooks.size, "only the return inside the window should match")
        assertEquals(retInside, hooks[0].next)
    }

    @Test
    fun `within with only a from bound leaves the tail open`() {
        val begin = MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "begin", "()V", false)
        val retBefore = InsnNode(Opcodes.RETURN)
        val retAfter1 = InsnNode(Opcodes.RETURN)
        val retAfter2 = InsnNode(Opcodes.RETURN)

        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    within(from = Anchor.call(name = "begin")) {
                        atReturn { omlCall("hook") }
                    }
                }
            }
        }
        val cls = classNode(
            methodNode("m", "()V") {
                add(retBefore); add(begin); add(retAfter1); add(retAfter2)
            }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(2, hooks.size)
        assertEquals(retAfter1, hooks[0].next)
        assertEquals(retAfter2, hooks[1].next)
    }

    @Test
    fun `within applies ordinal inside the window`() {
        val begin = MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "begin", "()V", false)
        val retOutside = InsnNode(Opcodes.RETURN)
        val retIn1 = InsnNode(Opcodes.RETURN)
        val retIn2 = InsnNode(Opcodes.RETURN)

        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    within(from = Anchor.call(name = "begin")) {
                        atReturn(ordinal = 1) { omlCall("hook") }
                    }
                }
            }
        }
        val cls = classNode(
            methodNode("m", "()V") {
                add(retOutside); add(begin); add(retIn1); add(retIn2)
            }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(1, hooks.size, "ordinal should be counted within the window, not the whole method")
        assertEquals(retIn2, hooks[0].next)
    }

    @Test
    fun `within keeps head anchored cancelling payloads valid`() {
        // Payload validation looks at the anchor **core** — a HEAD inside a window is still a
        // "method entry"
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    within(to = Anchor.call(name = "end")) {
                        atHead { cancellableCall("x/Y", "check", "()Z") }
                    }
                }
            }
        }
        assertTrue(
            spec.verify().none { it.contains("only supports method-entry anchors") },
            spec.verify().toString(),
        )
    }

    @Test
    fun `within with an unresolvable bound injects nothing`() {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    within(from = Anchor.call(name = "missing")) {
                        atReturn { omlCall("hook") }
                    }
                }
            }
        }
        val cls = classNode(methodNode("m", "()V") { add(InsnNode(Opcodes.RETURN)) })

        // A missing bound means no injection (rather than silently degrading to the whole-method range)
        assertFalse(spec.transform(TransformContext(owner, cls)))
        assertEquals(0, hookCalls(cls, "m").size)
    }

    @Test
    fun `within uses the last match of the from bound`() {
        val begin1 = MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "begin", "()V", false)
        val begin2 = MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "begin", "()V", false)
        val retEarly = InsnNode(Opcodes.RETURN)
        val retLate = InsnNode(Opcodes.RETURN)

        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    within(from = Anchor.call(name = "begin")) {
                        atReturn { omlCall("hook") }
                    }
                }
            }
        }
        val cls = classNode(
            methodNode("m", "()V") {
                add(begin1); add(retEarly); add(begin2); add(retLate)
            }
        )

        assertTrue(spec.transform(TransformContext(owner, cls)))

        val hooks = hookCalls(cls, "m")
        assertEquals(1, hooks.size, "from takes the last match (begin2), so retEarly is out of the window")
        assertEquals(retLate, hooks[0].next)
    }

    // ---------- scaffolding ----------

    private fun spec(block: InjectionBuilder.() -> Unit): InjectionSpec = specOf(block)

    private fun assertFindsNew(
        insns: List<AbstractInsnNode>,
        desc: String,
        expected: TypeInsnNode?,
    ) {
        val spec = spec {
            classTarget(owner) {
                method("m", desc = "()V") {
                    beforeNew(owner = "x/Y", desc = desc) { omlCall("hook") }
                }
            }
        }
        val cls = classNode(methodNode("m", "()V") { insns.forEach { add(it) } })
        spec.transform(TransformContext(owner, cls))

        val hooks = hookCalls(cls, "m")
        if (expected == null) {
            assertEquals(0, hooks.size, "desc=$desc should not match")
        } else {
            assertEquals(1, hooks.size, "desc=$desc should match")
            assertEquals(expected, hooks[0].next)
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

    private fun newConstructor(): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            // The legal injection point is after super(); the injection block lands after this
            // INVOKESPECIAL
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 2
            maxLocals = 1
        }

    private fun methodNode(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, desc, null, null).apply {
            instructions.build()
            maxStack = 4
            maxLocals = 8
        }

    /**
     * Define and link the target class (`VerifyError` would be thrown here).
     *
     * Uses `COMPUTE_MAXS` and **not** `COMPUTE_FRAMES`: the latter would compute the stack frames
     * for the caller, hiding whether "the frames the injection block wrote itself" are correct. The
     * anchors in this file produce no branches, so the manual frame burden is zero and can be
     * checked faithfully.
     */
    private fun define(node: ClassNode, spec: InjectionSpec): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionAnchorTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    /** Extract the OML hook calls that were injected. */
    private fun hookCalls(cls: ClassNode, methodName: String): List<MethodInsnNode> =
        cls.methods.first { it.name == methodName }.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
            .filter { it.owner == "org/ohmyloader/core/OMLCore" || it.owner == Handlers.OWNER }
}

/** Handler invoked via reflection by the `CTOR_HEAD` cases. */
object Handlers {
    const val OWNER: String = "org/ohmyloader/core/transformer/injection/Handlers"

    val constructed = mutableListOf<Any>()

    @JvmStatic
    fun onConstructed(instance: Any) {
        constructed += instance
    }
}
