package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.HandlerKind
import org.ohmyloader.api.inject.InjectionError
import org.ohmyloader.api.inject.InjectionPoint
import org.ohmyloader.api.inject.Payload
import org.ohmyloader.core.expression.ExpressionParserFacade
import org.ohmyloader.core.expression.ast.expressions.Expression
import org.ohmyloader.core.expression.flow.ComplexDataException
import org.ohmyloader.core.expression.flow.ExpressionTarget
import org.ohmyloader.core.expression.flow.FlowInterpreter
import org.ohmyloader.core.expression.flow.FlowValue
import org.ohmyloader.core.expression.flow.expansion.InsnExpander
import org.ohmyloader.core.expression.point.ExpressionContext
import org.ohmyloader.core.expression.pool.IdentifierPool
import org.ohmyloader.core.expression.pool.MemberDefinition
import org.ohmyloader.core.expression.pool.TypeDefinition
import org.ohmyloader.core.expression.utils.ExpressionDecorations
import org.ohmyloader.core.expression.utils.FlowDecorations
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
     * [target] keeps the compound-instruction nodes so the engine can expand them lazily at injection.
     */
    internal class Resolution(
        val instructions: List<AbstractInsnNode>,
        val decorations: Map<AbstractInsnNode, Map<String, Any?>>,
        val target: ExpressionTarget,
        val missHint: String? = null,
    )

    /**
     * Parsed patterns by source text. Rules are applied per class and the conflict pre-pass resolves some
     * points twice, so the same pattern would otherwise be re-parsed (and re-validated) repeatedly.
     */
    private val parsedPatterns = java.util.concurrent.ConcurrentHashMap<String, Expression>()

    fun resolve(
        classNode: ClassNode,
        method: MethodNode,
        point: InjectionPoint.Expression,
        injector: Any,
        contextType: ExpressionContext.Type,
    ): Resolution {
        val patterns = point.expressions.map { pattern ->
            parsedPatterns.computeIfAbsent(pattern) {
                try {
                    ExpressionParserFacade.parse(pattern)
                } catch (failure: RuntimeException) {
                    throw InjectionError(
                        "[injection] @Expression pattern cannot be parsed in ${method.name}${method.desc}: " +
                            failure.message,
                    )
                }
            }
        }
        val pool = identifierPool(point.definitions, classNode, method)
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
        val target = ExpressionTarget(method)
        // The most specific expression that matched its parent but failed on an input: the best clue when
        // nothing is captured at all, because it names the exact sub-pattern and instruction to look at.
        var partialExpr: Expression? = null
        var partialNode: FlowValue? = null
        val sink = object : Expression.OutputSink {
            override fun capture(node: FlowValue, expr: Expression, ctx: ExpressionContext) {
                if (node.isComplex) return
                // Register the injector's interest in this compound instruction now; the engine expands it
                // lazily at injection and lands on the part this capture referred to
                val expansion = InsnExpander.prepareExpansion(node, target, injector, ctx)
                val instruction = expansion?.compound ?: node.insn
                captured += instruction
                // Persistent flow decorations (array creation info, …) belong to the instruction the engine
                // will anchor on; the injector reads them to find the value's real position
                for ((key, value) in node.decorations) {
                    if (key.startsWith(FlowDecorations.PERSISTENT)) {
                        decorations.getOrPut(instruction) { HashMap() }[key] = value
                    }
                }
                // The produced value's type: SimpleExpression records it for most nodes, but node kinds that
                // extend Expression directly (instantiation, returns, assignments) do not — the injector
                // still needs to know what the capture leaves on the stack
                val type = runCatching { node.type }.getOrNull()
                if (type != null && type != Type.VOID_TYPE) {
                    val normalized = when (type.descriptor) {
                        "Lnull;" -> Type.getObjectType("java/lang/Object")
                        else -> type
                    }
                    decorations.getOrPut(instruction) { HashMap() }
                        .putIfAbsent(ExpressionDecorations.SIMPLE_EXPRESSION_TYPE, normalized)
                }
            }

            override fun decorate(insn: AbstractInsnNode, key: String, value: Any?) {
                decorations.getOrPut(insn) { HashMap() }[key] = value
            }

            override fun decorateInjectorSpecific(insn: AbstractInsnNode, key: String, value: Any?) {
                decorations.getOrPut(insn) { HashMap() }[key] = value
            }

            override fun reportPartialMatch(node: FlowValue, expr: Expression) {
                val current = partialExpr
                if (current == null || expr.src.endIndex - expr.src.startIndex <=
                    current.src.endIndex - current.src.startIndex
                ) {
                    partialExpr = expr
                    partialNode = node
                }
            }
        }
        val ctx = ExpressionContext(
            pool, sink, classNode, method, contextType, false,
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
                } catch (failure: UnsupportedOperationException) {
                    // This injector cannot target the captured shape (compound instruction, string concat, factory…)
                    throw InjectionError(
                        "[injection] @Expression in ${method.name}${method.desc} is not supported by " +
                            "this injector: ${failure.message}",
                    )
                }
            }
        }

        val ordered = method.instructions.toArray().filter { it in captured }
        val ordinal = point.ordinal
        val selected = if (ordinal == null) ordered else listOfNotNull(ordered.getOrNull(ordinal))
        val hint = when {
            ordered.isNotEmpty() -> if (selected.isEmpty()) {
                "@Expression matched ${ordered.size} instruction(s), but ordinal $ordinal is out of range " +
                    "(the first match is at ${describeInsn(ordered.first())})"
            } else {
                null
            }

            partialExpr != null -> {
                val node = partialNode
                val where = if (node == null || node.isComplex) "a merge point" else {
                    describeInsn(runCatching { node.insn }.getOrNull())
                }
                "the closest partial match was \"${partialExpr!!.src}\" against $where, " +
                    "but at least one of its inputs did not match"
            }

            else -> null
        }
        return Resolution(selected, decorations, target, hint)
    }

    /** A one-line instruction description for miss diagnostics. */
    private fun describeInsn(insn: AbstractInsnNode?): String = when (insn) {
        null -> "an unknown instruction"
        is MethodInsnNode -> "${insn.owner}.${insn.name}${insn.desc}"
        is FieldInsnNode -> "${insn.owner}.${insn.name}:${insn.desc}"
        is InvokeDynamicInsnNode -> "invokedynamic ${insn.name}${insn.desc}"
        is LdcInsnNode -> "constant ${insn.cst}"
        is VarInsnNode -> "local slot ${insn.`var`}"
        else -> "opcode ${insn.opcode}"
    }

    /** The expression context kind a payload uses: compound expansion and diagnostics depend on it. */
    fun contextTypeOf(payload: Payload): ExpressionContext.Type = when (payload) {
        is Payload.ModifyExpressionValue -> ExpressionContext.Type.MODIFY_EXPRESSION_VALUE
        is Payload.WrapOperation -> ExpressionContext.Type.WRAP_OPERATION
        is Payload.ModifyVariable -> ExpressionContext.Type.MODIFY_VARIABLE
        is Payload.Redirect -> ExpressionContext.Type.REDIRECT
        is Payload.ModifyArg -> ExpressionContext.Type.MODIFY_ARG
        is Payload.ModifyArgs -> ExpressionContext.Type.MODIFY_ARGS
        is Payload.HandlerCall -> when (payload.kind) {
            HandlerKind.INJECT -> ExpressionContext.Type.INJECT
            HandlerKind.REDIRECT -> ExpressionContext.Type.REDIRECT
            HandlerKind.MODIFY_ARG -> ExpressionContext.Type.MODIFY_ARG
            HandlerKind.MODIFY_ARGS -> ExpressionContext.Type.MODIFY_ARGS
            HandlerKind.MODIFY_EXPR_VALUE -> ExpressionContext.Type.MODIFY_EXPRESSION_VALUE
            HandlerKind.WRAP_OPERATION -> ExpressionContext.Type.WRAP_OPERATION
            HandlerKind.MODIFY_VAR -> ExpressionContext.Type.MODIFY_VARIABLE
            else -> ExpressionContext.Type.CUSTOM
        }

        else -> ExpressionContext.Type.CUSTOM
    }

    /** The identifier pool the author declared; the default primitives and `length` come from [IdentifierPool]. */
    private fun identifierPool(
        definitions: List<InjectionPoint.Expression.Definition>,
        classNode: ClassNode,
        method: MethodNode,
    ): IdentifierPool {
        val pool = IdentifierPool()
        val frames = if (definitions.any { it.locals.isNotEmpty() }) MethodFrames(classNode.name, method) else null
        val isStatic = (method.access and Opcodes.ACC_STATIC) != 0
        val argSlots = (if (isStatic) 0 else 1) + Type.getArgumentTypes(method.desc).sumOf { it.size }
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
            for (local in definition.locals) {
                pool.addMember(
                    definition.id,
                    MemberDefinition { flow ->
                        val insn = flow.insnOrNull
                        if (insn !is VarInsnNode || insn.opcode !in Opcodes.ILOAD..Opcodes.ALOAD) {
                            return@MemberDefinition false
                        }
                        val frame = frames?.at(insn, after = false)?.frame() ?: return@MemberDefinition false
                        val resolved = frame.resolve(
                            DslValue.Local(local.index, local.type, local.ordinal, strict = false),
                            isStatic,
                        )
                        val slot = (resolved as? LocalResolution.Found)?.slot ?: return@MemberDefinition false
                        slot == insn.`var` && (!local.argsOnly || insn.`var` < argSlots)
                    },
                )
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
