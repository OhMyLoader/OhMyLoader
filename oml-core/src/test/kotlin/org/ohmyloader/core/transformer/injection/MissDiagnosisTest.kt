package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.*
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The "why" when a rule does not hit ([MissDiagnosis]).
 *
 * This layer targets the hardest failure mode to debug: **a rule that is written wrong yet reports
 * nothing** (no crash, no throw — it just fails to do one thing). The cases only assert that "the
 * diagnosis explains the reason"; hit counts and hard failures are `reportHits`' responsibility,
 * with two other cases covering the wiring between the two (the `require` exception message must
 * carry the diagnosis).
 */
class MissDiagnosisTest {

    private val owner = "omltest/Miss"

    /** The diagnosis only looks at the anchor; the payload takes no part — an inert `Raw` suffices. */
    private val anyPayload: Payload = Payload.Raw { _, _, _ -> }

    // ---------- selector did not match ----------

    @Test
    fun `descriptor mismatch is spelled out with the overloads that do exist`() {
        val node = classNode(methodNode("foo", "(I)V") { add(InsnNode(Opcodes.RETURN)) })

        val text = diagnose(node, MethodSelector(setOf("foo"), "(J)V"))

        assertTrue(text.contains("none of the descriptors match"), text)
        assertTrue(text.contains("foo(I)V"), text)
        assertTrue(text.contains("desc=(J)V"), text)
    }

    @Test
    fun `name mismatch lists what the class actually has`() {
        val node = classNode(
            methodNode("alpha", "()V") { add(InsnNode(Opcodes.RETURN)) },
            methodNode("beta", "()V") { add(InsnNode(Opcodes.RETURN)) },
        )

        val text = diagnose(node, MethodSelector(setOf("gamma"), "()V"))

        assertTrue(text.contains("none of the names is among gamma"), text)
        assertTrue(text.contains("alpha()V"), text)
        assertTrue(text.contains("beta()V"), text)
    }

    @Test
    fun `a missing name says how many methods the class has`() {
        val node = classNode(methodNode("alpha", "()V") { add(InsnNode(Opcodes.RETURN)) })

        val text = diagnose(node, MethodSelector(setOf("gamma"), null))

        assertTrue(text.contains("has no method named gamma"), text)
        assertTrue(text.contains("has 1 method"), text)
    }

    @Test
    fun `methods without a body are called out`() {
        val node = classNode(
            MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "run", "()V", null, null),
        )

        val text = diagnose(node, MethodSelector(setOf("run"), "()V"))

        assertTrue(text.contains("has a method body"), text)
        assertTrue(text.contains("run()V"), text)
    }

    // ---------- anchor drift ----------

    @Test
    fun `a call with the same name but another descriptor is the near miss`() {
        val node = classNode(
            methodNode("m", "()V") {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "bar", "()V", false))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        val text = diagnoseAnchor(node, InjectionPoint.Call(null, "bar", "(II)V", after = false))

        assertTrue(text.contains("owner / descriptor does not match"), text)
        assertTrue(text.contains("x/Y.bar()V"), text)
    }

    @Test
    fun `a call that lives in another method of the class is named`() {
        val node = classNode(
            methodNode("foo", "()V") {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "bar", "()V", false))
                add(InsnNode(Opcodes.RETURN))
            },
            methodNode("baz", "()V") { add(InsnNode(Opcodes.RETURN)) },
        )

        val text = diagnoseAnchor(node, InjectionPoint.Call(null, "bar", null, after = false), only = "baz")

        assertTrue(text.contains("another method"), text)
        assertTrue(text.contains("foo()V"), text)
    }

    @Test
    fun `a call that is not in the method at all lists the calls that are`() {
        val node = classNode(
            methodNode("foo", "()V") {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "bar", "()V", false))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        val text = diagnoseAnchor(node, InjectionPoint.Call(null, "nope", null, after = false))

        assertTrue(text.contains("has no call named nope"), text)
        assertTrue(text.contains("x/Y.bar()V"), text)
    }

    @Test
    fun `a constant that is absent lists the constants that are there`() {
        val node = classNode(
            methodNode("m", "()V") {
                add(InsnNode(Opcodes.ICONST_0))
                add(InsnNode(Opcodes.POP))
                add(IntInsnNode(Opcodes.BIPUSH, 60))
                add(InsnNode(Opcodes.POP))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        val text = diagnoseAnchor(node, InjectionPoint.Constant(42))

        assertTrue(text.contains("the method's constants are"), text)
        assertTrue(text.contains("0"), text)
        assertTrue(text.contains("60"), text)
    }

    @Test
    fun `a local store that is absent lists the slots that are written`() {
        val node = classNode(
            methodNode("m", "()V") {
                add(IntInsnNode(Opcodes.BIPUSH, 5))
                add(VarInsnNode(Opcodes.ISTORE, 1))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        val text = diagnoseAnchor(node, InjectionPoint.Store(index = 7))

        assertTrue(text.contains("writes"), text)
        assertTrue(text.contains("slot 1"), text)
    }

    @Test
    fun `a field access that is absent lists the accesses that are there`() {
        val node = classNode(
            methodNode("m", "()V") {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(FieldInsnNode(Opcodes.GETFIELD, "x/Y", "f", "I"))
                add(InsnNode(Opcodes.POP))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        val text = diagnoseAnchor(node, InjectionPoint.FieldAccess(null, "g", null, null, after = false))

        assertTrue(text.contains("field accesses are"), text)
        assertTrue(text.contains("x/Y.f I"), text)
    }

    @Test
    fun `a new that is absent lists what is instantiated`() {
        val node = classNode(
            methodNode("m", "()V") {
                add(TypeInsnNode(Opcodes.NEW, "x/Y"))
                add(InsnNode(Opcodes.POP))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        val text = diagnoseAnchor(node, InjectionPoint.NewInstance(owner = "x/Z", desc = null))

        assertTrue(text.contains("`new`s"), text)
    }

    @Test
    fun `a sliced anchor names the window and recurses into the inner anchor`() {
        val node = classNode(methodNode("m", "()V") { add(InsnNode(Opcodes.RETURN)) })

        val text = diagnoseAnchor(
            node,
            InjectionPoint.Sliced(
                inner = InjectionPoint.Call(null, "bar", null, after = false),
                from = InjectionPoint.Head,
                to = InjectionPoint.FinalReturn,
            ),
        )

        assertTrue(text.contains("search window is"), text)
        assertTrue(text.contains("has no call named bar"), text)
    }

    @Test
    fun `constructor head on a plain method is called out as degraded`() {
        val node = classNode(methodNode("m", "()V") { add(InsnNode(Opcodes.RETURN)) })

        val text = diagnoseAnchor(node, InjectionPoint.ConstructorHead)

        assertTrue(text.contains("has no constructor"), text)
    }

    @Test
    fun `a return ordinal beyond the actual count is spelled out`() {
        val node = classNode(methodNode("m", "()V") { add(InsnNode(Opcodes.RETURN)) })

        val text = diagnoseAnchor(node, InjectionPoint.Return(ordinal = 3))

        assertTrue(text.contains("the #3"), text)
        assertTrue(text.contains("has 1 returns"), text)
    }

    // ---------- wiring with hit-count policies ----------

    @Test
    fun `the diagnosis reaches the require failure message`() {
        // The selector hits the method but the call is not there ⇒ 0 hits and require(1) ⇒ the
        // exception message must carry the diagnosis
        val spec = specOf {
            classTarget(owner) {
                method("m", desc = "()V") {
                    require(1)
                    beforeCall("x/Y", "nope", "()V") { omlCall("hook") }
                }
            }
        }
        val node = classNode(
            methodNode("m", "()V") {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "bar", "()V", false))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        val failure = assertFailsWith<InjectionError> { spec.transform(TransformContext(owner, node)) }

        assertTrue(failure.message!!.contains("hit count too low"), failure.message!!)
        assertTrue(failure.message!!.contains("has no call named nope"), failure.message!!)
    }

    @Test
    fun `a rule that hits produces no failure`() {
        // Baseline: the same rule retargeted at the **existing** call ⇒ 1 hit, require(1) satisfied, no
        // diagnosis emitted
        val spec = specOf {
            classTarget(owner) {
                method("m", desc = "()V") {
                    require(1)
                    beforeCall("x/Y", "bar", "()V") { omlCall("hook") }
                }
            }
        }
        val node = classNode(
            methodNode("m", "()V") {
                add(MethodInsnNode(Opcodes.INVOKESTATIC, "x/Y", "bar", "()V", false))
                add(InsnNode(Opcodes.RETURN))
            }
        )

        assertTrue(spec.transform(TransformContext(owner, node)), "the rule should hit and modify the target")
    }

    // ---------- scaffolding ----------

    private fun diagnose(node: ClassNode, selector: MethodSelector): String =
        diagnose(node, selector, emptyList())

    private fun diagnose(
        node: ClassNode,
        selector: MethodSelector,
        points: List<Pair<InjectionPoint, Payload>>,
    ): String {
        val named = node.methods.filter { selector.matches(it.name, it.desc) }
        val injectable = named.filter { it.instructions.size() > 0 }
        return MissDiagnosis.explain(node, MethodRule(selector, points), named, injectable).joinToString("\n")
    }

    /** A rule attached to some method of the class, with anchor [anchor]. */
    private fun diagnoseAnchor(node: ClassNode, anchor: InjectionPoint, only: String? = null): String =
        diagnose(
            node,
            MethodSelector(setOf(only ?: node.methods.first().name), null),
            listOf(anchor to anyPayload),
        )

    private fun classNode(vararg methods: MethodNode): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = owner
        superName = "java/lang/Object"
        this.methods.addAll(methods)
    }

    private fun methodNode(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, desc, null, null).apply {
            instructions.build()
            maxStack = 4
            maxLocals = 8
        }
}
