package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TypeInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.core.hook.OPERATION
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds the `Operation<R>` handed to a `@WrapOperation` handler.
 *
 * The object is a `LambdaMetafactory`-generated lambda whose implementation is a **private synthetic
 * bridge method added to the target class**. The bridge unpacks the varargs array the handler passed to
 * `Operation.call`, re-executes a clone of the original instruction, and boxes the result. The bridge is
 * straight-line code (no branches) for calls and field accesses, so it needs no `StackMapTable` — which is
 * what lets it ride the loader's `COMPUTE_MAXS` write-back.
 *
 * The bridge is static on purpose: a normal call's receiver travels through `Operation.call`'s arguments,
 * not through the bridge's `this`. A private/super target still works because the bridge lives in the same
 * class as the call site.
 */
internal object OperationSupport {

    private val OPERATION_TYPE: Type = Type.getObjectType(OPERATION)

    private const val RUNTIME_OWNER = "org/ohmyloader/core/transformer/injection/OperationSupport"

    private val OBJECTS = Type.getType("[Ljava/lang/Object;")
    private val OBJECT = Type.getObjectType("java/lang/Object")
    private val STRING = Type.getObjectType("java/lang/String")

    private val LMF = Handle(
        Opcodes.H_INVOKESTATIC,
        "java/lang/invoke/LambdaMetafactory",
        "metafactory",
        "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)" +
            "Ljava/lang/invoke/CallSite;",
        false,
    )

    private val counter = AtomicInteger()

    /**
     * Appends the `Operation<R>` creation to [insns] and adds the bridge method to [classNode].
     *
     * @param argTypes the operation's values, in stack order (receiver first for an instance target; the
     *   field value for a write; empty for a static read)
     * @param original the instruction the bridge re-executes (a clone is placed inside it)
     */
    fun makeOperation(
        classNode: ClassNode,
        insns: InsnList,
        argTypes: Array<Type>,
        returnType: Type,
        original: AbstractInsnNode,
        prefix: List<AbstractInsnNode> = emptyList(),
    ) {
        val boxedReturn = boxed(returnType)
        val bridgeName = uniqueName(classNode)
        val bridgeDesc = Type.getMethodDescriptor(boxedReturn, OBJECTS)
        classNode.methods.add(bridgeMethod(bridgeName, argTypes, returnType, original, prefix))

        insns.add(
            InvokeDynamicInsnNode(
                "call",
                Type.getMethodDescriptor(OPERATION_TYPE),
                LMF,
                Type.getMethodType(OBJECT, OBJECTS),
                Handle(
                    Opcodes.H_INVOKESTATIC,
                    classNode.name,
                    bridgeName,
                    bridgeDesc,
                    (classNode.access and Opcodes.ACC_INTERFACE) != 0,
                ),
                Type.getMethodType(boxedReturn, OBJECTS),
            ),
        )
    }

    /**
     * Runtime arity guard called by the bridge. `Operation.call` erases its arguments to `Object[]`, so a
     * wrong count cannot be caught at compile time; failing here beats reading garbage from the array.
     */
    @JvmStatic
    fun checkArgumentCount(args: Array<Any?>, expected: Int, types: String) {
        if (args.size != expected) {
            throw IllegalArgumentException(
                "Operation.call expected $expected argument(s) $types, got ${args.size}",
            )
        }
    }

    private fun bridgeMethod(
        name: String,
        argTypes: Array<Type>,
        returnType: Type,
        original: AbstractInsnNode,
        prefix: List<AbstractInsnNode>,
    ): MethodNode {
        val boxedReturn = boxed(returnType)
        val method = MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            name,
            Type.getMethodDescriptor(boxedReturn, OBJECTS),
            null,
            null,
        )
        val ins = method.instructions
        ins.add(VarInsnNode(Opcodes.ALOAD, 0))
        ins.add(intConst(argTypes.size))
        ins.add(LdcInsnNode(argTypes.joinToString(", ", "[", "]") { it.className }))
        ins.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME_OWNER, "checkArgumentCount",
                Type.getMethodDescriptor(Type.VOID_TYPE, OBJECTS, Type.INT_TYPE, STRING),
                false,
            ),
        )
        // Instantiation's bridge starts with NEW + DUP: the uninitialized reference never leaves the
        // bridge, so the verifier's uninitialized-object rule is satisfied inside one straight-line body.
        prefix.forEach(ins::add)
        for (i in argTypes.indices) {
            val type = argTypes[i]
            ins.add(VarInsnNode(Opcodes.ALOAD, 0))
            ins.add(intConst(i))
            ins.add(InsnNode(Opcodes.AALOAD))
            when (type.sort) {
                Type.OBJECT, Type.ARRAY -> ins.add(TypeInsnNode(Opcodes.CHECKCAST, type.internalName))
                else -> {
                    val unbox = Boxing.unbox(type)
                        ?: throw IllegalStateException("no unboxing type for ${type.className}")
                    ins.add(TypeInsnNode(Opcodes.CHECKCAST, unbox.owner))
                    ins.add(MethodInsnNode(Opcodes.INVOKEVIRTUAL, unbox.owner, unbox.name, unbox.desc, false))
                }
            }
        }
        ins.add(copyOf(original))
        when {
            returnType == Type.VOID_TYPE -> ins.add(InsnNode(Opcodes.ACONST_NULL))
            returnType.sort != Type.OBJECT && returnType.sort != Type.ARRAY -> {
                val box = Boxing.box(returnType)
                    ?: throw IllegalStateException("no boxing type for ${returnType.className}")
                ins.add(MethodInsnNode(Opcodes.INVOKESTATIC, box.owner, box.name, box.desc, false))
            }
        }
        ins.add(InsnNode(Opcodes.ARETURN))
        return method
    }

    /** A detached copy of the wrapped call / field instruction (only those two anchor kinds are supported). */
    private fun copyOf(insn: AbstractInsnNode): AbstractInsnNode = when (insn) {
        is MethodInsnNode -> MethodInsnNode(insn.opcode, insn.owner, insn.name, insn.desc, insn.itf)
        is FieldInsnNode -> FieldInsnNode(insn.opcode, insn.owner, insn.name, insn.desc)
        else -> throw IllegalArgumentException(
            "WrapOperation supports call and field anchors only, got ${insn.javaClass.simpleName}",
        )
    }

    private fun boxed(type: Type): Type = when (type.sort) {
        Type.VOID -> Type.getObjectType("java/lang/Void")
        Type.BOOLEAN -> Type.getObjectType("java/lang/Boolean")
        Type.BYTE -> Type.getObjectType("java/lang/Byte")
        Type.CHAR -> Type.getObjectType("java/lang/Character")
        Type.SHORT -> Type.getObjectType("java/lang/Short")
        Type.INT -> Type.getObjectType("java/lang/Integer")
        Type.FLOAT -> Type.getObjectType("java/lang/Float")
        Type.LONG -> Type.getObjectType("java/lang/Long")
        Type.DOUBLE -> Type.getObjectType("java/lang/Double")
        else -> type
    }

    private fun intConst(value: Int): AbstractInsnNode = when {
        value in -1..5 -> InsnNode(Opcodes.ICONST_0 + value)
        value in Byte.MIN_VALUE..Byte.MAX_VALUE -> IntInsnNode(Opcodes.BIPUSH, value)
        value in Short.MIN_VALUE..Short.MAX_VALUE -> IntInsnNode(Opcodes.SIPUSH, value)
        else -> LdcInsnNode(value)
    }

    private fun uniqueName(classNode: ClassNode): String {
        var name: String
        do {
            name = "oml\$wrap\$${counter.incrementAndGet()}"
        } while (classNode.methods.any { it.name == name })
        return name
    }
}