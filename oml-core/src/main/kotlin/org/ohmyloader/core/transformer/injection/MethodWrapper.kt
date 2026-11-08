package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.core.hook.OPERATION_DESC
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whole-method wrapping (MixinExtras `@WrapMethod`).
 *
 * The original body moves **verbatim** to a private synthetic method (frames and exception table travel
 * with the instruction list), and the selected method becomes a straight-line wrapper:
 * `(params…, Operation<R>, extras…)R`. The `Operation` bridge calls the moved body, so the handler can
 * re-run it with different arguments or skip it. There are no branches in the wrapper, so it needs no
 * `StackMapTable` and rides the loader's `COMPUTE_MAXS` write-back.
 *
 * Rules of this kind are applied **after** the method's ordinary injection rules (the caller orders them
 * that way), so previously injected code is inside the moved body and is re-run by the Operation.
 */
internal object MethodWrapper {

    private val counter = AtomicInteger()

    /** @return true when the wrap was applied; throws [InjectionError] for a rule that can never work. */
    fun wrap(
        classNode: ClassNode,
        method: MethodNode,
        owner: String,
        handlerOwner: String,
        handlerMethod: String,
        handlerDesc: String,
        extras: List<DslValue>,
        instance: Boolean,
    ): Boolean {
        if (method.name == "<init>" || method.name == "<clinit>") {
            throw InjectionError(
                "[injection] WrapMethod cannot target ${method.name}: initializing the instance/class " +
                    "through a handler is not supported",
            )
        }
        if (method.instructions.size() == 0) {
            throw InjectionError(
                "[injection] WrapMethod cannot target an abstract/native method: ${method.name}${method.desc}",
            )
        }
        val argTypes = Type.getArgumentTypes(method.desc)
        val returnType = Type.getReturnType(method.desc)
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        if (instance && isStatic) {
            throw InjectionError(
                "[injection] a merged WrapMethod handler needs an instance target, but ${method.name}${method.desc} " +
                    "is static — write the handler static (it returns to the bridge path)",
            )
        }

        val handlerArgs = Type.getArgumentTypes(handlerDesc)
        val handlerRet = Type.getReturnType(handlerDesc)
        val opIndex = argTypes.size
        if (handlerArgs.size < opIndex + 1 + extras.size) {
            throw InjectionError(
                "[injection] the handler of WrapMethod gives too few parameters: ${method.name}${method.desc} has " +
                    "$opIndex parameter(s), then Operation<R>, then ${extras.size} extra(s); the handler declares " +
                    "${handlerArgs.size} ($handlerDesc)",
            )
        }
        for (i in argTypes.indices) {
            if (handlerArgs[i] != argTypes[i]) {
                throw InjectionError(
                    "[injection] WrapMethod parameter ${i + 1} type mismatch: the target takes " +
                        "${argTypes[i].className}, the handler takes ${handlerArgs[i].className} ($handlerDesc)",
                )
            }
        }
        if (handlerArgs[opIndex].descriptor != OPERATION_DESC) {
            throw InjectionError(
                "[injection] the handler of WrapMethod must take $OPERATION_DESC right after the method " +
                    "parameters: actual ${handlerArgs[opIndex].className} ($handlerDesc)",
            )
        }
        if (handlerRet != returnType) {
            throw InjectionError(
                "[injection] the handler return type of WrapMethod must equal the method's return type: " +
                    "method returns ${returnType.className}, handler returns ${handlerRet.className} ($handlerDesc)",
            )
        }

        val movedName = uniqueName(classNode)
        val moved = MethodNode(
            Opcodes.ASM9,
            Opcodes.ACC_PRIVATE or (if (isStatic) Opcodes.ACC_STATIC else 0) or Opcodes.ACC_SYNTHETIC,
            movedName,
            method.desc,
            method.signature,
            method.exceptions?.toTypedArray(),
        )
        moved.instructions = method.instructions
        moved.tryCatchBlocks = method.tryCatchBlocks
        moved.localVariables = method.localVariables
        moved.maxStack = method.maxStack
        moved.maxLocals = method.maxLocals
        classNode.methods.add(moved)

        // The original method becomes the wrapper: fresh body, no exception table / debug locals.
        method.instructions = InsnList()
        method.tryCatchBlocks = mutableListOf()
        method.localVariables = mutableListOf()
        method.visibleLocalVariableAnnotations = null
        method.invisibleLocalVariableAnnotations = null

        val body = method.instructions
        // Handler parameters: the merged form's `this`, then the target method's parameters.
        if (instance) body.add(VarInsnNode(Opcodes.ALOAD, 0))
        var slot = if (isStatic) 0 else 1
        for (type in argTypes) {
            body.add(VarInsnNode(Boxing.loadOpcode(type), slot))
            slot += type.size
        }
        // The Operation factory captures `this` for an instance method.
        if (!isStatic) body.add(VarInsnNode(Opcodes.ALOAD, 0))
        OperationSupport.makeMethodOperation(
            classNode, body, argTypes, returnType, movedName, method.desc, isStatic,
        )
        if (!PayloadEmitter.emitValues(body, extras, method, null)) {
            throw InjectionError(
                "[injection] the extras of WrapMethod could not be emitted at method entry: ${method.name}${method.desc}",
            )
        }
        body.add(
            MethodInsnNode(
                if (instance) Opcodes.INVOKEVIRTUAL else Opcodes.INVOKESTATIC,
                handlerOwner,
                handlerMethod,
                handlerDesc,
                false,
            ),
        )
        body.add(
            if (returnType == Type.VOID_TYPE) InsnNode(Opcodes.RETURN)
            else InsnNode(Boxing.returnOpcode(returnType)),
        )
        return true
    }

    private fun uniqueName(classNode: ClassNode): String {
        var name: String
        do {
            name = "oml\$wrapped\$${counter.incrementAndGet()}"
        } while (classNode.methods.any { it.name == name })
        return name
    }
}