package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.BasicValue
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.local
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.*

/**
 * Local-variable reading (data-flow analysis) — two things only verifiable by **actually running** the
 * code:
 * 1. **Whether slot resolution is correct** — the slot located by type, the type inferred, and the load
 *    instruction selected; if any link is wrong it either raises a `VerifyError` or reads the value of a
 *    different variable. So the main cases all follow "define the class → invoke via reflection →
 *    assert what the handler actually received".
 * 2. **Whether it fails when it should** — writing `index` is itself an assertion about the slot: a
 *    contradiction with the data flow must **throw**, not silently read garbage (`assertFailsWith<InjectionError>`).
 */
class InjectionLocalTest {

    private val owner = "omltest/LocalTarget"

    // ---------- reading by slot (index given) ----------

    @Test
    fun `explicit index with matching type reads the local`() {
        val spec = pickSpec(listOf(local(index = 2, type = "Ljava/lang/String;")), "(Ljava/lang/String;)V")
        val clazz = define(spec, pickTarget())

        LocalHandlers.clear()
        invokeStatic(clazz, "pick", 7)

        assertEquals(listOf<Any?>("tag"), LocalHandlers.values)
    }

    @Test
    fun `explicit index without type infers the load from the frame`() {
        // Slot 2 holds the String built by StringBuilder/LDC — when no type is given, the load is inferred as ALOAD from the data flow
        val spec = pickSpec(listOf(local(index = 2)), "(Ljava/lang/String;)V")
        val clazz = define(spec, pickTarget())

        LocalHandlers.clear()
        invokeStatic(clazz, "pick", 7)

        assertEquals(listOf<Any?>("tag"), LocalHandlers.values)
    }

    @Test
    fun `explicit index with a contradicting type hard fails`() {
        // Slot 2 is a String but the rule declares it as int — this is a **miswritten rule** and must throw rather than silently read garbage
        val spec = pickSpec(listOf(local(index = 2, type = "I")), "(I)V")

        val error = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, pickTarget()))
        }
        assertTrue(error.message!!.contains("slot 2"), error.message)
        assertTrue(error.message!!.contains("Ljava/lang/String;"), error.message)
    }

    @Test
    fun `reading a dead slot hard fails`() {
        // Slot 7 is never written in this code (the frame slot is empty) — reading it would only fetch garbage, so it must be blocked
        val spec = pickSpec(listOf(local(index = 7, type = "I")), "(I)V")

        val error = assertFailsWith<InjectionError> {
            spec.transform(TransformContext(owner, pickTarget()))
        }
        assertTrue(error.message!!.contains("no live value"), error.message)
    }

    @Test
    fun `boolean argument can be read as an int local`() {
        // The int family (Z/B/C/S/I) is shape-identical on the JVM, so declaring I to read a Z parameter is legal
        // (static method: parameters start at slot 0)
        val spec = specOf {
            classTarget(owner) {
                method("flag", desc = "(Z)I") {
                    require(1)
                    atHead {
                        call(
                            LocalHandlers.OWNER, "note", "(I)V",
                            args = listOf(local(index = 0, type = "I")),
                        )
                    }
                }
            }
        }
        val clazz = define(spec, flagTarget())

        LocalHandlers.clear()
        invokeStatic(clazz, "flag", true)

        assertEquals(listOf<Any?>(1), LocalHandlers.values)
    }

    // ---------- reading by type (type only) ----------

    @Test
    fun `by-type resolution finds a method-body local`() {
        // In pick(int a), locals = [a(I), doubled(I), tag(String)]:
        // the first String is slot 2 — this is the path needed only when the slot is statically unknowable
        val spec = pickSpec(listOf(local(type = "Ljava/lang/String;")), "(Ljava/lang/String;)V")
        val clazz = define(spec, pickTarget())

        LocalHandlers.clear()
        invokeStatic(clazz, "pick", 7)

        assertEquals(listOf<Any?>("tag"), LocalHandlers.values)
    }

    @Test
    fun `ordinal picks the Nth local of that type`() {
        // locals = [a(I), doubled(I), tag(String)]: the 2nd I (ordinal=1) is doubled = a*2
        val spec = pickSpec(listOf(local(type = "I", ordinal = 1)), "(I)V")
        val clazz = define(spec, pickTarget())

        LocalHandlers.clear()
        invokeStatic(clazz, "pick", 7)

        assertEquals(listOf<Any?>(14), LocalHandlers.values)
    }

    @Test
    fun `exact descriptor wins over int-family fallback`() {
        // flag(Z)I has locals = [on(Z)] (static method, slot 0 is the parameter):
        // requesting Z must yield this real boolean, not another slot that merely belongs to the int family
        val spec = specOf {
            classTarget(owner) {
                method("flagPair", desc = "(ZI)I") {
                    require(1)
                    atHead {
                        call(
                            LocalHandlers.OWNER, "note", "(I)V",
                            args = listOf(local(type = "Z")),
                        )
                    }
                }
            }
        }
        val clazz = define(spec, flagPairTarget())

        LocalHandlers.clear()
        invokeStatic(clazz, "flagPair", false, 99)

        assertEquals(listOf<Any?>(0), LocalHandlers.values, "must pick up that boolean (false), not the int parameter")
    }

    @Test
    fun `unresolvable type skips the injection instead of failing`() {
        // A rule may match several overloads and only some have that local, so an unresolved lookup just skips
        // (note: **cannot** be marked require here — skipping is a legitimate outcome; pinning a hit relies on require/expect)
        val spec = specOf {
            classTarget(owner) {
                method("pick", desc = "(I)Ljava/lang/String;") {
                    beforeCall(owner = LocalSink.OWNER, name = "two") {
                        call(LocalHandlers.OWNER, "note", "(J)V", args = listOf(local(type = "J")))
                    }
                }
            }
        }

        val changed = spec.transform(TransformContext(owner, pickTarget()))

        assertFalse(changed, "an unresolvable capture should skip the whole injection, not fail hard")
    }

    @Test
    fun `this is not a candidate for by-type resolution`() {
        // In an instance method, slot 0 is `this`; it takes no part in by-type resolution (use `This` to get
        // the target instance), otherwise "the 1st reference-type local" would be misaligned because of `this`
        val spec = specOf {
            classTarget(owner) {
                method("greet", desc = "(Ljava/lang/String;)Ljava/lang/String;") {
                    atHead {
                        call(
                            LocalHandlers.OWNER, "noteStr", "(Ljava/lang/String;)V",
                            args = listOf(local(type = "L$owner;")),
                        )
                    }
                }
            }
        }

        val changed = spec.transform(TransformContext(owner, greetTarget()))

        assertFalse(changed, "this must not be a candidate (otherwise it would read the target instance)")
    }

    @Test
    fun `instance method argument resolves to slot one, not this`() {
        val spec = specOf {
            classTarget(owner) {
                method("greet", desc = "(Ljava/lang/String;)Ljava/lang/String;") {
                    require(1)
                    atHead {
                        call(
                            LocalHandlers.OWNER, "noteStr", "(Ljava/lang/String;)V",
                            args = listOf(local(type = "Ljava/lang/String;")),
                        )
                    }
                }
            }
        }
        val clazz = define(spec, greetTarget())

        LocalHandlers.clear()
        val instance = clazz.getDeclaredConstructor().newInstance()
        clazz.getMethod("greet", String::class.java).invoke(instance, "hi")

        assertEquals(listOf<Any?>("hi"), LocalHandlers.values)
    }

    // ---------- the data flow itself ----------

    @Test
    fun `unreachable anchor yields no locals`() {
        // A call site in dead code: the frame is empty, so the injection is skipped (not a hard failure — the rule's slot itself is not wrong)
        val spec = specOf {
            classTarget(owner) {
                method("dead", desc = "(I)V") {
                    beforeCall(owner = LocalSink.OWNER, name = "two") {
                        call(
                            LocalHandlers.OWNER, "note", "(I)V",
                            args = listOf(local(index = 1, type = "I")),
                        )
                    }
                }
            }
        }

        val changed = spec.transform(TransformContext(owner, deadCodeTarget()))

        assertFalse(changed, "an unreachable injection point must not inject")
    }

    @Test
    fun `merged references degrade to Object instead of guessing`() {
        // The two paths write a String and an Integer into slot 1 respectively; after the merge they degrade
        // to Object (no class loading, no parent/child guessing), so "want a String" resolves to nothing,
        // but "read as Object" still works
        val node = branchyTarget()
        val method = node.methods.single { it.name == "branchy" }
        val anchor = method.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
            .single { it.owner == LocalSink.OWNER }

        val locals = MethodFrames(owner, method).at(anchor, after = false).frame()!!

        assertEquals(
            "Ljava/lang/Object;",
            locals.live(1)!!.descriptor,
            "merged references of the same category degrade to Object"
        )
        assertIs<LocalResolution.Found>(
            locals.resolve(DslValue.Local(index = 1, type = "Ljava/lang/Object;"), isStatic = true)
        )
        assertIs<LocalResolution.Missing>(
            locals.resolve(DslValue.Local(type = "Ljava/lang/String;"), isStatic = true)
        )
    }

    @Test
    fun `analysis failure is reported and never crashes the transform`() {
        // Malformed bytecode (POP on an empty stack, maxStack = 0): `Analyzer` throws AnalyzerException.
        // The engine must degrade to "no locals readable + explain why", not throw the exception into class loading.
        val method = MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, "m", "()V", null, null)
        method.instructions.add(InsnNode(Opcodes.POP))
        method.instructions.add(InsnNode(Opcodes.RETURN))
        method.maxStack = 0
        method.maxLocals = 0

        val locals = MethodFrames(owner, method).at(method.instructions.first, after = false)

        assertNull(locals.frame())
        assertTrue(locals.explain().contains("dataflow analysis"), locals.explain())
    }

    // ---------- the resolver itself (no bytecode involved, covers more edge cases) ----------

    @Test
    fun `resolver handles the slot table edge cases`() {
        val frame = LocalFrame(
            listOf(
                BasicValue(Type.getObjectType(owner)),          // 0 = this
                BasicValue(Type.INT_TYPE),                      // 1
                BasicValue(Type.BOOLEAN_TYPE),                  // 2
                BasicValue(Type.getType("Ljava/lang/String;")), // 3
                BasicValue(Type.LONG_TYPE), null,               // 4 (+5 is the upper half-slot)
                null,                                           // 6 = dead slot
            )
        )

        fun found(index: Int? = null, type: String? = null, ordinal: Int = 0, isStatic: Boolean = false) =
            frame.resolve(DslValue.Local(index, type, ordinal), isStatic)

        assertEquals(LocalResolution.Found(3, Type.getType("Ljava/lang/String;")), found(index = 3))
        assertEquals(LocalResolution.Found(2, Type.BOOLEAN_TYPE), found(index = 2))
        assertEquals(LocalResolution.Found(4, Type.LONG_TYPE), found(index = 4))
        assertEquals(LocalResolution.Found(3, Type.getType("Ljava/lang/String;")), found(type = "Ljava/lang/String;"))
        // Exact match wins: requesting Z hits the boolean in slot 2, not the int in slot 1
        assertEquals(LocalResolution.Found(2, Type.BOOLEAN_TYPE), found(type = "Z"))
        // In a static method, slot 0 takes part in resolution
        assertEquals(LocalResolution.Found(1, Type.INT_TYPE), found(type = "I", isStatic = true))

        assertIs<LocalResolution.Missing>(found(index = 6, type = "I"))   // dead slot
        assertIs<LocalResolution.Missing>(found(index = 5, type = "I"))   // upper half-slot of a long
        assertIs<LocalResolution.Missing>(found(index = 3, type = "I"))   // type contradiction
        assertIs<LocalResolution.Missing>(found(index = -1))              // negative slot
        assertIs<LocalResolution.Missing>(found())                        // nothing given
        assertEquals(LocalResolution.Found(4, Type.LONG_TYPE), found(type = "J"))
        assertIs<LocalResolution.Missing>(found(type = "F"))              // no float in the table
        // `this` takes no part in by-type resolution: only slot 0 has the owner type, so it is not found
        assertIs<LocalResolution.Missing>(found(type = "L$owner;"))
    }

    // ---------- scaffolding ----------

    private fun pickSpec(args: List<DslValue>, handlerDesc: String): InjectionSpec = specOf {
        classTarget(owner) {
            method("pick", desc = "(I)Ljava/lang/String;") {
                require(1)
                beforeCall(owner = LocalSink.OWNER, name = "two") {
                    call(LocalHandlers.OWNER, handlerName(handlerDesc), handlerDesc, args = args)
                }
            }
        }
    }

    private fun handlerName(desc: String): String = when (desc) {
        "(Ljava/lang/String;)V" -> "noteStr"
        else -> "note"
    }

    /**
     * `static String pick(int a) { int doubled = a*2; String tag = "tag"; LocalSink.two(tag, doubled); return tag; }`
     *
     * Local-variable table: static, so not `0=this` → 0=a(I), 1=doubled(I), 2=tag(String)
     */
    private fun pickTarget(): ClassNode = classNode(
        staticMethod("pick", "(I)Ljava/lang/String;") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_2))
            add(InsnNode(Opcodes.IMUL))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(LdcInsnNode("tag"))
            add(VarInsnNode(Opcodes.ASTORE, 2))
            add(VarInsnNode(Opcodes.ALOAD, 2))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, LocalSink.OWNER, "two", "(Ljava/lang/String;I)V", false))
            add(VarInsnNode(Opcodes.ALOAD, 2))
            add(InsnNode(Opcodes.ARETURN))
        }
    )

    /** `static int flag(boolean on) { return on; }` — used to demonstrate the int-family interchange (static: the parameter sits in slot 0). */
    private fun flagTarget(): ClassNode = classNode(
        staticMethod("flag", "(Z)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.IRETURN))
        }
    )

    /** `static int flagPair(boolean on, int n) { return n; }` — Z and I are present together (slots 0 and 1). */
    private fun flagPairTarget(): ClassNode = classNode(
        staticMethod("flagPair", "(ZI)I") {
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IRETURN))
        }
    )

    /** The instance method `String greet(String name) { return name; }` (slot 0 is `this`). */
    private fun greetTarget(): ClassNode = classNode(
        MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
            instructions.add(MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false))
            instructions.add(InsnNode(Opcodes.RETURN))
            maxStack = 1
            maxLocals = 1
        },
        MethodNode(Opcodes.ACC_PUBLIC, "greet", "(Ljava/lang/String;)Ljava/lang/String;", null, null).apply {
            instructions.add(VarInsnNode(Opcodes.ALOAD, 1))
            instructions.add(InsnNode(Opcodes.ARETURN))
            maxStack = 1
            maxLocals = 2
        },
    )

    /** A call placed in dead code — that injection point has no frame. */
    private fun deadCodeTarget(): ClassNode = classNode(
        staticMethod("dead", "(I)V") {
            add(InsnNode(Opcodes.RETURN))
            add(LdcInsnNode("unused"))
            add(IntInsnNode(Opcodes.BIPUSH, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, LocalSink.OWNER, "two", "(Ljava/lang/String;I)V", false))
            add(InsnNode(Opcodes.RETURN))
        }
    )

    /**
     * Two paths write a String and an Integer into the **same slot**, then merge and call — so slot 1 is Object in the frame.
     *
     * This fixture is used only for **analysis** (the class is not defined), so no frames need to be handwritten.
     */
    private fun branchyTarget(): ClassNode = classNode(
        staticMethod("branchy", "(Z)V") {
            val elseLabel = LabelNode()
            val joinLabel = LabelNode()
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(JumpInsnNode(Opcodes.IFEQ, elseLabel))
            add(LdcInsnNode("text"))
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(JumpInsnNode(Opcodes.GOTO, joinLabel))
            add(elseLabel)
            add(InsnNode(Opcodes.ICONST_1))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false))
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(joinLabel)
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Object"))
            add(InsnNode(Opcodes.POP))
            add(LdcInsnNode("x"))
            add(InsnNode(Opcodes.ICONST_0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, LocalSink.OWNER, "two", "(Ljava/lang/String;I)V", false))
            add(InsnNode(Opcodes.RETURN))
        }
    )

    private fun classNode(vararg methods: MethodNode): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = owner
            superName = "java/lang/Object"
            this.methods.addAll(methods)
        }

    private fun staticMethod(name: String, desc: String, build: InsnList.() -> Unit): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC, name, desc, null, null).apply {
            instructions.build()
            maxStack = 8
            maxLocals = 8
        }

    /** Inject → write back → define and link (`VerifyError` is thrown here). */
    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionLocalTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invokeStatic(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)
}

/** The target class calls it to create a stable, version-independent injection anchor. */
object LocalSink {
    const val OWNER: String = "org/ohmyloader/core/transformer/injection/LocalSink"

    val calls = mutableListOf<List<Any?>>()

    /** Records the local variable the target method computed — so the JIT cannot optimize the whole body away. */
    @JvmStatic
    fun two(text: String?, n: Int) {
        calls += listOf(text, n)
    }
}

/** Handlers (all must be static — the engine emits INVOKESTATIC). */
object LocalHandlers {
    const val OWNER: String = "org/ohmyloader/core/transformer/injection/LocalHandlers"

    val values = mutableListOf<Any?>()

    fun clear() = values.clear()

    @JvmStatic
    fun note(v: Int) {
        values += v
    }

    @JvmStatic
    fun noteStr(v: String) {
        values += v
    }
}
