package org.ohmyloader.core.hook

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `@Implements`: the target class gains the declared interfaces; default methods come from the JVM. */
class ImplementsTest {

    @Test
    fun `implements adds the declared interfaces to the target`() {
        val target = target()
        val result = ClassMerger.merge(
            hook(listOf("com/example/Extra", "java/lang/Runnable")),
            target,
            mutableMapOf(),
        )

        assertTrue(result.problems.isEmpty(), result.problems.toString())
        assertTrue(result.changed)
        assertEquals(2, result.interfaces)
        assertEquals(listOf("com/example/Extra", "java/lang/Runnable"), target.interfaces)
    }

    @Test
    fun `an already implemented interface is not duplicated`() {
        val target = target().apply { interfaces.add("com/example/Extra") }
        val result = ClassMerger.merge(hook(listOf("com/example/Extra")), target, mutableMapOf())

        assertTrue(result.problems.isEmpty(), result.problems.toString())
        assertEquals(0, result.interfaces)
        assertEquals(listOf("com/example/Extra"), target.interfaces)
    }

    private fun target(): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = "omltest/Target"
        superName = "java/lang/Object"
    }

    private fun hook(interfaces: List<String>): ClassMerger.HookClass {
        val mixin = ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = "omltest/Mixin"
            superName = "java/lang/Object"
        }
        return ClassMerger.HookClass(
            "m", "omltest.Mixin", "omltest/Target", mixin,
            implementedInterfaces = interfaces,
        )
    }
}
