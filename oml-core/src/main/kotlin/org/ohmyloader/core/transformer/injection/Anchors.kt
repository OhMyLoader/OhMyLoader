package org.ohmyloader.core.transformer.injection

import org.ohmyloader.api.OmlLog
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.InjectionPoint
import org.ohmyloader.core.transformer.injection.AnchorResolver.NOT_CONSTANT
import org.ohmyloader.core.transformer.injection.AnchorResolver.applyOrdinal
import org.ohmyloader.core.transformer.injection.AnchorResolver.constantValueOf
import org.ohmyloader.core.transformer.injection.AnchorResolver.sameConstant

/**
 * Anchor resolution: turns a declarative [InjectionPoint] into a set of instruction nodes inside the target method.
 *
 * It only cares about "finding the location", not what gets inserted there. All anchors return candidates
 * **in instruction order**, so [InjectionPoint]'s `ordinal` is just an index into the result — the semantics
 * live in one place instead of being re-implemented per anchor type.
 */
internal object AnchorResolver {

    /** `null` means [Constant] matched `ACONST_NULL`; it separates "matched null" from "not a constant". */
    private val NULL_CONSTANT = Any()

    /** Not a constant-loading instruction. */
    private val NOT_CONSTANT = Any()

    /** `java.lang.Object`'s descriptor — the anchor's type filter treats it as a wildcard for any reference. */
    private const val OBJECT_DESC = "Ljava/lang/Object;"

    /**
     * Pseudo-instructions (labels / line numbers / stack frames): not executable instructions, so location
     * matching must skip them.
     *
     * Frames matter most — a frame describes the state at the instruction **after** it, so inserting the injection
     * block after the frame is the only way to match the "entry state" semantics.
     */
    private val AbstractInsnNode.isPseudo: Boolean
        get() = this is LabelNode || this is LineNumberNode || this is FrameNode

    /**
     * Resolves the anchor.
     *
     * @param superName internal name of the target class's superclass (for delegating-constructor-call detection; may be null)
     * @param frames dataflow analysis (lazy, one per method) — only [`InjectionPoint.Store`] /
     *   [`InjectionPoint.Load`] need it to decide what type of value is being read/written. Other anchors never trigger it.
     * @return candidate nodes in instruction order; empty = not found (the caller is responsible for warning/counting)
     */
    fun resolve(
        point: InjectionPoint,
        method: MethodNode,
        owner: String,
        superName: String?,
        frames: MethodFrames,
    ): List<AbstractInsnNode> {
        // Sliced anchors: find by core first, then trim the window, and only then apply the ordinal
        // —— the ordinal is "the Nth within the window", matching Mixin's Slice behavior
        if (point is InjectionPoint.Sliced) {
            val inner = resolveCandidates(point.inner, method, owner, superName, frames)
            val windowed = applySlice(inner, point, method, owner, superName, frames)
            return applyOrdinal(windowed, ordinalOf(point), point)
        }
        return applyOrdinal(resolveCandidates(point, method, owner, superName, frames), ordinalOf(point), point)
    }

    /** Only finds the location; does not apply the window or ordinal. */
    private fun resolveCandidates(
        point: InjectionPoint,
        method: MethodNode,
        owner: String,
        superName: String?,
        frames: MethodFrames,
    ): List<AbstractInsnNode> = when (point) {
        // Head skips the method entry FrameNode: a frame describes the state at the instruction after it,
        // so inserting the injection block after the frame matches the "entry state" semantics
        InjectionPoint.Head -> listOfNotNull(firstRealInstruction(method))
        InjectionPoint.ConstructorHead -> listOfNotNull(constructorHead(method, owner, superName))
        is InjectionPoint.Return -> returns(method)
        InjectionPoint.FinalReturn -> listOfNotNull(returns(method).lastOrNull())

        is InjectionPoint.Call -> method.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
            .filter { point.matches(it) }

        is InjectionPoint.FieldAccess -> method.instructions.toArray()
            .filterIsInstance<FieldInsnNode>()
            .filter { point.matches(it) }

        is InjectionPoint.NewInstance -> newInstances(method, point)

        is InjectionPoint.Constant -> constants(method, point)

        is InjectionPoint.Store -> varAccesses(
            method,
            point.index,
            point.type,
            point.localOrdinal,
            point.argsOnly,
            store = true,
            frames = frames
        )

        is InjectionPoint.Load -> varAccesses(
            method,
            point.index,
            point.type,
            point.localOrdinal,
            point.argsOnly,
            store = false,
            frames = frames
        )

        // Nested within: the inner window is trimmed first, then the outer trims again (intersection, equal to a narrower range)
        is InjectionPoint.Sliced -> {
            val inner = resolveCandidates(point.inner, method, owner, superName, frames)
            applySlice(inner, point, method, owner, superName, frames)
        }
    }

    /**
     * Trims the candidates to within [InjectionPoint.Sliced]'s window (both ends exclusive).
     *
     * Returns an empty list and prints the reason when a boundary can't be resolved — ignoring a boundary would
     * silently degrade the search range to the whole method, whereas "only within this range" is precisely the
     * semantics the caller wants.
     */
    private fun applySlice(
        candidates: List<AbstractInsnNode>,
        slice: InjectionPoint.Sliced,
        method: MethodNode,
        owner: String,
        superName: String?,
        frames: MethodFrames,
    ): List<AbstractInsnNode> {
        val insns = method.instructions

        // Cross-module val properties don't smart-cast (Kotlin only guarantees stability for same-module properties),
        // so hoist them into local variables first, otherwise every null check would need a `!!`.
        val from = slice.from
        val to = slice.to

        val fromIndex = if (from == null) {
            -1
        } else {
            val node = resolveCandidates(from, method, owner, superName, frames).lastOrNull()
            if (node == null) {
                OmlLog.error(
                    "Injection",
                    "within start anchor not found: ${describe(from)} in ${method.name}${method.desc}" +
                        " — not injecting (ignoring the bound would silently widen the range to the whole method)"
                )
                return emptyList()
            }
            insns.indexOf(node)
        }

        val toIndex = if (to == null) {
            Int.MAX_VALUE
        } else {
            val node = resolveCandidates(to, method, owner, superName, frames).firstOrNull()
            if (node == null) {
                OmlLog.error(
                    "Injection",
                    "within end anchor not found: ${describe(to)} in ${method.name}${method.desc}" +
                        " — not injecting (ignoring the bound would silently widen the range to the whole method)"
                )
                return emptyList()
            }
            insns.indexOf(node)
        }

        return candidates.filter { insns.indexOf(it) in (fromIndex + 1) until toIndex }
    }

    /** Each anchor's ordinal; for sliced anchors it is the ordinal of their core. */
    private fun ordinalOf(point: InjectionPoint): Int? = when (point) {
        is InjectionPoint.Return -> point.ordinal
        is InjectionPoint.Call -> point.ordinal
        is InjectionPoint.FieldAccess -> point.ordinal
        is InjectionPoint.NewInstance -> point.ordinal
        is InjectionPoint.Constant -> point.ordinal
        is InjectionPoint.Store -> point.ordinal
        is InjectionPoint.Load -> point.ordinal
        is InjectionPoint.Sliced -> ordinalOf(point.inner)
        else -> null
    }

    private fun applyOrdinal(
        candidates: List<AbstractInsnNode>,
        ordinal: Int?,
        point: InjectionPoint,
    ): List<AbstractInsnNode> {
        if (ordinal == null) return candidates
        if (ordinal < 0) {
            OmlLog.error("Injection", "ordinal cannot be negative ($ordinal): $point — treated as unspecified")
            return candidates
        }
        return listOfNotNull(candidates.getOrNull(ordinal))
    }

    // ---------- the various anchors ----------

    /** The first non-pseudo instruction in the method body (skipping Label/LineNumber/Frame). */
    private fun firstRealInstruction(method: MethodNode): AbstractInsnNode? {
        var node = method.instructions.first
        while (node != null && node.isPseudo) node = node.next
        return node
    }

    private fun returns(method: MethodNode): List<AbstractInsnNode> {
        // Count only the target method's **own** return instructions. Using the IRETURN..RETURN range would
        // also count unreachable IRETURNs in `V` methods — Mixin instead derives the single opcode from the return type.
        val opcode = Type.getReturnType(method.desc).getOpcode(Opcodes.IRETURN)
        return method.instructions.toArray().filter { it.opcode == opcode }
    }

    /**
     * The "after the delegating call" position in the constructor.
     *
     * The delegating call is the first `INVOKESPECIAL <init>` whose owner is **this class** (`this()`) or the
     * **superclass** (`super()`). It is not detected by "a preceding `ALOAD 0`" — in a delegating call with
     * arguments, `ALOAD 0` is followed by the pushed arguments, not directly adjacent to it.
     *
     * Returns null when not found (**never falls back to the method head**): using `this` before `super()`
     * produces illegal bytecode, and silently changing the wrong location is far more dangerous than not injecting.
     */
    private fun constructorHead(method: MethodNode, owner: String, superName: String?): AbstractInsnNode? {
        if (method.name != "<init>") {
            // Non-constructor: Mixin's CTOR_HEAD degrades to HEAD here
            return firstRealInstruction(method)
        }
        val superInternal = superName ?: "java/lang/Object"
        val insns = method.instructions.toArray()
        for (i in insns.indices) {
            val node = insns[i]
            if (node !is MethodInsnNode) continue
            if (node.opcode != Opcodes.INVOKESPECIAL || node.name != "<init>") continue
            if (node.owner != owner && node.owner != superInternal) continue
            var j = i + 1
            while (j < insns.size && insns[j].isPseudo) j++
            return insns.getOrNull(j)
        }
        OmlLog.error(
            "Injection",
            "no delegating call (super()/this()) found for constructor ${owner}.${method.name}${method.desc}, " +
                "CTOR_HEAD anchor cannot be resolved — not injecting (no fallback to the method head: that would use this before it is initialized)"
        )
        return null
    }

    private fun newInstances(method: MethodNode, point: InjectionPoint.NewInstance): List<AbstractInsnNode> {
        val insns = method.instructions.toArray()
        val wantDesc = point.desc
        val news = insns.mapIndexedNotNull { i, node ->
            if (node !is TypeInsnNode || node.opcode != Opcodes.NEW) return@mapIndexedNotNull null
            if (point.owner != null && node.desc != point.owner) return@mapIndexedNotNull null
            // With a descriptor given, also confirm this NEW is actually followed by a matching <init>:
            // a nested `new` may sit between NEW and its <init>, so pair them by depth
            if (wantDesc != null && !hasMatchingInit(insns, i, node.desc, wantDesc)) {
                return@mapIndexedNotNull null
            }
            node
        }
        // The ordinal acts at the "entry level": filter by owner (+desc) first, then take the Nth entry,
        // matching Mixin's implementation (its ordinal also accumulates over candidates)
        return news
    }

    /**
     * From the `NEW` at [newIndex], pairs by depth and checks whether its `<init>` matches.
     *
     * Depth starts counting at the traversal of the `NEW` itself (+1), then increments on each subsequent `NEW`
     * and decrements on each `<init>`; the one that reaches 0 is this `NEW`'s constructor call.
     */
    private fun hasMatchingInit(
        insns: Array<AbstractInsnNode>,
        newIndex: Int,
        newOwner: String,
        wantedDesc: String,
    ): Boolean {
        var depth = 0
        for (i in newIndex until insns.size) {
            when (val node = insns[i]) {
                is TypeInsnNode if node.opcode == Opcodes.NEW -> depth++
                is MethodInsnNode if node.opcode == Opcodes.INVOKESPECIAL && node.name == "<init>" -> {
                    depth--
                    if (depth == 0) return node.owner == newOwner && node.desc == wantedDesc
                }
            }
        }
        return false
    }

    private fun constants(method: MethodNode, point: InjectionPoint.Constant): List<AbstractInsnNode> {
        val wanted = point.value ?: NULL_CONSTANT
        return method.instructions.toArray().filter { node ->
            val actual = constantValueOf(node)
            actual !== NOT_CONSTANT && sameConstant(actual, wanted)
        }
    }

    /**
     * Normalizes a "constant-loading instruction" into its value; returns [NOT_CONSTANT] if it is not a constant load.
     *
     * Short instruction forms must also be recognized: `javac` emits `ICONST_1` rather than `LDC` for `1`,
     * so recognizing only `LDC` would miss things like `if (x == 1)` entirely.
     */
    private fun constantValueOf(node: AbstractInsnNode): Any = when (node) {
        is LdcInsnNode -> node.cst
        is IntInsnNode if node.opcode in Opcodes.BIPUSH..Opcodes.SIPUSH -> node.operand
        is InsnNode -> when (node.opcode) {
            Opcodes.ACONST_NULL -> NULL_CONSTANT
            in Opcodes.ICONST_M1..Opcodes.ICONST_5 -> node.opcode - Opcodes.ICONST_0
            Opcodes.LCONST_0 -> 0L
            Opcodes.LCONST_1 -> 1L
            Opcodes.FCONST_0 -> 0f
            Opcodes.FCONST_1 -> 1f
            Opcodes.FCONST_2 -> 2f
            Opcodes.DCONST_0 -> 0.0
            Opcodes.DCONST_1 -> 1.0
            else -> NOT_CONSTANT
        }

        else -> NOT_CONSTANT
    }

    /**
     * Renders the constants appearing in the method as text.
     *
     * Only for diagnostics ("the constants in the method are 0, 1, 60") — comparison still goes through
     * [constantValueOf] + [sameConstant], both sharing the same normalization, otherwise the text would list
     * values the engine doesn't recognize as "present".
     */
    internal fun constantTexts(method: MethodNode): List<String> =
        method.instructions.toArray().mapNotNull { node ->
            when (val value = constantValueOf(node)) {
                NOT_CONSTANT -> null
                NULL_CONSTANT -> "null"
                else -> value.toString()
            }
        }

    /** Constant comparison: different types are unequal (`1` does not match `1L`), identical to Mixin's typed discriminator. */
    private fun sameConstant(actual: Any, wanted: Any): Boolean {
        if (actual === NULL_CONSTANT || wanted === NULL_CONSTANT) return actual === wanted
        return actual == wanted
    }

    // ---------- the local-variable read/write instructions (STORE / LOAD) ----------

    /**
     * The "**which one + which occurrence**" filter for local variables ([InjectionPoint.Store] and
     * [InjectionPoint.Load] share one implementation — they differ only in whether an access counts as a read
     * or write). In meaningful order: filter by read/write direction, [argsOnly], and explicit [index]
     * (pure instruction-level); when `type` is set, filter again by value type; when `localOrdinal` is set,
     * take the Nth of the still-live distinct slots (Mixin's `@ModifyVariable(ordinal = n)` semantics); hand
     * the rest to [applyOrdinal] for "which occurrence of the access".
     * For writes the type filter intentionally reads the **stack top** rather than the slot's declared type:
     * a written slot is only assigned **after** analysis, and at the successor the slot's type may already
     * have been **widened by** merging other paths (e.g. declared `Object` but each branch assigns
     * `String`/`Integer`) — asking the stack top "what value was actually written here" is unaffected by that.
     */
    private fun varAccesses(
        method: MethodNode,
        index: Int?,
        type: String?,
        localOrdinal: Int?,
        argsOnly: Boolean,
        store: Boolean,
        frames: MethodFrames,
    ): List<AbstractInsnNode> {
        val declared = type?.let { runCatching { Type.getType(it) }.getOrNull() }
        val argSlots = argumentSlotCount(method)
        var candidates = method.instructions.toArray()
            .filterIsInstance<VarInsnNode>()
            .filter { isStoreOpcode(it.opcode) == store }
            .filter { index == null || it.`var` == index }
            .filter { !argsOnly || it.`var` < argSlots }

        if (declared != null) {
            candidates = candidates.filter { matchesDeclaredType(it, declared, store, frames) }
        }

        if (localOrdinal != null) {
            val slots = candidates.map { it.`var` }.distinct().sorted()
            val slot = slots.getOrNull(localOrdinal)
            if (slot == null) {
                OmlLog.error(
                    "Injection",
                    "local-variable read/write anchor: only " +
                        "${slots.size} slots in ${method.name}${method.desc} match that type ($slots), cannot take occurrence ${localOrdinal + 1} — not injecting"
                )
                return emptyList()
            }
            candidates = candidates.filter { it.`var` == slot }
        }
        return candidates
    }

    /** Whether this read/write touches a value of type [declared]. */
    private fun matchesDeclaredType(
        node: VarInsnNode,
        declared: Type,
        store: Boolean,
        frames: MethodFrames,
    ): Boolean {
        val precise = if (store) frames.stackTopAt(node) else frames.localTypeAt(node, node.`var`)
        return if (precise != null) typeMatches(declared, precise) else categoryMatches(declared, node.opcode)
    }

    /**
     * Anchor-level type matching: same descriptor, or both in the int family (isomorphic at the JVM level),
     * or declared `Object` with an actual reference/array — the last case supports the legacy `@ModifyVariable`
     * returning `Object` (which only works for reference-typed local variables, see `Payload.ModifyVariable`'s docs).
     */
    private fun typeMatches(declared: Type, actual: Type): Boolean =
        declared.descriptor == actual.descriptor ||
            (declared.isIntFamily && actual.isIntFamily) ||
            (declared.descriptor == OBJECT_DESC && (actual.sort == Type.OBJECT || actual.sort == Type.ARRAY))

    /** Degenerate criterion when dataflow is unavailable: only look at the instruction category (`ASTORE` always counts as a reference). */
    private fun categoryMatches(declared: Type, opcode: Int): Boolean = when (opcode) {
        Opcodes.ILOAD, Opcodes.ISTORE -> declared.isIntFamily
        Opcodes.LLOAD, Opcodes.LSTORE -> declared.sort == Type.LONG
        Opcodes.FLOAD, Opcodes.FSTORE -> declared.sort == Type.FLOAT
        Opcodes.DLOAD, Opcodes.DSTORE -> declared.sort == Type.DOUBLE
        else -> declared.sort == Type.OBJECT || declared.sort == Type.ARRAY
    }

    private fun isStoreOpcode(opcode: Int): Boolean = opcode in Opcodes.ISTORE..Opcodes.ASTORE

    /** Total slots occupied by `this` (instance methods) plus each parameter — the boundary for `argsOnly`. */
    private fun argumentSlotCount(method: MethodNode): Int {
        val thisSlot = if (method.access and Opcodes.ACC_STATIC == 0) 1 else 0
        return thisSlot + Type.getArgumentTypes(method.desc).sumOf { it.size }
    }

    private val Type.isIntFamily: Boolean
        get() = sort == Type.BOOLEAN || sort == Type.CHAR || sort == Type.BYTE ||
            sort == Type.SHORT || sort == Type.INT

    // ---------- the anchors' own matching ----------

    private fun InjectionPoint.Call.matches(node: MethodInsnNode): Boolean =
        (owner == null || owner == node.owner) &&
            (name == null || name == node.name) &&
            (desc == null || desc == node.desc)

    private fun InjectionPoint.FieldAccess.matches(node: FieldInsnNode): Boolean =
        (opcode == null || opcode == node.opcode) &&
            (owner == null || owner == node.owner) &&
            (name == null || name == node.name) &&
            (desc == null || desc == node.desc)

    /** Used by warning text. */
    fun describe(point: InjectionPoint): String = when (point) {
        InjectionPoint.Head -> "HEAD"
        InjectionPoint.ConstructorHead -> "CTOR_HEAD"
        is InjectionPoint.Return -> "RETURN" + (point.ordinal?.let { "[$it]" } ?: "")
        InjectionPoint.FinalReturn -> "TAIL"
        is InjectionPoint.Call -> "${if (point.after) "AFTER" else "BEFORE"} INVOKE(" +
            listOfNotNull(point.owner, point.name, point.desc).joinToString(" ") +
            ")" + (point.ordinal?.let { "[$it]" } ?: "")

        is InjectionPoint.FieldAccess -> "${if (point.after) "AFTER" else "BEFORE"} FIELD(" +
            listOfNotNull(point.owner, point.name, point.desc, point.opcode?.let(::opcodeName)).joinToString(" ") +
            ")" + (point.ordinal?.let { "[$it]" } ?: "")

        is InjectionPoint.NewInstance -> "NEW(" +
            listOfNotNull(point.owner, point.desc).joinToString(" ") +
            ")" + (point.ordinal?.let { "[$it]" } ?: "")

        is InjectionPoint.Constant -> "CONSTANT(${point.value ?: "null"})" +
            (point.ordinal?.let { "[$it]" } ?: "")

        is InjectionPoint.Store -> varAnchor(
            "STORE",
            point.index,
            point.type,
            point.localOrdinal,
            point.ordinal,
            point.argsOnly
        )

        is InjectionPoint.Load -> varAnchor(
            "LOAD",
            point.index,
            point.type,
            point.localOrdinal,
            point.ordinal,
            point.argsOnly
        )

        is InjectionPoint.Sliced -> describe(point.inner) + " within(" +
            (point.from?.let(::describe) ?: "method head") + " … " +
            (point.to?.let(::describe) ?: "method tail") + ")"
    }

    /** `STORE(index=3, type=I)[2]` — states "which variable + which occurrence" in one go. */
    private fun varAnchor(
        name: String,
        index: Int?,
        type: String?,
        localOrdinal: Int?,
        ordinal: Int?,
        argsOnly: Boolean,
    ): String {
        val filters = listOfNotNull(
            index?.let { "index=$it" },
            type?.let { "type=$it" },
            localOrdinal?.let { "${it + 1}th local of this type" },
            if (argsOnly) "args only" else null,
        )
        val suffix = listOfNotNull(
            filters.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ordinal?.let { "${it + 1}th access" },
        ).joinToString("; ")
        return name + if (suffix.isEmpty()) "(any local)" else "($suffix)"
    }

    private fun opcodeName(opcode: Int): String = when (opcode) {
        Opcodes.GETFIELD -> "GETFIELD"
        Opcodes.PUTFIELD -> "PUTFIELD"
        Opcodes.GETSTATIC -> "GETSTATIC"
        Opcodes.PUTSTATIC -> "PUTSTATIC"
        else -> "opcode=$opcode"
    }
}
