package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.InjectionPoint
import org.ohmyloader.core.expression.ExpressionParserFacade
import org.ohmyloader.core.expression.ast.expressions.Expression
import org.ohmyloader.core.expression.flow.ComplexDataException
import org.ohmyloader.core.expression.flow.FlowInterpreter
import org.ohmyloader.core.expression.flow.FlowValue
import org.ohmyloader.core.expression.flow.expansion.InsnExpander
import org.ohmyloader.core.expression.point.ExpressionContext
import org.ohmyloader.core.expression.pool.IdentifierPool
import org.ohmyloader.core.expression.pool.MemberDefinition
import org.ohmyloader.core.expression.pool.TypeDefinition
import org.ohmyloader.core.hook.AtParser

/**
 * Resolves an [InjectionPoint.Expression] against a target method: reconstructs the value flow, matches
 * every declared pattern against every value, and returns the captured instructions in method order
 * (the `ordinal` then picks one of them).
 *
 * Unlike the other anchors, this resolution needs the whole `ClassNode` — identifiers are declared by
 * the author through `@Definition` (At-style member strings), so no hierarchy lookup is involved; the
 * flow's type merges fall back to `Object` when no better common supertype is known.
 */
internal object ExpressionAnchors {

    /**
     * Resolution result: the captured instructions in method order plus, per instruction, the
     * decorations the match attached (e.g. a comparison's [org.ohmyloader.core.expression.utils.ComparisonInfo]).
     */
    internal class Resolution(
        val instructions: List<AbstractInsnNode>,
        val decorations: Map<AbstractInsnNode, Map<String, Any?>>,
    )

    fun resolve(
        classNode: ClassNode,
        method: MethodNode,
        point: InjectionPoint.Expression,
    ): Resolution {
        val patterns = point.expressions.map { pattern ->
            try {
                ExpressionParserFacade.parse(pattern)
            } catch (failure: RuntimeException) {
                throw InjectionError(
                    "[injection] @Expression pattern cannot be parsed in ${method.name}${method.desc}: " +
                        failure.message,
                )
            }
        }
        val pool = identifierPool(point.definitions)
        val flows = try {
            FlowInterpreter.analyze(classNode, method, null)
        } catch (failure: RuntimeException) {
            throw InjectionError(
                "[injection] the value flow of ${classNode.name}.${method.name}${method.desc} " +
                    "cannot be reconstructed (needed by @Expression): ${failure.message}",
            )
        }

        val captured = LinkedHashSet<AbstractInsnNode>()
        val decorations = HashMap<AbstractInsnNode, MutableMap<String, Any?>>()
        val sink = object : Expression.OutputSink {
            override fun capture(node: FlowValue, expr: Expression, ctx: ExpressionContext) {
                if (!node.isComplex) {
                    // A compound instruction (iinc, string concat, …) is represented by the real instruction
                    // in the method, not by the synthetic node the matcher sees
                    captured += InsnExpander.getRepresentative(node)
                }
            }

            override fun decorate(insn: AbstractInsnNode, key: String, value: Any?) {
                decorations.getOrPut(insn) { HashMap() }[key] = value
            }

            override fun decorateInjectorSpecific(insn: AbstractInsnNode, key: String, value: Any?) {
                decorations.getOrPut(insn) { HashMap() }[key] = value
            }
        }
        val ctx = ExpressionContext(
            pool, sink, classNode, method, ExpressionContext.Type.MODIFY_EXPRESSION_VALUE, false,
        )
        for (pattern in patterns) {
            for (flow in flows) {
                try {
                    pattern.matches(flow, ctx)
                } catch (_: ComplexDataException) {
                    // A merge point cannot be matched; the rest of the graph still can
                } catch (failure: IllegalStateException) {
                    // Undeclared identifier: a rule error, not a matching miss
                    throw InjectionError(
                        "[injection] @Expression in ${method.name}${method.desc}: ${failure.message}",
                    )
                }
            }
        }

        val ordered = method.instructions.toArray().filter { it in captured }
        val ordinal = point.ordinal
        val selected = if (ordinal == null) ordered else listOfNotNull(ordered.getOrNull(ordinal))
        return Resolution(selected, decorations)
    }

    /** The identifier pool the author declared; the default primitives and `length` come from [IdentifierPool]. */
    private fun identifierPool(definitions: List<InjectionPoint.Expression.Definition>): IdentifierPool {
        val pool = IdentifierPool()
        for (definition in definitions) {
            for (method in definition.methods) {
                val target = AtParser.methodTarget(method)
                pool.addMember(
                    definition.id,
                    MemberDefinition { flow ->
                        val insn = flow.insnOrNull
                        insn is MethodInsnNode &&
                            (target.owner == null || target.owner == insn.owner) &&
                            (target.name == null || target.name == insn.name) &&
                            (target.desc == null || target.desc == insn.desc)
                    },
                )
            }
            for (field in definition.fields) {
                val target = AtParser.fieldTarget(field)
                pool.addMember(
                    definition.id,
                    MemberDefinition { flow ->
                        val insn = flow.insnOrNull
                        insn is FieldInsnNode &&
                            (target.owner == null || target.owner == insn.owner) &&
                            (target.name == null || target.name == insn.name) &&
                            (target.desc == null || target.desc == insn.desc)
                    },
                )
            }
            for (type in definition.types) {
                val parsed = parseType(type)
                pool.addType(definition.id, TypeDefinition { actual -> actual == parsed })
            }
        }
        return pool
    }

    /** `Ljava/lang/String;` / `[I` / a bare internal name. */
    private fun parseType(value: String): Type =
        if (value.startsWith("L") || value.startsWith("[")) Type.getType(value) else Type.getObjectType(value)

    private val FlowValue.insnOrNull: AbstractInsnNode?
        get() = if (isComplex) null else runCatching { insn }.getOrNull()
}
