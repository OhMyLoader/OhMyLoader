package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodNode
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionBuilder
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.*

/**
 * Validation layer: hit-count policies (require/expect/optional), statically decidable rule errors
 * (hard failures), and the startup-time self-check [InjectionSpec.verify].
 */
class InjectionVerificationTest {

    private fun spec(block: InjectionBuilder.() -> Unit): InjectionSpec = specOf(block)

    private fun classNode(name: String, vararg methods: MethodNode): ClassNode =
        ClassNode(Opcodes.ASM9).apply {
            this.name = name
            this.methods.addAll(methods)
        }

    private fun methodNode(
        name: String,
        desc: String,
        isStatic: Boolean = false,
        build: InsnList.() -> Unit = { add(InsnNode(Opcodes.RETURN)) },
    ): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or if (isStatic) Opcodes.ACC_STATIC else 0, name, desc, null, null)
            .apply { instructions.build() }

    // ---------- hit-count policies ----------

    @Test
    fun `require satisfied does not throw`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    require(1)
                    atHead { omlCall("hook") }
                }
            }
        }
        val cls = classNode("a/B", methodNode("m", "()V"))

        assertTrue(spec.transform(TransformContext("a/B", cls)))
    }

    @Test
    fun `require unsatisfied fails hard`() {
        val spec = spec {
            classTarget("a/B") {
                method("nope", desc = "()V") {
                    require(1)
                    atHead { omlCall("hook") }
                }
            }
        }
        val cls = classNode("a/B", methodNode("m", "()V"))

        val error = assertFailsWith<InjectionError> { spec.transform(TransformContext("a/B", cls)) }
        assertTrue(error.message!!.contains("require=1"), error.message)
        assertTrue(error.message!!.contains("but got 0"), error.message)
    }

    @Test
    fun `optional rule may miss silently`() {
        val spec = spec {
            classTarget("a/B") {
                method("nope", desc = "()V") {
                    optional()
                    atTail { omlCall("hook") }
                }
            }
        }
        val cls = classNode("a/B", methodNode("m", "()V"))

        assertFalse(spec.transform(TransformContext("a/B", cls)))
    }

    @Test
    fun `expect mismatch only warns`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    expect(2)
                    atHead { omlCall("hook") }
                }
            }
        }
        val cls = classNode("a/B", methodNode("m", "()V"))

        // An expect mismatch only warns: injection still completes, no throw
        assertTrue(spec.transform(TransformContext("a/B", cls)))
    }

    // ---------- statically decidable errors: hard failures ----------

    @Test
    fun `arg type mismatch fails hard`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    atHead { call("x/Y", "go", "(I)V", args = listOf(DslValue.Str("oops"))) }
                }
            }
        }
        val cls = classNode("a/B", methodNode("m", "()V"))

        val error = assertFailsWith<InjectionError> { spec.transform(TransformContext("a/B", cls)) }
        assertTrue(error.message!!.contains("argument type does not match"), error.message)
        assertTrue(error.message!!.contains("java.lang.String"), error.message)
    }

    @Test
    fun `int-like arg types are interchangeable on the stack`() {
        // boolean/byte/char/short are all int on the stack, so declaring (Z)V while pushing an int is
        // not an error
        val spec = spec {
            classTarget("a/B") {
                method("m", "(Z)V") {
                    atHead { call("x/Y", "go", "(I)V", args = listOf(DslValue.Arg(0))) }
                }
            }
        }
        val cls = classNode("a/B", methodNode("m", "(Z)V"))

        assertTrue(spec.transform(TransformContext("a/B", cls)))
    }

    @Test
    fun `reference arg into Object parameter is accepted`() {
        // The real displayGuiScreen hook does exactly this: passing a GuiScreen to a parameter declared
        // as Object (a legal upcast). Deciding who is whose supertype needs the class hierarchy, but
        // the engine runs inside findClass where loading classes is not appropriate — so this is left
        // to the JVM verifier.
        val spec = spec {
            classTarget("a/B") {
                method("m", "(La/B;)V") {
                    atHead { call("x/Y", "go", "(Ljava/lang/Object;)V", args = listOf(DslValue.Arg(0))) }
                }
            }
        }
        val cls = classNode("a/B", methodNode("m", "(La/B;)V"))

        assertTrue(spec.transform(TransformContext("a/B", cls)))
    }

    @Test
    fun `arg count mismatch fails hard`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    atHead { call("x/Y", "go", "(ILjava/lang/String;)V", args = listOf(DslValue.IntVal(1))) }
                }
            }
        }
        val cls = classNode("a/B", methodNode("m", "()V"))

        val error = assertFailsWith<InjectionError> { spec.transform(TransformContext("a/B", cls)) }
        assertTrue(error.message!!.contains("argument count does not match"), error.message)
    }

    @Test
    fun `transformReturn extras type mismatch fails hard`() {
        val cls = classNode(
            "a/B",
            methodNode("m", "(I)J") {
                add(InsnNode(Opcodes.LCONST_0))
                add(InsnNode(Opcodes.LRETURN))
            },
        )
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "(I)J") {
                    atTail {
                        // The 2nd parameter is declared as int but a String is pushed
                        transformReturn("x/Y", "tweak", "(JI)J", extras = listOf(DslValue.Str("nope")))
                    }
                }
            }
        }

        val error = assertFailsWith<InjectionError> { spec.transform(TransformContext("a/B", cls)) }
        assertTrue(error.message!!.contains("TransformReturn.extras"), error.message)
    }

    // ---------- startup-time self-check: verify() ----------

    @Test
    fun `verify accepts a real static handler`() {
        // OMLCore.onMinecraftReady()V is a real existing @JvmStatic method
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atHead { omlCall("onMinecraftReady") } }
            }
        }

        assertEquals(emptyList(), spec.verify())
    }

    @Test
    fun `verify reports missing handler on resolvable class`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atHead { omlCall("definitelyNotAMethod") } }
            }
        }

        val problems = spec.verify()
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems[0].contains("does not exist"), problems[0])
        assertTrue(problems[0].contains("org.ohmyloader.core.OMLCore"), problems[0])
    }

    @Test
    fun `verify reports non static handler`() {
        // ModContainer.getId() is an ordinary instance method — injection uses INVOKESTATIC, so it
        // must be reported
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    atHead {
                        call(
                            "org/ohmyloader/core/mod/ModContainer",
                            "getId",
                            "()Ljava/lang/String;",
                        )
                    }
                }
            }
        }

        val problems = spec.verify()
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems[0].contains("must be static"), problems[0])
    }

    @Test
    fun `verify reports descriptor mismatch`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atHead { omlCall("onMinecraftReady", desc = "(I)V") } }
            }
        }

        val problems = spec.verify()
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems[0].contains("does not match"), problems[0])
    }

    @Test
    fun `verify tolerates unresolvable handler owner`() {
        // The handler may come from a class loaded later: warn only, do not treat as an error
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atHead { call("x/Y", "go", "()V") } }
            }
        }

        val problems = spec.verify()
        assertEquals(1, problems.size, problems.toString())
        assertTrue(problems[0].contains("skipping verification"), problems[0])
    }

    @Test
    fun `verify reports inconsistent transformReturn shape`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    atTail { transformReturn("x/Y", "tweak", "(Ljava/lang/String;)I") }
                }
            }
        }

        val problems = spec.verify()
        assertTrue(problems.any { it.contains("must be shaped") }, problems.toString())
    }

    @Test
    fun `verify reports tail anchored checkCall`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atTail { cancellableCall("x/Y", "check", "()Z") } }
            }
        }

        val problems = spec.verify()
        assertTrue(problems.any { it.contains("CheckCall only supports method-entry anchors") }, problems.toString())
    }

    @Test
    fun `verify reports This inside constructor`() {
        val spec = spec {
            classTarget("a/B") {
                constructor(desc = "()V") {
                    atHead { call("x/Y", "go", "(La/B;)V", args = listOf(DslValue.This)) }
                }
            }
        }

        val problems = spec.verify()
        assertTrue(problems.any { it.contains("This cannot be used in a constructor") }, problems.toString())
    }

    @Test
    fun `verify reports rule without injection points`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { }
            }
        }

        val problems = spec.verify()
        assertTrue(problems.any { it.contains("no injection point") }, problems.toString())
    }

    @Test
    fun `verify reports negative require`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    require(-1)
                    atHead { omlCall("onMinecraftReady") }
                }
            }
        }

        val problems = spec.verify()
        assertTrue(problems.any { it.contains("require must not be negative") }, problems.toString())
    }
}
