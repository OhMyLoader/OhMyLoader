package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.ClassTargetBuilder
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.MethodRuleBuilder
import org.ohmyloader.api.inject.PayloadBuilder
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Local-variable rewriting (`ModifyVariable`).
 *
 * Its failure mode is the same kind as value-short-circuiting — **illegal bytecode** (a wrongly
 * chosen `ILOAD/ALOAD`, an incorrect write-back slot, or a value type that does not match the
 * variable), which only surfaces when the class is **defined** (`VerifyError`). So the main cases all
 * follow "build the target class → inject → define → invoke via reflection → assert the return value".
 * The other three semantics can only be told apart by actual execution: a `Store` lands **right after
 * the write**, a `Load` **right before the read**; `ordinal` counts "which read/write of that variable
 * this is" while `localOrdinal` counts "which slot of that type"; the `type` filter must be able to
 * exclude `long` writes (otherwise a `(I)I` handler would trip over an `LSTORE`).
 */
class InjectionVariableTest {

    private val owner = "omltest/VarTarget"

    // ---------- Store: rewrite right after the write ----------

    @Test
    fun `afterStore rewrites the value that was just written`() {
        val clazz = define(
            storeSpec(
                target = "compute",
                desc = "(I)I",
                anchor = { afterStore(index = 1, type = "I", block = it) },
                handler = "add100",
            ),
            computeTarget(),
        )

        // a=3 → x=4 → handler +100 → 104 → *10 = 1040
        assertEquals(1040, invokeStatic(clazz, "compute", 3))
    }

    @Test
    fun `afterStore without index finds the local by type`() {
        // Without an index, it locates that int local (slot 1) by type = "I"
        val clazz = define(
            storeSpec(
                target = "compute",
                desc = "(I)I",
                anchor = { afterStore(type = "I", block = it) },
                handler = "add100",
            ),
            computeTarget(),
        )

        assertEquals(1040, invokeStatic(clazz, "compute", 3))
    }

    @Test
    fun `afterStore ordinal selects which write to rewrite`() {
        // twice(a) = { x = a; x = a + 1; return x; }, slot 1 is written twice
        val first = define(
            storeSpec(
                target = "twice", desc = "(I)I", handler = "times10",
                anchor = { afterStore(index = 1, type = "I", ordinal = 0, block = it) },
            ),
            twiceTarget(),
        )
        // The 1st write is changed to 30, then the 2nd write right after overwrites it back to 4
        assertEquals(4, invokeStatic(first, "twice", 3))

        val second = define(
            storeSpec(
                target = "twice", desc = "(I)I", handler = "times10",
                anchor = { afterStore(index = 1, type = "I", ordinal = 1, block = it) },
            ),
            twiceTarget(),
        )
        // The 2nd write is changed to 40, and nothing overwrites it afterward
        assertEquals(40, invokeStatic(second, "twice", 3))
    }

    @Test
    fun `afterStore localOrdinal selects the nth slot of that type`() {
        // mixed has two writes: slot 3 is ISTORE(x), slot 4 is LSTORE(y).
        // type="I" excludes the LSTORE, so localOrdinal=0 is slot 3.
        val clazz = define(
            storeSpec(
                target = "mixed", desc = "(IJ)J", handler = "add100",
                anchor = { afterStore(type = "I", localOrdinal = 0, block = it) },
            ),
            mixedTarget(),
        )

        // a=3,b=10 → x=4→104、y=11 → 104 + 11 = 115
        assertEquals(115L, invokeStatic(clazz, "mixed", 3, 10L))
    }

    @Test
    fun `without a type filter a long store contradicts an int handler`() {
        // Without a type filter, both writes are hit, and an LSTORE against a (I)I handler is **provably illegal**
        // — it must throw at startup (otherwise we would wait for a runtime VerifyError). This is exactly the
        // reason the type filter exists.
        val spec = specOf {
            classTarget(owner) {
                method("mixed", desc = "(IJ)J") {
                    require(2)
                    afterStore(index = null) { modifyVariable(VarHandlers.OWNER, "add100", "(I)I") }
                }
            }
        }

        val failure = assertFailsWith<InjectionError> { define(spec, mixedTarget()) }
        assertTrue(failure.message!!.contains("slot 4"), failure.message)
    }

    // ---------- Load: rewrite right before the read ----------

    @Test
    fun `beforeLoad rewrites the value on its way out`() {
        val clazz = define(loadSpec(index = 1), readTarget())

        // a=3 → x=4 → +1000 at read time → 1004
        assertEquals(1004, invokeStatic(clazz, "readOnce", 3))
    }

    // ---------- type coverage ----------

    @Test
    fun `long local is rewritten with two slot load and store`() {
        val spec = specOf {
            classTarget(owner) {
                method("mixed", desc = "(IJ)J") {
                    require(1)
                    afterStore(index = 4, type = "J") {
                        modifyVariable(VarHandlers.OWNER, "times2Long", "(J)J")
                    }
                }
            }
        }
        val clazz = define(spec, mixedTarget())

        // y=11 → 22 (times2Long); x=4 → 4 + 22 = 26
        assertEquals(26L, invokeStatic(clazz, "mixed", 3, 10L))
    }

    @Test
    fun `reference local is rewritten without unboxing`() {
        val spec = specOf {
            classTarget(owner) {
                method("tag", desc = "(I)Ljava/lang/String;") {
                    require(1)
                    afterStore(index = 1, type = "Ljava/lang/String;") {
                        modifyVariable(VarHandlers.OWNER, "exclaim", "(Ljava/lang/String;)Ljava/lang/String;")
                    }
                }
            }
        }
        val clazz = define(spec, tagTarget())

        assertEquals("t!7", invokeStatic(clazz, "tag", 7))
    }

    @Test
    fun `Object handler rewrites an Object-typed local`() {
        // A @ModifyVariable handler that returns Object is very common in mixins — support it, but **the slot
        // itself must be able to hold an Object**: the write-back widens that slot's type to Object, and if the
        // following code still uses it as a String it will VerifyError.
        // (Only safe when the target is a reference-typed variable and nothing later uses it as a concrete type.)
        val spec = specOf {
            classTarget(owner) {
                method("objLocal", desc = "(I)Ljava/lang/Object;") {
                    require(1)
                    afterStore(index = 1, type = "Ljava/lang/Object;") {
                        modifyVariable(VarHandlers.OWNER, "exclaimAny", "(Ljava/lang/Object;)Ljava/lang/Object;")
                    }
                }
            }
        }
        val clazz = define(spec, objTarget())

        assertEquals("t!", invokeStatic(clazz, "objLocal", 7))
    }

    @Test
    fun `Object handler also matches a more specific reference local`() {
        // The type filter treats Object as "any reference" (the same meaning as Mixin's discriminator),
        // otherwise a String slot like `tag` would never match. Here we only verify "it matched" — the
        // write-back widens the slot to Object, yet `tag` subsequently calls concat as a String and would
        // necessarily VerifyError, so this class is intentionally not defined.
        val spec = specOf {
            classTarget(owner) {
                method("tag", desc = "(I)Ljava/lang/String;") {
                    require(1)
                    afterStore(index = 1, type = "Ljava/lang/Object;") {
                        modifyVariable(VarHandlers.OWNER, "exclaimAny", "(Ljava/lang/Object;)Ljava/lang/Object;")
                    }
                }
            }
        }
        val node = tagTarget()
        spec.transform(TransformContext(owner, node))

        val astores = node.methods.single { it.name == "tag" }.instructions.toArray()
            .filterIsInstance<VarInsnNode>()
            .filter { it.opcode == Opcodes.ASTORE }
        assertEquals(2, astores.size, "one extra write-back besides the original ASTORE 1")
    }

    // ---------- argsOnly ----------

    @Test
    fun `argsOnly restricts to method arguments`() {
        // argStore(a) = { a = a + 1; x = a; return a + x; } — slot 0 is the parameter, slot 1 a local
        val clazz = define(
            storeSpec(
                target = "argStore", desc = "(I)I", handler = "add100", hits = 1,
                anchor = { afterStore(argsOnly = true, block = it) },
            ),
            argStoreTarget(),
        )

        // a=3 → 4 → +100 → 104; x=104 → 104 + 104 = 208
        assertEquals(208, invokeStatic(clazz, "argStore", 3))
    }

    @Test
    fun `without argsOnly both writes are hit`() {
        val clazz = define(
            storeSpec(
                target = "argStore",
                desc = "(I)I",
                handler = "add100",
                hits = 2,
                anchor = { afterStore(block = it) },
            ),
            argStoreTarget(),
        )

        // a=4→104, x=104→204 → 104 + 204 = 308
        assertEquals(308, invokeStatic(clazz, "argStore", 3))
    }

    // ---------- it must hard-fail when it must hard-fail ----------

    @Test
    fun `handler type that cannot hold the local is rejected`() {
        // The anchor targets an int write, but the handler declares it returns String — provably illegal, so
        // it must throw rather than silently rewrite wrongly
        val spec = specOf {
            classTarget(owner) {
                method("compute", desc = "(I)I") {
                    afterStore(index = 1, type = "I") {
                        modifyVariable(VarHandlers.OWNER, "exclaim", "(Ljava/lang/String;)Ljava/lang/String;")
                    }
                }
            }
        }

        val failure = assertFailsWith<InjectionError> { define(spec, computeTarget()) }
        assertTrue(failure.message!!.contains("slot 1"), failure.message)
    }

    // ---------- rule-level validation ----------

    @Test
    fun `modifyVariable needs a store or load anchor`() {
        val problems = verify {
            method("compute", desc = "(I)I") {
                atHead { modifyVariable(VarHandlers.OWNER, "add100", "(I)I") }
            }
        }

        assertTrue(problems.any { it.contains("afterStore / beforeLoad") }, problems.toString())
    }

    @Test
    fun `handler must return the value type`() {
        val problems = verify {
            method("compute", desc = "(I)I") {
                afterStore(index = 1, type = "I") {
                    modifyVariable(VarHandlers.OWNER, "note", "(I)V")
                }
            }
        }

        assertTrue(problems.any { it.contains("cannot return void") }, problems.toString())
    }

    @Test
    fun `handler first param must equal its return type`() {
        val problems = verify {
            method("compute", desc = "(I)I") {
                afterStore(index = 1, type = "I") {
                    // (Ljava/lang/String;)I — the first parameter and the return type do not match
                    modifyVariable(VarHandlers.OWNER, "bogus", "(Ljava/lang/String;)I")
                }
            }
        }

        assertTrue(problems.any { it.contains("must be shaped (T[, extras...])T") }, problems.toString())
    }

    @Test
    fun `anchor without any discriminator is reported`() {
        val problems = verify {
            method("compute", desc = "(I)I") {
                afterStore { modifyVariable(VarHandlers.OWNER, "add100", "(I)I") }
            }
        }

        assertTrue(problems.any { it.contains("gave no qualifier") }, problems.toString())
    }

    @Test
    fun `localOrdinal requires a type`() {
        val problems = verify {
            method("compute", desc = "(I)I") {
                afterStore(localOrdinal = 1) { modifyVariable(VarHandlers.OWNER, "add100", "(I)I") }
            }
        }

        assertTrue(problems.any { it.contains("type must be written too") }, problems.toString())
    }

    @Test
    fun `index and localOrdinal cannot be combined`() {
        val problems = verify {
            method("compute", desc = "(I)I") {
                afterStore(index = 1, type = "I", localOrdinal = 0) {
                    modifyVariable(VarHandlers.OWNER, "add100", "(I)I")
                }
            }
        }

        assertTrue(problems.any { it.contains("only one can be given") }, problems.toString())
    }

    @Test
    fun `invalid type descriptor is reported`() {
        val problems = verify {
            method("compute", desc = "(I)I") {
                afterStore(index = 1, type = "not-a-desc") {
                    modifyVariable(VarHandlers.OWNER, "add100", "(I)I")
                }
            }
        }

        assertTrue(problems.any { it.contains("is not a legal value-type descriptor") }, problems.toString())
    }

    // ---------- scaffolding ----------

    /** An `afterStore { modifyVariable(...) }` rule (default `require(hits)` pins the hit count). */
    private fun storeSpec(
        target: String,
        desc: String,
        anchor: MethodRuleBuilder.(PayloadBuilder.() -> Unit) -> Unit,
        handler: String,
        hits: Int = 1,
    ): InjectionSpec = specOf {
        classTarget(owner) {
            method(target, desc = desc) {
                require(hits)
                anchor {
                    modifyVariable(VarHandlers.OWNER, handler, handlerDescOf(handler))
                }
            }
        }
    }

    private fun loadSpec(index: Int): InjectionSpec = specOf {
        classTarget(owner) {
            method("readOnce", desc = "(I)I") {
                require(1)
                beforeLoad(index = index, type = "I") {
                    modifyVariable(VarHandlers.OWNER, "add1000", "(I)I")
                }
            }
        }
    }

    private fun handlerDescOf(name: String): String = when (name) {
        "add100", "times10", "add1000" -> "(I)I"
        "times2Long" -> "(J)J"
        "exclaim" -> "(Ljava/lang/String;)Ljava/lang/String;"
        else -> error("unknown handler $name")
    }

    /** Get a rule's startup self-check result. */
    private fun verify(block: ClassTargetBuilder.() -> Unit): List<String> {
        val spec = specOf { classTarget(owner) { block() } }
        return spec.verify()
    }

    /** `static int compute(int a) { int x = a + 1; return x * 10; }` — slot 0=a, slot 1=x. */
    private fun computeTarget(): ClassNode = classNode(
        staticMethod("compute", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IADD))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(IntInsnNode(Opcodes.BIPUSH, 10))
            add(InsnNode(Opcodes.IMUL))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int readOnce(int a) { int x = a + 1; return x; }` — slot 1 is read exactly once. */
    private fun readTarget(): ClassNode = classNode(
        staticMethod("readOnce", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IADD))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /** `static int twice(int a) { int x = a; x = a + 1; return x; }` — slot 1 is written twice. */
    private fun twiceTarget(): ClassNode = classNode(
        staticMethod("twice", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IADD))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IRETURN))
        },
    )

    /**
     * `static long mixed(int a, long b) { int x = a + 1; long y = b + 1; return x + y; }`
     *
     * Slots: 0=a(I), 1..2=b(J), 3=x(I), 4..5=y(J) — an int local coexists with a long local,
     * used to verify that the `type` filter really excludes the `LSTORE`.
     */
    private fun mixedTarget(): ClassNode = classNode(
        staticMethod("mixed", "(IJ)J") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IADD))
            add(VarInsnNode(Opcodes.ISTORE, 3))
            add(VarInsnNode(Opcodes.LLOAD, 1))
            add(InsnNode(Opcodes.LCONST_1))
            add(InsnNode(Opcodes.LADD))
            add(VarInsnNode(Opcodes.LSTORE, 4))
            add(VarInsnNode(Opcodes.ILOAD, 3))
            add(InsnNode(Opcodes.I2L))
            add(VarInsnNode(Opcodes.LLOAD, 4))
            add(InsnNode(Opcodes.LADD))
            add(InsnNode(Opcodes.LRETURN))
        },
    )

    /** `static String tag(int a) { String s = "t"; return s + a; }` — slot 1 is reference-typed. */
    private fun tagTarget(): ClassNode = classNode(
        staticMethod("tag", "(I)Ljava/lang/String;") {
            add(LdcInsnNode("t"))
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/String", "valueOf", "(I)Ljava/lang/String;", false))
            add(
                MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
                    "(Ljava/lang/String;)Ljava/lang/String;", false,
                ),
            )
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static Object objLocal(int a) { Object o = "t"; return o; }` — slot 1 is declared as Object. */
    private fun objTarget(): ClassNode = classNode(
        staticMethod("objLocal", "(I)Ljava/lang/Object;") {
            add(LdcInsnNode("t"))
            add(VarInsnNode(Opcodes.ASTORE, 1))
            add(VarInsnNode(Opcodes.ALOAD, 1))
            add(InsnNode(Opcodes.ARETURN))
        },
    )

    /** `static int argStore(int a) { a = a + 1; int x = a; return a + x; }` — slot 0 the parameter, slot 1 a local. */
    private fun argStoreTarget(): ClassNode = classNode(
        staticMethod("argStore", "(I)I") {
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IADD))
            add(VarInsnNode(Opcodes.ISTORE, 0))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ISTORE, 1))
            add(VarInsnNode(Opcodes.ILOAD, 0))
            add(VarInsnNode(Opcodes.ILOAD, 1))
            add(InsnNode(Opcodes.IADD))
            add(InsnNode(Opcodes.IRETURN))
        },
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
            // long/double take two slots, so maxLocals must be generous
            maxStack = 8
            maxLocals = 8
        }

    /** Inject → write back → define and link (`VerifyError` is thrown here). */
    private fun define(spec: InjectionSpec, node: ClassNode): Class<*> {
        assertTrue(spec.transform(TransformContext(owner, node)), "injection should happen")
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val bytes = writer.toByteArray()
        return object : ClassLoader(InjectionVariableTest::class.java.classLoader) {
            fun define(): Class<*> = defineClass(owner.replace('/', '.'), bytes, 0, bytes.size)
        }.define()
    }

    private fun invokeStatic(clazz: Class<*>, name: String, vararg args: Any?): Any? =
        clazz.methods.first { it.name == name }.invoke(null, *args)
}

/** Handlers (all must be static — the engine emits INVOKESTATIC). */
object VarHandlers {
    const val OWNER: String = "org/ohmyloader/core/transformer/injection/VarHandlers"

    @JvmStatic
    fun add100(v: Int): Int = v + 100

    @JvmStatic
    fun times10(v: Int): Int = v * 10

    @JvmStatic
    fun add1000(v: Int): Int = v + 1000

    @JvmStatic
    fun times2Long(v: Long): Long = v * 2

    @JvmStatic
    fun exclaim(v: String): String = "$v!"

    @JvmStatic
    fun exclaimAny(v: Any?): Any = "$v!"

    /** Exists only for validation cases like "a handler that is not declared". */
    @JvmStatic
    fun note(v: Int) {
    }

    @JvmStatic
    fun bogus(v: String): Int = v.length
}
