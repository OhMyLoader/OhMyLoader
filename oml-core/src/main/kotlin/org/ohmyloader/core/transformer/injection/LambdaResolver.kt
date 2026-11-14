package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode

/** One method a `LambdaMetafactory` call site points at; [owner] may be another class. */
internal data class LambdaImplementation(val owner: String, val name: String, val desc: String)

/**
 * The single enumeration of `LambdaMetafactory` implementations behind `@Lambda`. The scanner resolves
 * foreign references from the enclosing class's bytes at startup; the weaver resolves same-class bodies
 * from the live [ClassNode]. Both must pick the same [LambdaSelector.ordinal], so the match rules, the
 * scan order and the dedupe key live here only.
 */
internal object LambdaResolver {

    /** Distinct implementations in enclosing-method order; a repeated call site counts once. */
    fun implementations(
        classNode: ClassNode,
        methodNames: Collection<String>,
        methodDesc: String?,
        interfaceInternal: String,
    ): List<LambdaImplementation> {
        val outers = classNode.methods.filter {
            it.name in methodNames && (methodDesc == null || methodDesc == it.desc)
        }
        val implementations = LinkedHashMap<String, LambdaImplementation>()
        for (outer in outers) {
            for (insn in outer.instructions.toArray()) {
                if (insn !is InvokeDynamicInsnNode) continue
                if (insn.bsm.owner != LAMBDA_METAFACTORY || insn.bsm.name !in LAMBDA_BSMS) continue
                if (Type.getReturnType(insn.desc).descriptor != "L$interfaceInternal;") continue
                val impl = insn.bsmArgs.getOrNull(1) as? Handle ?: continue
                if (impl.tag !in LAMBDA_HANDLE_TAGS) continue
                implementations.putIfAbsent(
                    "${impl.owner}.${impl.name}${impl.desc}",
                    LambdaImplementation(impl.owner, impl.name, impl.desc),
                )
            }
        }
        return implementations.values.toList()
    }

    private const val LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory"
    private val LAMBDA_BSMS = setOf("metafactory", "altMetafactory")
    private val LAMBDA_HANDLE_TAGS = setOf(
        Opcodes.H_INVOKESTATIC, Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKESPECIAL,
        Opcodes.H_INVOKEINTERFACE, Opcodes.H_NEWINVOKESPECIAL,
    )
}

/**
 * Scan-time `@Lambda` resolution against class **bytes**: the enclosing class is only known by name
 * while scanning mod jars, so a foreign implementation can be registered as its own rule before any
 * class loads (load order then no longer decides whether the injection is possible). Unreadable bytes
 * or an out-of-range ordinal return null: the rule stays lazy and reports its miss at weaving time,
 * exactly as before.
 */
internal class LambdaScan(private val bytes: (String) -> ByteArray?) {

    private val nodes = HashMap<String, ClassNode?>()

    fun resolve(
        targetInternal: String,
        methodNames: Collection<String>,
        methodDesc: String?,
        interfaceInternal: String,
        ordinal: Int,
    ): LambdaImplementation? {
        val node = nodes.getOrPut(targetInternal) {
            val classBytes = bytes(targetInternal) ?: return@getOrPut null
            runCatching {
                ClassNode(Opcodes.ASM9).also { ClassReader(classBytes).accept(it, 0) }
            }.getOrNull()
        } ?: return null
        return LambdaResolver.implementations(node, methodNames, methodDesc, interfaceInternal).getOrNull(ordinal)
    }
}