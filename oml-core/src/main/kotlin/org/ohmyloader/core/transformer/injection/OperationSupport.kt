package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MultiANewArrayInsnNode
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
        val bridgeName = uniqueName(classNode, "oml\$wrap")
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
        emitCheck(ins, 0, argTypes)
        // Instantiation's bridge starts with NEW + DUP: the uninitialized reference never leaves the
        // bridge, so the verifier's uninitialized-object rule is satisfied inside one straight-line body.
        prefix.forEach(ins::add)
        emitUnpack(ins, 0, argTypes)
        ins.add(copyOf(original))
        emitBoxedReturn(ins, returnType)
        return method
    }

    /**
     * Builds the `Operation` for a wrapped **method**: the bridge (instance for an instance method, static
     * for a static one) unpacks the handler's arguments and calls the body moved to [movedName]. The lambda
     * factory captures `this` for an instance method, so the bridge stays an instance method.
     */
    fun makeMethodOperation(
        classNode: ClassNode,
        insns: InsnList,
        argTypes: Array<Type>,
        returnType: Type,
        movedName: String,
        movedDesc: String,
        isStatic: Boolean,
    ) {
        if ((classNode.access and Opcodes.ACC_INTERFACE) != 0) {
            throw IllegalStateException("WrapMethod cannot wrap a method of an interface: ${classNode.name}")
        }
        val boxedReturn = boxed(returnType)
        val bridgeName = uniqueName(classNode, "oml\$wrapCall")
        val bridgeDesc = Type.getMethodDescriptor(boxedReturn, OBJECTS)
        val bridge = MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PRIVATE or (if (isStatic) Opcodes.ACC_STATIC else 0) or Opcodes.ACC_SYNTHETIC,
            bridgeName,
            bridgeDesc,
            null,
            null,
        )
        val ins = bridge.instructions
        val argsSlot = if (isStatic) 0 else 1
        emitCheck(ins, argsSlot, argTypes)
        if (!isStatic) ins.add(VarInsnNode(Opcodes.ALOAD, 0))
        emitUnpack(ins, argsSlot, argTypes)
        ins.add(
            MethodInsnNode(
                if (isStatic) Opcodes.INVOKESTATIC else Opcodes.INVOKESPECIAL,
                classNode.name,
                movedName,
                movedDesc,
                false,
            ),
        )
        emitBoxedReturn(ins, returnType)
        classNode.methods.add(bridge)

        val factoryDesc = if (isStatic) {
            Type.getMethodDescriptor(OPERATION_TYPE)
        } else {
            Type.getMethodDescriptor(OPERATION_TYPE, Type.getObjectType(classNode.name))
        }
        insns.add(
            InvokeDynamicInsnNode(
                "call",
                factoryDesc,
                LMF,
                Type.getMethodType(OBJECT, OBJECTS),
                Handle(
                    if (isStatic) Opcodes.H_INVOKESTATIC else Opcodes.H_INVOKESPECIAL,
                    classNode.name,
                    bridgeName,
                    bridgeDesc,
                    false,
                ),
                Type.getMethodType(boxedReturn, OBJECTS),
            ),
        )
    }

    /**
     * Builds the `Operation<Boolean>` for a **wrapped comparison**: the bridge unpacks the two compared
     * values, re-runs the comparison and returns the **source-level** boolean (honoring [jumpOnTrue]).
     * Unlike the call bridges this body branches, so it carries its own two `StackMapTable` frames.
     */
    fun makeComparisonOperation(
        classNode: ClassNode,
        insns: InsnList,
        operandTypes: Array<Type>,
        cmpOpcode: Int,
        jumpOpcode: Int,
        jumpOnTrue: Boolean,
    ) {
        val booleanType = Type.getObjectType("java/lang/Boolean")
        val bridgeName = uniqueName(classNode, "oml\$compare")
        val bridgeDesc = Type.getMethodDescriptor(booleanType, OBJECTS)
        val bridge = MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PRIVATE or Opcodes.ACC_STATIC or Opcodes.ACC_SYNTHETIC,
            bridgeName,
            bridgeDesc,
            null,
            null,
        )
        val ins = bridge.instructions
        emitCheck(ins, 0, operandTypes)
        emitUnpack(ins, 0, operandTypes)
        // lcmp / fcmp / dcmp turn the two source operands into the int the jump consumes
        if (cmpOpcode != 0) ins.add(InsnNode(cmpOpcode))
        val onTrue = LabelNode()
        val end = LabelNode()
        ins.add(JumpInsnNode(jumpOpcode, onTrue))
        ins.add(InsnNode(if (jumpOnTrue) Opcodes.ICONST_0 else Opcodes.ICONST_1))
        ins.add(JumpInsnNode(Opcodes.GOTO, end))
        ins.add(onTrue)
        ins.add(FrameNode(Opcodes.F_NEW, 1, arrayOf<Any>(OBJECTS.internalName), 0, emptyArray<Any>()))
        ins.add(InsnNode(if (jumpOnTrue) Opcodes.ICONST_1 else Opcodes.ICONST_0))
        ins.add(end)
        ins.add(
            FrameNode(
                Opcodes.F_NEW, 1, arrayOf<Any>(OBJECTS.internalName), 1, arrayOf<Any>(Opcodes.INTEGER),
            ),
        )
        ins.add(MethodInsnNode(Opcodes.INVOKESTATIC, booleanType.internalName, "valueOf", "(Z)Ljava/lang/Boolean;", false))
        ins.add(InsnNode(Opcodes.ARETURN))
        classNode.methods.add(bridge)

        insns.add(
            InvokeDynamicInsnNode(
                "call",
                Type.getMethodDescriptor(OPERATION_TYPE),
                LMF,
                Type.getMethodType(OBJECT, OBJECTS),
                Handle(Opcodes.H_INVOKESTATIC, classNode.name, bridgeName, bridgeDesc, false),
                Type.getMethodType(booleanType, OBJECTS),
            ),
        )
    }

    private fun emitCheck(ins: InsnList, argsSlot: Int, argTypes: Array<Type>) {
        ins.add(VarInsnNode(Opcodes.ALOAD, argsSlot))
        ins.add(intConst(argTypes.size))
        ins.add(LdcInsnNode(argTypes.joinToString(", ", "[", "]") { it.className }))
        ins.add(
            MethodInsnNode(
                Opcodes.INVOKESTATIC, RUNTIME_OWNER, "checkArgumentCount",
                Type.getMethodDescriptor(Type.VOID_TYPE, OBJECTS, Type.INT_TYPE, STRING),
                false,
            ),
        )
    }

    private fun emitUnpack(ins: InsnList, argsSlot: Int, argTypes: Array<Type>) {
        for (i in argTypes.indices) {
            val type = argTypes[i]
            ins.add(VarInsnNode(Opcodes.ALOAD, argsSlot))
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
    }

    private fun emitBoxedReturn(ins: InsnList, returnType: Type) {
        when {
            returnType == Type.VOID_TYPE -> ins.add(InsnNode(Opcodes.ACONST_NULL))
            returnType.sort != Type.OBJECT && returnType.sort != Type.ARRAY -> {
                val box = Boxing.box(returnType)
                    ?: throw IllegalStateException("no boxing type for ${returnType.className}")
                ins.add(MethodInsnNode(Opcodes.INVOKESTATIC, box.owner, box.name, box.desc, false))
            }
        }
        ins.add(InsnNode(Opcodes.ARETURN))
    }

    /** A detached copy of the wrapped call / field instruction (only those two anchor kinds are supported). */
    private fun copyOf(insn: AbstractInsnNode): AbstractInsnNode = when (insn) {
        is MethodInsnNode -> MethodInsnNode(insn.opcode, insn.owner, insn.name, insn.desc, insn.itf)
        is FieldInsnNode -> FieldInsnNode(insn.opcode, insn.owner, insn.name, insn.desc)
        is InvokeDynamicInsnNode -> InvokeDynamicInsnNode(insn.name, insn.desc, insn.bsm, *insn.bsmArgs)
        is TypeInsnNode -> TypeInsnNode(insn.opcode, insn.desc)
        is IntInsnNode -> IntInsnNode(insn.opcode, insn.operand)
        is MultiANewArrayInsnNode -> MultiANewArrayInsnNode(insn.desc, insn.dims)
        is InsnNode -> InsnNode(insn.opcode)
        else -> throw IllegalArgumentException(
            "WrapOperation does not support this anchor instruction: ${insn.javaClass.simpleName}",
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

    private fun uniqueName(classNode: ClassNode, prefix: String): String {
        var name: String
        do {
            name = "$prefix\$${counter.incrementAndGet()}"
        } while (classNode.methods.any { it.name == name })
        return name
    }
}