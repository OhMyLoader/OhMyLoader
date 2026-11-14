package org.ohmyloader.core.transformer

/**
 * One instruction (or whole method body) that an injection transformer will **replace**, claimed on the
 * untouched class before any transformer runs.
 *
 * [instructionIndex] is the index in the method's original instruction list; `-1` with [wholeMethod]
 * marks a whole-body wrap. Claims are only compared inside one pre-pass, so the index stays stable.
 */
class ExclusiveClaim(
    val methodName: String,
    val methodDesc: String,
    val instructionIndex: Int,
    val wholeMethod: Boolean,
    val label: String,
)

/**
 * Optional capability for transformers that own exclusive injection sites. The class loader gathers the
 * claims of every interested transformer on the untouched class first, so two different transformers
 * (e.g. an adapter's event hooks and a mod's rules) cannot silently race for the same instruction.
 */
interface IExclusiveClaimSource : IClassTransformer {
    /** Cheap pre-check: does this transformer have any exclusive rule for the class? */
    fun hasExclusivePoints(internalName: String): Boolean

    /** Resolves the exclusive anchors on the untouched [TransformContext.node]. */
    fun exclusiveClaims(context: TransformContext): List<ExclusiveClaim>
}