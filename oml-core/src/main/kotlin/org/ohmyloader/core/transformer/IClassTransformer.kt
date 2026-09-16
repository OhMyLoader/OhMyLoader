package org.ohmyloader.core.transformer

import org.objectweb.asm.tree.ClassNode

/**
 * Bytecode transform context: classes flowing through the pipeline already live in the mapped, readable namespace.
 */
class TransformContext(val internalName: String, val node: ClassNode)

/**
 * Bytecode transformer.
 *
 * The pipeline routes **all** game/mod classes through the transformers; each transformer declares in
 * [appliesTo] which classes it cares about (via wildcards or exact internal names). This is the foundation of
 * Mixin/AT-style any-target injection.
 */
interface IClassTransformer {
    val id: String

    /** Whether this transformer applies to a given class (internal name). The actual transform only runs on a match by default. */
    fun appliesTo(internalName: String): Boolean

    /** Modifies [TransformContext.node], returning whether an actual modification occurred. */
    fun transform(context: TransformContext): Boolean
}
