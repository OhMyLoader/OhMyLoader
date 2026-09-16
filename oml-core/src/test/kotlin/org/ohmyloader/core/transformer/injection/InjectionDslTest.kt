package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionBuilder
import org.ohmyloader.core.transformer.TransformContext
import kotlin.test.*

class InjectionDslTest {

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
        build: InsnList.() -> Unit
    ): MethodNode =
        MethodNode(Opcodes.ACC_PUBLIC or if (isStatic) Opcodes.ACC_STATIC else 0, name, desc, null, null)
            .apply { instructions.build() }

    // ---- atReturn / atTail: multiple returns must each get their own fresh node
    //      (to prevent a shared InsnList from corrupting the instruction chain) ----

    @Test
    fun `atReturn injects before every return with fresh nodes`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atReturn { omlCall("hook") } }
            }
        }
        val ret1 = InsnNode(Opcodes.RETURN)
        val ret2 = InsnNode(Opcodes.RETURN)
        val cls = classNode("a/B", methodNode("m", "()V") {
            add(ret1)
            add(ret2)
        })

        val changed = spec.transform(TransformContext("a/B", cls))

        assertTrue(changed)
        val callNodes = cls.methods[0].instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(2, callNodes.size)
        assertEquals("org/ohmyloader/core/OMLCore", callNodes[0].owner)
        assertEquals("hook", callNodes[1].name)
        assertNotSame(callNodes[0], callNodes[1])
        // Both returns have the injected call as their direct predecessor
        assertEquals(callNodes[0], ret1.previous)
        assertEquals(callNodes[1], ret2.previous)
    }

    @Test
    fun `atTail injects only before the last return`() {
        // Mixin's TAIL semantics: only the last return (= Cleanroom's BeforeFinalReturn)
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atTail { omlCall("hook") } }
            }
        }
        val ret1 = InsnNode(Opcodes.RETURN)
        val ret2 = InsnNode(Opcodes.RETURN)
        val cls = classNode("a/B", methodNode("m", "()V") {
            add(ret1)
            add(ret2)
        })

        assertTrue(spec.transform(TransformContext("a/B", cls)))

        val callNodes = cls.methods[0].instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(1, callNodes.size, "TAIL should only hit the last return")
        assertEquals(callNodes[0], ret2.previous)
        assertEquals(null, ret1.previous) // the first return must not be touched
    }

    @Test
    fun `atReturn ordinal picks the nth return`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atReturn(ordinal = 1) { omlCall("hook") } }
            }
        }
        val ret1 = InsnNode(Opcodes.RETURN)
        val ret2 = InsnNode(Opcodes.RETURN)
        val cls = classNode("a/B", methodNode("m", "()V") {
            add(ret1)
            add(ret2)
        })

        assertTrue(spec.transform(TransformContext("a/B", cls)))

        val callNodes = cls.methods[0].instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(1, callNodes.size)
        assertEquals(callNodes[0], ret2.previous)
    }

    @Test
    fun `return anchors only match the target method's own return opcode`() {
        // An unreachable IRETURN mixed into a `()V` method must not be treated as a return anchor
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atReturn { omlCall("hook") } }
            }
        }
        val ret = InsnNode(Opcodes.RETURN)
        val alien = InsnNode(Opcodes.IRETURN)
        val cls = classNode("a/B", methodNode("m", "()V") {
            add(alien)
            add(ret)
        })

        assertTrue(spec.transform(TransformContext("a/B", cls)))

        val callNodes = cls.methods[0].instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(1, callNodes.size, "should only hit a RETURN matching this method's return type")
        assertEquals(callNodes[0], ret.previous)
    }

    // ---- atHead ----

    @Test
    fun `atHead injects before first instruction`() {
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") { atHead { call("x/Y", "go", "()V") } }
            }
        }
        val cls = classNode("a/B", methodNode("m", "()V") {
            add(LdcInsnNode("payload"))
            add(InsnNode(Opcodes.RETURN))
        })

        spec.transform(TransformContext("a/B", cls))

        val first = cls.methods[0].instructions.first
        assertTrue(first is MethodInsnNode && first.name == "go")
    }

    // ---- beforeCall / afterCall ----

    @Test
    fun `call site injection brackets the target invocation`() {
        val target = MethodInsnNode(Opcodes.INVOKESTATIC, "game/Foo", "tick", "()V", false)
        val cls = classNode("a/B", methodNode("m", "()V") {
            add(target)
            add(InsnNode(Opcodes.RETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    beforeCall(owner = "game/Foo", name = "tick") { omlCall("before") }
                    afterCall(owner = "game/Foo", name = "tick") { omlCall("after") }
                }
            }
        }

        spec.transform(TransformContext("a/B", cls))

        assertEquals("before", (target.previous as MethodInsnNode).name)
        assertEquals("after", (target.next as MethodInsnNode).name)
    }

    // ---- argument capture: descriptor-driven slot inference (int=0, long=1..2, reference=3) ----

    @Test
    fun `arg values load correct local slots`() {
        val cls = classNode("a/B", methodNode("m", "(IJLjava/lang/String;)V", isStatic = true) {
            add(InsnNode(Opcodes.RETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "(IJLjava/lang/String;)V") {
                    atHead {
                        call(
                            "x/Y",
                            "go",
                            "(IJLjava/lang/String;)V",
                            args = listOf(DslValue.Arg(0), DslValue.Arg(1), DslValue.Arg(2))
                        )
                    }
                }
            }
        }

        spec.transform(TransformContext("a/B", cls))

        val loads = cls.methods[0].instructions.toArray().filterIsInstance<VarInsnNode>()
        assertEquals(
            listOf(Triple(Opcodes.ILOAD, 0, null), Triple(Opcodes.LLOAD, 1, null), Triple(Opcodes.ALOAD, 3, null)),
            loads.map { Triple(it.opcode, it.`var`, null) })
    }

    // ---- missed rules must be reported explicitly and count as not modified ----

    @Test
    fun `missed rules return false and appliesTo is exact`() {
        val spec = spec {
            classTarget("a/B") {
                method("nope", desc = "()V") { atTail { omlCall("hook") } }
            }
        }
        val cls = classNode("a/B", methodNode("m", "()V") { add(InsnNode(Opcodes.RETURN)) })

        assertFalse(spec.appliesTo("a/C"))
        assertTrue(spec.appliesTo("a/B"))
        assertFalse(spec.transform(TransformContext("a/B", cls)))
        assertEquals(1, cls.methods[0].instructions.size())
    }

    // ---- rewrite level: return value transformation ----

    @Test
    fun `transformReturn pipes returned value through handler`() {
        val cls = classNode("a/B", methodNode("m", "()Ljava/lang/String;") {
            add(LdcInsnNode("hello"))
            add(InsnNode(Opcodes.ARETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()Ljava/lang/String;") {
                    atTail { transformReturn("x/Y", "tweak", "(Ljava/lang/String;)Ljava/lang/String;") }
                }
            }
        }

        assertTrue(spec.transform(TransformContext("a/B", cls)))

        val areturn = cls.methods[0].instructions.toArray().first { it.opcode == Opcodes.ARETURN }
        val call = areturn.previous as MethodInsnNode
        assertEquals("x/Y", call.owner)
        assertEquals("(Ljava/lang/String;)Ljava/lang/String;", call.desc)
    }

    @Test
    fun `transformReturn skips handler with wrong shape`() {
        val cls = classNode("a/B", methodNode("m", "()I") {
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IRETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()I") {
                    // handler shape is wrong: the parameter is not the target return type
                    atTail { transformReturn("x/Y", "tweak", "(Ljava/lang/String;)I") }
                }
            }
        }

        assertFalse(spec.transform(TransformContext("a/B", cls)))
        assertEquals(2, cls.methods[0].instructions.size())
    }

    @Test
    fun `transformReturn accepts extras pushed after the value`() {
        // Target: instance method (I)J returning long; handler (JLa/B;I)J — original value + this + parameter arg0
        val cls = classNode("a/B", methodNode("m", "(I)J") {
            add(LdcInsnNode(5L))
            add(InsnNode(Opcodes.LRETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "(I)J") {
                    atTail {
                        transformReturn(
                            "x/Y", "tweak", "(JLa/B;I)J",
                            extras = listOf(DslValue.This, DslValue.Arg(0))
                        )
                    }
                }
            }
        }

        assertTrue(spec.transform(TransformContext("a/B", cls)))

        val seq = cls.methods[0].instructions.toArray()
        val lreturn = seq.first { it.opcode == Opcodes.LRETURN }
        val call = lreturn.previous as MethodInsnNode
        assertEquals("x/Y", call.owner)
        // Push sequence before the handler: this(slot0) → iload(slot1) → INVOKESTATIC
        val aloadThis = call.previous.previous as VarInsnNode
        val iloadArg = call.previous as VarInsnNode
        assertEquals(Opcodes.ALOAD, aloadThis.opcode)
        assertEquals(0, aloadThis.`var`)
        assertEquals(Opcodes.ILOAD, iloadArg.opcode)
        assertEquals(1, iloadArg.`var`)
    }

    // ---- rewrite level: call redirection ----

    @Test
    fun `redirect on an instance call takes the receiver as a parameter`() {
        val original = MethodInsnNode(Opcodes.INVOKEVIRTUAL, "game/Foo", "bar", "(I)V", false)
        val cls = classNode("a/B", methodNode("m", "(I)V") {
            add(VarInsnNode(Opcodes.ALOAD, 0))
            add(original)
            add(InsnNode(Opcodes.RETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "(I)V") {
                    redirectCall(owner = "game/Foo", name = "bar", handlerOwner = "x/Y", handlerMethod = "barImpl")
                }
            }
        }

        assertTrue(spec.transform(TransformContext("a/B", cls)))

        val calls = cls.methods[0].instructions.toArray().filterIsInstance<MethodInsnNode>()
        assertEquals(1, calls.size)
        assertEquals(Opcodes.INVOKESTATIC, calls[0].opcode)
        assertEquals("x/Y", calls[0].owner)
        assertEquals("barImpl", calls[0].name)
        // The instance call's receiver was already on the stack (with its arguments pushed on top of it);
        // after switching to INVOKESTATIC it must become the first parameter — otherwise only the
        // arguments are consumed and the stack gets misaligned (fixed)
        assertEquals("(Lgame/Foo;I)V", calls[0].desc)
        assertFalse(cls.methods[0].instructions.contains(original))
    }

    @Test
    fun `redirect refuses constructor call sites`() {
        val ctor = MethodInsnNode(Opcodes.INVOKESPECIAL, "game/Foo", "<init>", "()V", false)
        val cls = classNode("a/B", methodNode("m", "()V") {
            add(ctor)
            add(InsnNode(Opcodes.RETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()V") {
                    redirectCall(name = "<init>", handlerOwner = "x/Y", handlerMethod = "impl")
                }
            }
        }

        assertFalse(spec.transform(TransformContext("a/B", cls)))
        assertTrue(cls.methods[0].instructions.contains(ctor))
    }

    // ---- selectors allow multiple names (an alias + the readable name) and descriptor wildcards ----

    @Test
    fun `selector matches any declared name and any desc when omitted`() {
        val spec = spec {
            classTarget("a/B") {
                method("aliased_m", "readable_m") { atHead { omlCall("hook") } }
            }
        }
        val cls = classNode(
            "a/B",
            methodNode("readable_m", "()V") { add(InsnNode(Opcodes.RETURN)) },
            methodNode("aliased_m", "(I)V") { add(InsnNode(Opcodes.RETURN)) },
        )

        assertTrue(spec.transform(TransformContext("a/B", cls)))
        val calls = cls.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }
        assertEquals(2, calls.size)
    }


    // ---- cancellable check (CheckCall) ----

    @Test
    fun `cancellableCall short-circuits void method with entry frame`() {
        val cls = classNode("a/B", methodNode("m", "(I)V") {
            add(InsnNode(Opcodes.RETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "(I)V") {
                    atHead {
                        cancellableCall("x/Y", "check", "(I)Z", args = listOf(DslValue.Arg(0)))
                    }
                }
            }
        }

        assertTrue(spec.transform(TransformContext("a/B", cls)))

        val seq = cls.methods[0].instructions.toArray()
        // ILOAD (parameter index 0, instance-method slot 1) → INVOKESTATIC (I)Z → IFEQ → RETURN → LabelNode → FrameNode
        val iload = seq[0] as VarInsnNode
        assertEquals(Opcodes.ILOAD, iload.opcode)
        assertEquals(1, iload.`var`)
        val check = seq[1] as MethodInsnNode
        assertEquals("x/Y", check.owner)
        assertEquals("(I)Z", check.desc)
        assertEquals(Opcodes.IFEQ, seq[2].opcode)
        assertEquals(Opcodes.RETURN, seq[3].opcode)
        assertTrue(seq[4] is LabelNode)
        assertTrue(seq[5] is FrameNode)
        // The original method body follows the frame
        assertEquals(Opcodes.RETURN, seq[6].opcode)
    }

    @Test
    fun `cancellableCall on non-void target is rejected`() {
        val cls = classNode("a/B", methodNode("m", "()I") {
            add(InsnNode(Opcodes.ICONST_1))
            add(InsnNode(Opcodes.IRETURN))
        })
        val spec = spec {
            classTarget("a/B") {
                method("m", desc = "()I") {
                    atHead { cancellableCall("x/Y", "check", "()Z") }
                }
            }
        }

        assertFalse(spec.transform(TransformContext("a/B", cls)))
        assertEquals(2, cls.methods[0].instructions.size())
    }
}
