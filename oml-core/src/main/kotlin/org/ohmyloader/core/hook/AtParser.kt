package org.ohmyloader.core.hook

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.ohmyloader.api.inject.InjectionPoint

/**
 * Translator of an `@At` into an [InjectionPoint]. Kept in its own file so the annotation front-end has
 * a single implementation of the `@At` syntax. `target` syntax (aligned with Mixin's canonical form): for INVOKE/INVOKE_ASSIGN,
 * `Lowner/name;member(args)ret` (recommended; owner dots or slashes both fine), or `member(args)ret`, or
 * bare `member`; for FIELD, `Lowner/name;field:desc` / `field:desc` / `field`; for NEW, `Lowner/name;`
 * (matched by owner) or `(args)V` (matched by ctor descriptor). Mixin's legacy dotted form
 * `owner.name(args)ret` is **not** supported — package-name dots make it ambiguous, and guessing wrong is
 * costlier than erroring; use the `L…;` form for an owner. `args` (named arguments, for CONSTANT, e.g.
 * `args = ["intValue=42"]`) shares the field names of [org.ohmyloader.api.hook.Constant].
 */
internal object AtParser {

    /** Parse result: the anchor point, plus whether it came from `shift = AFTER` (lets the payload know the injection lands after the instruction). */
    data class Result(val point: InjectionPoint)

    /**
     * Parse an `@At`; returns `null` when the value is not an injection point this front-end
     * recognizes (the caller is responsible for reporting the problem).
     *
     * An `ordinal` of -1 (the annotation default) is treated as "match all" -- consistent with Mixin.
     *
     * Mixin's specifier suffixes are parsed for `INVOKE`/`INVOKE_ASSIGN`: `:FIRST` selects the first
     * match (ordinal 0), `:LAST` the last ([InjectionPoint.Call.last]). Any other suffix on any value —
     * or a suffix combined with an explicit `ordinal` — makes the whole `@At` unrecognized, so it
     * surfaces as a problem instead of silently degrading to "match all".
     */
    fun parse(node: AnnotationNode): InjectionPoint? {
        val rawValue = annotationValue(node, "value") as? String ?: return null
        val value = rawValue.substringBefore(':').trim().uppercase()
        val suffix = if (':' in rawValue) rawValue.substringAfter(':').trim().uppercase() else null
        val ordinal = (annotationValue(node, "ordinal") as? Int)?.takeIf { it >= 0 }
        val opcode = (annotationValue(node, "opcode") as? Int)?.takeIf { it >= 0 }
        val rawShift = annotationValue(node, "shift")
        val shiftName = enumName(rawShift)
        // An unsupported shift (Mixin's Shift.BY) must surface as a problem, not silently degrade to
        // BEFORE: an arbitrary instruction offset drifts as the target code changes.
        if (rawShift != null && shiftName !in SUPPORTED_SHIFTS) return null
        val after = shiftName == "AFTER"
        val target = (annotationValue(node, "target") as? String).orEmpty()

        // FIRST/LAST are defined for call anchors; anything else carrying a suffix is unrecognized
        val callSelector = when {
            suffix == null -> Ordinal(ordinal, last = false)
            value in CALL_VALUES && suffix == "FIRST" && ordinal == null -> Ordinal(0, last = false)
            value in CALL_VALUES && suffix == "LAST" && ordinal == null -> Ordinal(null, last = true)
            else -> return null
        }

        return when (value) {
            "HEAD" -> InjectionPoint.Head
            "CTOR_HEAD", "CONSTRUCTOR_HEAD" -> InjectionPoint.ConstructorHead
            "RETURN" -> InjectionPoint.Return(ordinal)
            "TAIL", "FINAL_RETURN" -> InjectionPoint.FinalReturn

            // INVOKE_ASSIGN means "after the call" -- it also covers the `shift = AFTER` semantics
            "INVOKE", "INVOKE_ASSIGN" -> {
                if (isLegacyDotted(target)) return null
                val m = methodTarget(target)
                InjectionPoint.Call(
                    owner = m.owner,
                    name = m.name,
                    desc = m.desc,
                    after = after || value == "INVOKE_ASSIGN",
                    ordinal = callSelector.ordinal,
                    last = callSelector.last,
                    // `opcode = INVOKESTATIC` is Mixin's way of declaring "the target is a static call"
                    // used to decide whether the receiver enters the handler parameter list. When no
                    // opcode is given, `null` = unknown, and it is conservatively treated as an instance call.
                    isStatic = when (opcode) {
                        Opcodes.INVOKESTATIC -> true
                        Opcodes.INVOKEVIRTUAL, Opcodes.INVOKESPECIAL, Opcodes.INVOKEINTERFACE -> false
                        else -> null
                    },
                )
            }

            "FIELD" -> {
                if (isLegacyDotted(target)) return null
                val f = fieldTarget(target)
                InjectionPoint.FieldAccess(
                    owner = f.owner,
                    name = f.name,
                    desc = f.desc,
                    opcode = opcode,
                    after = after,
                    ordinal = ordinal,
                )
            }

            "NEW" -> {
                if (isLegacyDotted(target)) return null
                val t = typeTarget(target)
                InjectionPoint.NewInstance(t.owner, t.desc, ordinal)
            }

            "JUMP" -> {
                // A conditional branch; the opcode may be written as `target = "IF_ICMPGE"` or `opcode = Opcodes.IF_ICMPGE`
                if (after) return null
                val named = if (target.isBlank()) null else JUMP_OPCODES[target.trim().uppercase()] ?: return null
                if (opcode != null && opcode !in JUMP_OPCODES.values) return null
                if (opcode != null && named != null && opcode != named) return null
                InjectionPoint.Jump(opcode ?: named, ordinal)
            }

            "CHECKCAST" -> InjectionPoint.TypeCheck(Opcodes.CHECKCAST, typeCheckDesc(target), ordinal)
            "INSTANCEOF" -> InjectionPoint.TypeCheck(Opcodes.INSTANCEOF, typeCheckDesc(target), ordinal)

            "ARRAY_LOAD" -> InjectionPoint.ArrayAccess(
                opcode = opcode?.takeIf { it in Opcodes.IALOAD..Opcodes.SALOAD },
                ordinal = ordinal,
            )

            "ARRAY_STORE" -> InjectionPoint.ArrayAccess(
                opcode = opcode?.takeIf { it in Opcodes.IASTORE..Opcodes.SASTORE },
                ordinal = ordinal,
            )

            "NEW_ARRAY" -> arrayCreate(target, dimsFromArgs(node), ordinal)

            "CONSTANT" -> {
                // The constant value is written in `args` (same as Mixin); when absent, "any constant".
                val constant = constantFromArgs(node)
                InjectionPoint.Constant(constant.value, ordinal ?: constant.ordinal, after = after)
            }

            // Local-variable writes/reads (Mixin `STORE` / `LOAD`). The discriminator
            // (index/type/argsOnly) is supplied by @ModifyVariable's own fields; only an ordinal
            // placeholder is placed here.
            "STORE" -> InjectionPoint.Store(ordinal = ordinal)
            "LOAD" -> InjectionPoint.Load(ordinal = ordinal)

            else -> null
        }
    }

    /** Whether the `@At` value is supported by this front-end (for diagnostics). */
    val SUPPORTED = listOf(
        "HEAD", "CTOR_HEAD", "RETURN", "TAIL", "INVOKE", "INVOKE_ASSIGN", "FIELD", "NEW", "JUMP", "CONSTANT",
        "STORE", "LOAD", "CHECKCAST", "INSTANCEOF", "ARRAY_LOAD", "ARRAY_STORE", "NEW_ARRAY",
    )

    /** The conditional-branch opcodes, by the name written in `@At(target = …)`. Exposed for the consistency test. */
    internal val JUMP_OPCODES = mapOf(
        "IFEQ" to Opcodes.IFEQ,
        "IFNE" to Opcodes.IFNE,
        "IFLT" to Opcodes.IFLT,
        "IFGE" to Opcodes.IFGE,
        "IFGT" to Opcodes.IFGT,
        "IFLE" to Opcodes.IFLE,
        "IF_ICMPEQ" to Opcodes.IF_ICMPEQ,
        "IF_ICMPNE" to Opcodes.IF_ICMPNE,
        "IF_ICMPLT" to Opcodes.IF_ICMPLT,
        "IF_ICMPGE" to Opcodes.IF_ICMPGE,
        "IF_ICMPGT" to Opcodes.IF_ICMPGT,
        "IF_ICMPLE" to Opcodes.IF_ICMPLE,
        "IF_ACMPEQ" to Opcodes.IF_ACMPEQ,
        "IF_ACMPNE" to Opcodes.IF_ACMPNE,
        "IFNULL" to Opcodes.IFNULL,
        "IFNONNULL" to Opcodes.IFNONNULL,
    )

    /** `Lfoo;` → `foo`, an array descriptor stays as-is, blank = any. */
    private fun typeCheckDesc(target: String): String? {
        if (target.isBlank()) return null
        return if (target.startsWith("L") && target.endsWith(";")) target.substring(1, target.length - 1) else target
    }

    /** `NEW_ARRAY`'s target is the resulting array descriptor; `[[…` needs `args = ["dims=n"]`. */
    private fun arrayCreate(target: String, dims: Int, ordinal: Int?): InjectionPoint? {
        if (target.isBlank()) return null
        if (target.startsWith("[[")) {
            return InjectionPoint.ArrayCreate(
                Opcodes.MULTIANEWARRAY, desc = target, dims = dims, ordinal = ordinal,
            )
        }
        if (target.startsWith("[L") && target.endsWith(";")) {
            return InjectionPoint.ArrayCreate(
                Opcodes.ANEWARRAY, desc = target.substring(2, target.length - 1), ordinal = ordinal,
            )
        }
        if (target.length == 2 && target[0] == '[') {
            val atype = when (target[1]) {
                'Z' -> 4
                'C' -> 5
                'F' -> 6
                'D' -> 7
                'B' -> 8
                'S' -> 9
                'I' -> 10
                'J' -> 11
                else -> return null
            }
            return InjectionPoint.ArrayCreate(Opcodes.NEWARRAY, atype = atype, ordinal = ordinal)
        }
        return null
    }

    /** `args = ["dims=2"]` → 2; absent = 0 (wildcard for MULTIANEWARRAY). */
    private fun dimsFromArgs(node: AnnotationNode): Int {
        val args = when (val raw = annotationValue(node, "args")) {
            is List<*> -> raw.filterIsInstance<String>()
            is Array<*> -> raw.filterIsInstance<String>()
            else -> emptyList()
        }
        return args.firstOrNull { it.substringBefore('=').trim() == "dims" }
            ?.substringAfter('=', "")
            ?.trim()
            ?.toIntOrNull() ?: 0
    }

    /** The values a `:FIRST`/`:LAST` specifier suffix is defined for. */
    private val CALL_VALUES = setOf("INVOKE", "INVOKE_ASSIGN")

    /** The `shift` values this front-end accepts; anything else (e.g. `BY`) is rejected, not ignored. */
    val SUPPORTED_SHIFTS = setOf("", "BEFORE", "AFTER")

    /** The call anchor's match selector: [ordinal] plus whether the **last** match is wanted. */
    private data class Ordinal(val ordinal: Int?, val last: Boolean)

    // ---------- member targets ----------

    /**
     * Mixin's legacy dotted form `owner.member(args)ret` (no `L…;` wrapper): package-name dots are
     * ambiguous against member dots, and guessing wrong yields a target that can never match — so the
     * whole `@At` goes unrecognized and the caller reports it, instead of emitting a dead anchor.
     */
    private fun isLegacyDotted(target: String): Boolean = target.contains('.') && !target.startsWith("L")

    /** Method target: `Lowner;name(args)ret` / `name(args)ret` / `name` / empty (all wildcard). */
    fun methodTarget(target: String): MethodTarget {
        if (target.isBlank()) return MethodTarget(null, null, null)
        val [owner, member] = splitOwner(target)
        if (member.isBlank()) return MethodTarget(owner, null, null)
        val paren = member.indexOf('(')
        if (paren < 0) return MethodTarget(owner, member, null)
        val name = member.substring(0, paren)
        val desc = member.substring(paren)
        return MethodTarget(owner, name.ifBlank { null }, desc.takeIf { isValidMethodDesc(it) })
    }

    /** Field target: `Lowner;name:desc` / `name:desc` / `name` / empty. */
    fun fieldTarget(target: String): FieldTarget {
        if (target.isBlank()) return FieldTarget(null, null, null)
        val [owner, member] = splitOwner(target)
        if (member.isBlank()) return FieldTarget(owner, null, null)
        val colon = member.indexOf(':')
        if (colon < 0) return FieldTarget(owner, member, null)
        val name = member.substring(0, colon)
        val desc = member.substring(colon + 1)
        return FieldTarget(owner, name.ifBlank { null }, desc.takeIf { isValidFieldDesc(it) })
    }

    /**
     * `NEW` target: `Lowner;` matches by class; `(args)V` matches by constructor descriptor;
     * empty = all wildcard. (Mixin allows both forms to be mixed in a single NEW; here each form
     * is recognized separately.)
     */
    fun typeTarget(target: String): TypeTarget {
        if (target.isBlank()) return TypeTarget(null, null)
        if (target.startsWith("(")) {
            return TypeTarget(null, target.takeIf { isValidMethodDesc(it) })
        }
        val [owner, member] = splitOwner(target)
        return TypeTarget(owner, member.takeIf { it.startsWith("(") && isValidMethodDesc(it) })
    }

    /**
     * Split the target into its `owner` and `member` parts:
     *
     * - `Lnet/minecraft/Foo;bar(I)V` → owner = `net/minecraft/Foo`、member = `bar(I)V`
     * - `Lnet/minecraft/Foo;` → same owner、member = empty (matched by type)
     * - `bar(I)V` → owner = null
     *
     * Dots in the owner are always converted to `/` so Java names can be used directly.
     */
    private fun splitOwner(target: String): Pair<String?, String> {
        if (!target.startsWith("L")) return null to target
        val semi = target.indexOf(';')
        if (semi < 0) return null to target
        val owner = target.substring(1, semi).replace('.', '/').takeIf { it.isNotEmpty() }
        return owner to target.substring(semi + 1)
    }

    data class MethodTarget(val owner: String?, val name: String?, val desc: String?)
    data class FieldTarget(val owner: String?, val name: String?, val desc: String?)
    data class TypeTarget(val owner: String?, val desc: String?)

    // ---------- CONSTANT `args` ----------

    /** `args = ["intValue=42"]` → the value and ordinal (`ordinal=3` can also be written in `args`). */
    data class ConstantSpec(val value: Any?, val ordinal: Int?)

    private fun constantFromArgs(node: AnnotationNode): ConstantSpec {
        val entries = when (val args = annotationValue(node, "args")) {
            is List<*> -> args.filterIsInstance<String>()
            else -> emptyList()
        }
        var value: Any? = null
        var ordinal: Int? = null
        for (entry in entries) {
            val key = entry.substringBefore('=').trim()
            val raw = entry.substringAfter('=', "").trim()
            when (key) {
                "ordinal" -> ordinal = raw.toIntOrNull()
                "nullValue" -> if (raw == "true") value = null
                "intValue" -> raw.toIntOrNull()?.let { value = it }
                "longValue" -> raw.toLongOrNull()?.let { value = it }
                "floatValue" -> raw.toFloatOrNull()?.let { value = it }
                "doubleValue" -> raw.toDoubleOrNull()?.let { value = it }
                "stringValue" -> value = raw
            }
        }
        return ConstantSpec(value, ordinal)
    }

    // ---------- utilities ----------

    /** ASM encodes an annotation's enum value as a `[desc, name]` pair (a bare string also occurs); "" for unknown. */
    fun enumName(raw: Any?): String = when (raw) {
        is String -> raw.substringAfterLast('/')
        is List<*> -> raw.filterIsInstance<String>().lastOrNull()?.substringAfterLast('/') ?: ""
        is Array<*> -> raw.filterIsInstance<String>().lastOrNull()?.substringAfterLast('/') ?: ""
        else -> ""
    }

    fun annotationValue(annotation: AnnotationNode, name: String): Any? {
        val values = annotation.values ?: return null
        var i = 0
        while (i + 1 < values.size) {
            if (values[i] == name) return values[i + 1]
            i += 2
        }
        return null
    }

    private fun isValidMethodDesc(desc: String): Boolean =
        desc.startsWith("(") && desc.contains(')') &&
            runCatching { Type.getMethodType(desc) }.isSuccess

    private fun isValidFieldDesc(desc: String): Boolean =
        runCatching { Type.getType(desc) }.getOrNull()?.let { it.sort != Type.METHOD && it.sort != Type.VOID } == true
}
