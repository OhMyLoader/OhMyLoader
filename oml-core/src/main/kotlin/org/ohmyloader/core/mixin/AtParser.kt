package org.ohmyloader.core.mixin

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.ohmyloader.api.inject.InjectionPoint

/**
 * Translator of an `@At` into an [InjectionPoint]. Kept in its own file to be **reusable**: OML's own
 * `@At` and the "read Mixin annotations directly from external mod bytecode" path share the same syntax,
 * so parsing has a single implementation. `target` syntax (aligned with Mixin's canonical form): for INVOKE/INVOKE_ASSIGN,
 * `Lowner/name;member(args)ret` (recommended; owner dots or slashes both fine), or `member(args)ret`, or
 * bare `member`; for FIELD, `Lowner/name;field:desc` / `field:desc` / `field`; for NEW, `Lowner/name;`
 * (matched by owner) or `(args)V` (matched by ctor descriptor). Mixin's legacy dotted form
 * `owner.name(args)ret` is **not** supported — package-name dots make it ambiguous, and guessing wrong is
 * costlier than erroring; use the `L…;` form for an owner. `args` (named arguments, for CONSTANT, e.g.
 * `args = ["intValue=42"]`) shares the field names of [org.ohmyloader.api.mixin.Constant].
 */
internal object AtParser {

    /** Parse result: the anchor point, plus whether it came from `shift = AFTER` (lets the payload know the injection lands after the instruction). */
    data class Result(val point: InjectionPoint)

    /**
     * Parse an `@At`; returns `null` when the value is not an injection point this front-end
     * recognizes (the caller is responsible for reporting the problem).
     *
     * An `ordinal` of -1 (the annotation default) is treated as "match all" -- consistent with Mixin.
     */
    fun parse(node: AnnotationNode): InjectionPoint? {
        val rawValue = annotationValue(node, "value") as? String ?: return null
        // Mixin allows specifier suffixes such as `"INVOKE:LAST"`. The with-in/ordinal semantics are
        // already expressed via "from takes last, to takes first", so only the part before the colon is kept.
        val value = rawValue.substringBefore(':').trim().uppercase()
        val ordinal = (annotationValue(node, "ordinal") as? Int)?.takeIf { it >= 0 }
        val opcode = (annotationValue(node, "opcode") as? Int)?.takeIf { it >= 0 }
        val after = shiftAfter(node)
        val target = (annotationValue(node, "target") as? String).orEmpty()

        return when (value) {
            "HEAD" -> InjectionPoint.Head
            "CTOR_HEAD", "CONSTRUCTOR_HEAD" -> InjectionPoint.ConstructorHead
            "RETURN" -> InjectionPoint.Return(ordinal)
            "TAIL", "FINAL_RETURN" -> InjectionPoint.FinalReturn

            // INVOKE_ASSIGN means "after the call" -- it also covers the `shift = AFTER` semantics
            "INVOKE", "INVOKE_ASSIGN" -> {
                val m = methodTarget(target)
                InjectionPoint.Call(
                    owner = m.owner,
                    name = m.name,
                    desc = m.desc,
                    after = after || value == "INVOKE_ASSIGN",
                    ordinal = ordinal,
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
                val t = typeTarget(target)
                InjectionPoint.NewInstance(t.owner, t.desc, ordinal)
            }

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
        "HEAD", "CTOR_HEAD", "RETURN", "TAIL", "INVOKE", "INVOKE_ASSIGN", "FIELD", "NEW", "CONSTANT",
        "STORE", "LOAD",
    )

    // ---------- member targets ----------

    /** Method target: `Lowner;name(args)ret` / `name(args)ret` / `name` / empty (all wildcard). */
    fun methodTarget(target: String): MethodTarget {
        if (target.isBlank()) return MethodTarget(null, null, null)
        val (owner, member) = splitOwner(target)
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
        val (owner, member) = splitOwner(target)
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
        val (owner, member) = splitOwner(target)
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

    /** `shift = "AFTER"`（or `"BEFORE"` = default）. `Shift.BY` is unsupported and its argument is ignored. */
    private fun shiftAfter(node: AnnotationNode): Boolean {
        val raw = annotationValue(node, "shift") ?: return false
        val name = enumName(raw)
        return name == "AFTER"
    }

    /**
     * An enum value in an annotation is a `[desc, name]` pair in ASM (and may also be a bare string).
     * Returns the constant name itself (e.g. `"AFTER"`); empty string for unknown values.
     */
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

    /** For diagnostics: describe the anchor point in plain words (reusing the engine's labels). */
    fun opcodeName(opcode: Int): String = when (opcode) {
        Opcodes.GETFIELD -> "GETFIELD"
        Opcodes.PUTFIELD -> "PUTFIELD"
        Opcodes.GETSTATIC -> "GETSTATIC"
        Opcodes.PUTSTATIC -> "PUTSTATIC"
        else -> "opcode=$opcode"
    }
}
