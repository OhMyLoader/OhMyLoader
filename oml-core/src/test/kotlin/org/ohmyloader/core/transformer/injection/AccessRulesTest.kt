package org.ohmyloader.core.transformer.injection

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.*
import org.ohmyloader.core.transformer.TransformContext
import java.lang.reflect.Modifier
import kotlin.test.*

private const val OWNER = "omlacc/AccessTarget"
private const val INNER = $$"omlacc/AccessTarget$Inner"
private const val SUB = "omlacc/AccessSub"

/**
 * Access-modifier rewriting (the DSL's `access {}` / `field(…) {}` / `methodAccess(…) {}`).
 *
 * Each case **defines and really uses** the result (define-and-run): the effect of changing flags is
 * only proven when the JVM itself answers — `Modifier.isPublic` reads the bits in the class file,
 * while whether `Field.get` / `Field.set` / inheriting a class really passes depends on how HotSpot
 * interprets those bits at link time. Next to every "the rewrite took effect" assertion there is a
 * **baseline** (running the same probe without any rule must fail), otherwise it is impossible to
 * tell "rewritten correctly" apart from "the probe already worked".
 */
class AccessRulesTest {

    // ---------- field visibility ----------

    @Test
    fun `widening a private field makes it readable without setAccessible`() {
        val target = build(targetSpec { field("secret", "I") { makePublic() } })

        val field = target.clazz.getDeclaredField("secret")
        assertTrue(Modifier.isPublic(field.modifiers), "field should be public: ${Modifier.toString(field.modifiers)}")

        val instance = target.clazz.getDeclaredConstructor().newInstance()
        assertEquals(5, field.getInt(instance), "a public field is readable without setAccessible")
    }

    @Test
    fun `baseline - a private field is unreadable without the rule`() {
        val target = build(null)
        val field = target.clazz.getDeclaredField("secret")
        val instance = target.clazz.getDeclaredConstructor().newInstance()

        assertFalse(Modifier.isPublic(field.modifiers))
        assertFailsWith<IllegalAccessException> { field.getInt(instance) }
    }

    // ---------- removing final ----------

    @Test
    fun `removing final from a static field makes it writable`() {
        val target = build(targetSpec { field("VERSION", "I") { removeFinal() } })

        val field = target.clazz.getDeclaredField("VERSION")
        assertFalse(
            Modifier.isFinal(field.modifiers),
            "should no longer be final: ${Modifier.toString(field.modifiers)}",
        )

        // A static final field is unwritable even with setAccessible — so to write it, ACC_FINAL must
        // really be removed
        field.isAccessible = true
        field.set(null, 42)
        assertEquals(42, field.getInt(null))
    }

    @Test
    fun `baseline - a static final field is unwritable even with setAccessible`() {
        val target = build(null)
        val field = target.clazz.getDeclaredField("VERSION")
        field.isAccessible = true

        assertTrue(Modifier.isFinal(field.modifiers))
        assertFailsWith<IllegalAccessException> { field.set(null, 42) }
    }

    // ---------- removing final from a class ----------

    @Test
    fun `removing final from a class allows inheriting it`() {
        val target = build(targetSpec { access { removeFinal() } })
        assertFalse(Modifier.isFinal(target.clazz.modifiers))

        val sub = subclass(target.loader, override = false)
        assertEquals(target.clazz, sub.superclass, "defining the subclass proves ACC_FINAL is really gone")
    }

    @Test
    fun `baseline - inheriting a final class fails at link time`() {
        val target = build(null)
        assertTrue(Modifier.isFinal(target.clazz.modifiers))

        val failure = assertFails { subclass(target.loader, override = false) }
        assertContains(
            failure.message.orEmpty(), "final",
            message = "the link-time error should mention final: ${failure.message}",
        )
    }

    // ---------- methods: remove final + promote visibility ----------

    @Test
    fun `removing final from a method allows overriding it`() {
        val target = build(
            targetSpec { methodAccess("locked", "()I") { makePublic(); removeFinal() } },
            node = target(finalClass = false),
        )

        val method = target.clazz.getDeclaredMethod("locked")
        assertTrue(Modifier.isPublic(method.modifiers))
        assertFalse(Modifier.isFinal(method.modifiers))

        val sub = subclass(target.loader, override = true)
        val instance = sub.getDeclaredConstructor().newInstance()
        assertEquals(99, sub.getMethod("locked").invoke(instance), "the override should take effect")
    }

    @Test
    fun `baseline - overriding a final method fails at link time`() {
        val target = build(null, node = target(finalClass = false))
        assertEquals(
            Opcodes.ACC_PROTECTED or Opcodes.ACC_FINAL,
            target.node.methods.first { it.name == "locked" }.access and (Opcodes.ACC_FINAL or Opcodes.ACC_PROTECTED),
        )

        val failure = assertFails { subclass(target.loader, override = true) }
        assertContains(
            failure.message.orEmpty(), "final",
            message = "the link-time error should mention final: ${failure.message}",
        )
    }

    // ---------- nested classes: both flags change together ----------

    @Test
    fun `a nested class keeps its own flags and the InnerClasses entry in sync`() {
        val node = nested()
        val spec = specOf { classTarget(INNER) { access { makePublic() } } }
        spec.transform(TransformContext(INNER, node))

        assertTrue(node.access and Opcodes.ACC_PUBLIC != 0, "the class file's own flags must change")
        val self = node.innerClasses.first { it.name == INNER }
        assertTrue(
            self.access and Opcodes.ACC_PUBLIC != 0,
            "the InnerClasses self-entry must change too (reflection reads that one)",
        )

        val clazz = define(node)
        assertTrue(Modifier.isPublic(clazz.modifiers))
    }

    @Test
    fun `a nested class can be narrowed, and the InnerClasses entry follows`() {
        val node = nested()
        val spec = specOf { classTarget(INNER) { access { makePackagePrivate() } } }
        spec.transform(TransformContext(INNER, node))

        assertEquals(0, node.access and Opcodes.ACC_PUBLIC)
        // Assert to the concrete tier (not just "not public"): the self-entry starts private, and if it
        // were not mirrored it would still be private
        assertEquals(Visibility.PACKAGE, Visibility.of(node.innerClasses.first { it.name == INNER }.access))

        val clazz = define(node)
        assertFalse(Modifier.isPublic(clazz.modifiers))
    }

    @Test
    fun `a top-level class refuses private - it would not survive the class loader`() {
        // A top-level class only has public / package-private tiers; private is a tier that only nested
        // classes have (written on InnerClasses)
        val node = target()
        val before = node.access
        val spec = specOf { classTarget(OWNER) { access { makePrivate(); require(1) } } }

        val failure = assertFailsWith<InjectionError> { spec.transform(TransformContext(OWNER, node)) }
        assertContains(failure.message.orEmpty(), "top-level class")
        assertEquals(before, node.access, "the rejected rule must not change a single byte")
    }

    // ---------- structural constraints on interfaces ----------

    @Test
    fun `interface fields refuse everything but public and non-final`() {
        val node = iface()
        val before = node.fields.first { it.name == "X" }.access

        // Interface fields are pinned to public static final by the JVMS
        val dropFinal = specOf { classTarget("omlacc/IAccess") { field("X", "I") { removeFinal(); require(1) } } }
        assertContains(
            assertFailsWith<InjectionError> {
                dropFinal.transform(
                    TransformContext(
                        "omlacc/IAccess",
                        node,
                    ),
                )
            }.message.orEmpty(),
            "interface fields",
        )

        val narrow = specOf { classTarget("omlacc/IAccess") { field("X", "I") { makePrivate(); require(1) } } }
        assertContains(
            assertFailsWith<InjectionError> {
                narrow.transform(
                    TransformContext(
                        "omlacc/IAccess",
                        node,
                    ),
                )
            }.message.orEmpty(),
            "interface fields",
        )
        assertEquals(before, node.fields.first { it.name == "X" }.access)
    }

    @Test
    fun `interface methods refuse protected and package-private`() {
        val node = iface()
        val before = node.methods.first { it.name == "run" }.access

        // Interface methods (class file >= 52) must have exactly one of ACC_PUBLIC or ACC_PRIVATE
        for (spec in listOf(
            specOf { classTarget("omlacc/IAccess") { methodAccess("run", "()I") { makeProtected(); require(1) } } },
            specOf {
                classTarget("omlacc/IAccess") {
                    methodAccess(
                        "run",
                        "()I",
                    ) { makePackagePrivate(); require(1) }
                }
            },
        )) {
            assertContains(
                assertFailsWith<InjectionError> { spec.transform(TransformContext("omlacc/IAccess", node)) }
                    .message.orEmpty(),
                "interface methods can only be public or private",
            )
        }
        assertEquals(
            before,
            node.methods.first { it.name == "run" }.access,
            "the rejected rule must not change a single byte",
        )
    }

    @Test
    fun `an interface method with a body may go private, but an abstract one may not`() {
        val node = iface()

        // An interface method with a body can be made private (interface private methods since Java 9)
        val privatize = specOf {
            classTarget("omlacc/IAccess") { methodAccess("helper", "()I") { makePrivate(); require(1) } }
        }
        assertTrue(privatize.transform(TransformContext("omlacc/IAccess", node)))
        assertTrue(node.methods.first { it.name == "helper" }.access and Opcodes.ACC_PRIVATE != 0)

        // Abstract methods cannot: a private interface method must have a body
        val abstractToo = specOf {
            classTarget("omlacc/IAccess") { methodAccess("run", "()I") { makePrivate(); require(1) } }
        }
        assertContains(
            assertFailsWith<InjectionError> { abstractToo.transform(TransformContext("omlacc/IAccess", node)) }
                .message.orEmpty(),
            "cannot be private",
        )
    }

    // ---------- `<clinit>` ----------

    @Test
    fun `clinit is rejected statically and skipped at apply time`() {
        val node = target()
        val spec = specOf { classTarget(OWNER) { methodAccess("<clinit>", "()V") { makePublic(); require(1) } } }

        val problems = spec.verify(null)
        assertTrue(problems.any { it.contains("<clinit>") }, "startup self-check should report it: $problems")

        val before = node.methods.first { it.name == "<clinit>" }.access
        assertFailsWith<InjectionError> { spec.transform(TransformContext(OWNER, node)) }
        assertEquals(before, node.methods.first { it.name == "<clinit>" }.access, "skipped, so it must not change")
    }

    @Test
    fun `hotspot accepts extra flags on clinit - the rejection is about meaning, not legality`() {
        // The reason the engine rejects <clinit> is "changing it has no meaning", not "it would write an
        // illegal class". This case pins the latter claim: give <clinit> ACC_PUBLIC and HotSpot still
        // accepts it.
        val node = target()
        node.methods.first { it.name == "<clinit>" }.access = Opcodes.ACC_STATIC or Opcodes.ACC_PUBLIC

        assertNotNull(define(node))
    }

    // ---------- startup-time self-check ----------

    @Test
    fun `verify reports rules that do nothing`() {
        val problems = specOf { classTarget(OWNER) { field("secret", "I") {} } }.verify(null)

        assertEquals(1, problems.size, problems.toString())
        assertContains(problems.single(), "does nothing")
    }

    @Test
    fun `verify reports contradictory visibilities`() {
        val problems = specOf {
            classTarget(OWNER) { field("secret", "I") { makePublic(); makePrivate() } }
        }.verify(null)

        assertContains(problems.single(), "multiple visibilities")
    }

    @Test
    fun `verify reports descriptors written in the wrong shape`() {
        val asMethod = specOf {
            classTarget(OWNER) { field("secret", desc = "()V") { makePublic() } }
        }.verify(null)
        assertContains(asMethod.single(), "wants a type descriptor")

        val asType = specOf {
            classTarget(OWNER) { methodAccess("locked", desc = "I") { makePublic() } }
        }.verify(null)
        assertContains(asType.single(), "cannot be parsed or is non-canonical")

        val void = specOf {
            classTarget(OWNER) { field("secret", desc = "V") { makePublic() } }
        }.verify(null)
        assertContains(void.single(), "cannot be void")

        val ok = specOf {
            classTarget(OWNER) { methodAccess("locked", desc = "()I") { removeFinal() } }
        }.verify(null)
        assertEquals(emptyList(), ok)
    }

    @Test
    fun `verify catches a descriptor passed as a positional argument`() {
        // Member names form a vararg, so the descriptor must be written as desc = …; passing it as a
        // positional argument turns it into "another name" and the rule never matches any member —
        // this kind of error is silent at runtime.
        val problems = specOf {
            classTarget(OWNER) { field("proxy", "Ljava/net/Proxy;") { makePublic() } }
        }.verify(null)

        assertContains(problems.single(), "descriptor")
    }

    @Test
    fun `verify reports a rule without member names`() {
        val problems = specOf { classTarget(OWNER) { field("secret") { makePublic() } } }.verify(null)
        assertEquals(emptyList(), problems)

        // An empty name can only come from internal construction (the DSL's vararg gives at least one);
        // here the rule model itself is validated directly
        val rule = AccessRule(
            kind = MemberKind.FIELD, names = emptySet(), desc = null,
            visibilities = listOf(Visibility.PUBLIC), removeFinal = false,
            require = null, expect = null, allow = null, optional = false,
        )
        val report = InjectionVerifier.verifyAccessRule(OWNER, rule)
        assertTrue(report.any { it.contains("member name") }, report.toString())
    }

    // ---------- hit-count policies ----------

    @Test
    fun `hit policies guard the access rules too`() {
        val node = target()

        // Wrong name ⇒ no member matches ⇒ require(1) fails hard
        val missing = specOf { classTarget(OWNER) { field("nope", "I") { makePublic(); require(1) } } }
        assertContains(
            assertFailsWith<InjectionError> { missing.transform(TransformContext(OWNER, node)) }.message.orEmpty(),
            "hit count too low",
        )

        // Selector too wide (both fields hit) ⇒ allow(1) fails hard
        val tooWide = specOf {
            classTarget(OWNER) { field("secret", "VERSION") { makePublic(); allow(1) } }
        }
        assertContains(
            assertFailsWith<InjectionError> { tooWide.transform(TransformContext(OWNER, node)) }.message.orEmpty(),
            "hit count exceeded",
        )

        // optional() mutes: same non-match, but no throw
        val optional = specOf { classTarget(OWNER) { field("nope", "I") { makePublic(); optional() } } }
        assertFalse(optional.transform(TransformContext(OWNER, node)))
    }

    // ---------- no change / no rule ----------

    @Test
    fun `a no-op rule reports no change`() {
        // STABLE is already public static final: removing final would change it, promoting visibility
        // would not — so promoting alone means no change
        val node = target()
        val spec = specOf { classTarget(OWNER) { field("STABLE", "I") { makePublic() } } }

        assertFalse(
            spec.transform(TransformContext(OWNER, node)),
            "if the flags did not change, it must not report changed",
        )
        assertEquals(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL,
            node.fields.first { it.name == "STABLE" }.access,
        )
    }

    @Test
    fun `access rules and method-body injection coexist on one class`() {
        // Two kinds of rules coexist on one class: the access flags land first, injection still happens
        val node = target()
        val spec = specOf {
            classTarget(OWNER) {
                field("secret", "I") { makePublic() }
                method("locked", desc = "()I") {
                    atHead { call("omlacc/Hooks", "touched", "()V") }
                }
            }
        }

        assertTrue(spec.transform(TransformContext(OWNER, node)))
        assertTrue(node.fields.first { it.name == "secret" }.access and Opcodes.ACC_PUBLIC != 0)
        assertTrue(
            node.methods.first { it.name == "locked" }.instructions.toArray()
                .filterIsInstance<MethodInsnNode>().any { it.name == "touched" },
            "injection should happen the same way",
        )
    }

    // ---------- fixtures ----------

    private class Probe(val loader: ClassLoader, val node: ClassNode, val clazz: Class<*>)

    private class ProbeLoader : ClassLoader(AccessRulesTest::class.java.classLoader) {
        fun define(name: String, bytes: ByteArray): Class<*> =
            defineClass(name.replace('/', '.'), bytes, 0, bytes.size)
    }

    /** Apply the rule ([spec] of null = attach nothing, used as baseline), write it out in the
     *  **production write-back mode** and define it in a fresh loader. */
    private fun build(spec: InjectionSpec?, node: ClassNode = target()): Probe {
        spec?.transform(TransformContext(node.name, node))
        val loader = ProbeLoader()
        return Probe(loader, node, loader.define(node.name, write(node)))
    }

    private fun define(node: ClassNode): Class<*> = ProbeLoader().define(node.name, write(node))

    /** The production write-back mode: keep the existing stack frames, only recompute maxStack/maxLocals. */
    private fun write(node: ClassNode): ByteArray {
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    private fun targetSpec(block: ClassTargetBuilder.() -> Unit): InjectionSpec =
        specOf { classTarget(OWNER, block) }

    private fun node(access: Int, name: String, superName: String): ClassNode = ClassNode(Opcodes.ASM9).also {
        it.version = Opcodes.V17
        it.access = access
        it.name = name
        it.superName = superName
    }

    private fun ClassNode.field(access: Int, name: String, desc: String, value: Any? = null) {
        fields.add(FieldNode(access, name, desc, null, value))
    }

    private fun ClassNode.method(access: Int, name: String, desc: String, body: InsnList.() -> Unit = {}) {
        methods.add(MethodNode(access, name, desc, null, null).apply { body(instructions) })
    }

    private fun ClassNode.ctor(superOwner: String) {
        method(Opcodes.ACC_PUBLIC, "<init>", "()V") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESPECIAL, superOwner, "<init>", "()V", false))
            add(InsnNode(Opcodes.RETURN))
        }
    }

    /**
     * Target class: private field / private static final field / public static final field /
     * protected final method / `<clinit>`.
     */
    private fun target(finalClass: Boolean = true): ClassNode =
        node(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER or (if (finalClass) Opcodes.ACC_FINAL else 0),
            OWNER, "java/lang/Object",
        ).apply {
            field(Opcodes.ACC_PRIVATE, "secret", "I")
            field(Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "VERSION", "I", 1)
            field(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "STABLE", "I", 2)

            method(Opcodes.ACC_PUBLIC, "<init>", "()V") {
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
                add(VarInsnNode(Opcodes.ALOAD, 0))
                add(InsnNode(Opcodes.ICONST_5))
                add(FieldInsnNode(Opcodes.PUTFIELD, OWNER, "secret", "I"))
                add(InsnNode(Opcodes.RETURN))
            }
            method(Opcodes.ACC_PROTECTED or Opcodes.ACC_FINAL, "locked", "()I") {
                add(IntInsnNode(Opcodes.BIPUSH, 7))
                add(InsnNode(Opcodes.IRETURN))
            }
            method(Opcodes.ACC_STATIC, "<clinit>", "()V") {
                add(InsnNode(Opcodes.ICONST_1))
                add(FieldInsnNode(Opcodes.PUTSTATIC, OWNER, "VERSION", "I"))
                add(InsnNode(Opcodes.RETURN))
            }
        }

    /** Nested class: the class file's own flags and the InnerClasses self-entry are both initially `private static`. */
    private fun nested(): ClassNode = node(Opcodes.ACC_SUPER, INNER, "java/lang/Object").apply {
        innerClasses.add(
            InnerClassNode(INNER, "omlacc/AccessTarget", "Inner", Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC),
        )
        ctor("java/lang/Object")
    }

    /** Interface: fields are necessarily public static final; `run` is abstract, `helper` has a body. */
    private fun iface(): ClassNode = node(
        Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT,
        "omlacc/IAccess", "java/lang/Object",
    ).apply {
        field(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC or Opcodes.ACC_FINAL, "X", "I", 1)
        method(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, "run", "()I")
        method(Opcodes.ACC_PUBLIC, "helper", "()I") {
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IRETURN))
        }
    }

    /** Define `SUB extends OWNER` in the same loader; with [override] it overrides `locked()`. */
    private fun subclass(loader: ClassLoader, override: Boolean): Class<*> {
        val node = node(Opcodes.ACC_PUBLIC or Opcodes.ACC_SUPER, SUB, OWNER).apply {
            ctor(OWNER)
            if (override) {
                method(Opcodes.ACC_PUBLIC, "locked", "()I") {
                    add(IntInsnNode(Opcodes.BIPUSH, 99))
                    add(InsnNode(Opcodes.IRETURN))
                }
            }
        }
        val writer = object : ClassWriter(COMPUTE_FRAMES) {
            override fun getClassLoader(): ClassLoader = loader
        }
        node.accept(writer)
        val bytes = writer.toByteArray()
        return (loader as ProbeLoader).define(SUB, bytes)
    }
}
