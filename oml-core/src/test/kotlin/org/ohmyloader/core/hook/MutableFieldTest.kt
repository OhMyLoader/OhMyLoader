package org.ohmyloader.core.hook

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `@Mutable` on a `@Borrowed` field clears `final` on the target field so merged code can write it. */
class MutableFieldTest {

    @Test
    fun `mutable clears final on the borrowed target field`() {
        val target = target(final = true)
        val result = ClassMerger.merge(hook(borrowed = true, mutable = true), target, mutableMapOf())

        assertTrue(result.problems.isEmpty(), result.problems.toString())
        assertEquals(1, result.mutable)
        assertTrue(result.changed)
        assertEquals(0, target.fields.single().access and Opcodes.ACC_FINAL)
    }

    @Test
    fun `mutable without borrowed is reported`() {
        val result = ClassMerger.merge(hook(borrowed = false, mutable = true), target(final = true), mutableMapOf())

        assertTrue(
            result.problems.any { it.contains("@Mutable only applies to a @Borrowed field") },
            result.problems.toString(),
        )
        assertEquals(0, result.mutable)
    }

    @Test
    fun `an already non-final target field is left unchanged`() {
        val target = target(final = false)
        val result = ClassMerger.merge(hook(borrowed = true, mutable = true), target, mutableMapOf())

        assertTrue(result.problems.isEmpty(), result.problems.toString())
        assertEquals(0, result.mutable)
        assertEquals(0, target.fields.single().access and Opcodes.ACC_FINAL)
    }

    private fun target(final: Boolean): ClassNode = ClassNode(Opcodes.ASM9).apply {
        version = Opcodes.V17
        access = Opcodes.ACC_PUBLIC
        name = "omltest/Target"
        superName = "java/lang/Object"
        fields.add(
            FieldNode(
                Opcodes.ACC_PUBLIC or (if (final) Opcodes.ACC_FINAL else 0),
                "value", "I", null, null,
            ),
        )
    }

    private fun hook(borrowed: Boolean, mutable: Boolean): ClassMerger.HookClass {
        val mixin = ClassNode(Opcodes.ASM9).apply {
            version = Opcodes.V17
            access = Opcodes.ACC_PUBLIC
            name = "omltest/Mixin"
            superName = "java/lang/Object"
            fields.add(
                FieldNode(Opcodes.ACC_PRIVATE, "value", "I", null, null).apply {
                    visibleAnnotations = buildList {
                        if (borrowed) add(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Borrowed;"))
                        if (mutable) add(AnnotationNode(Opcodes.ASM9, "Lorg/ohmyloader/api/hook/Mutable;"))
                    }
                },
            )
        }
        return ClassMerger.HookClass("m", "omltest.Mixin", "omltest/Target", mixin)
    }
}
