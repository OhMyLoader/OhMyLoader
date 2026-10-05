package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.objectweb.asm.tree.analysis.*
import org.ohmyloader.api.inject.DslValue

/**
 * The **local-variable snapshot** at an injection point.
 *
 * **Why not the LocalVariableTable**: an obfuscated jar's `LocalVariableTable` is intermittently present
 * (some jars carry none at all; even where present it covers only some methods), and the variable names in
 * it are **themselves obfuscated names** — looking up a local by name is simply impossible; dataflow
 * analysis reads only instructions, so every target is treated alike. **Why not SimpleVerifier**: it does
 * `Class.forName` when deriving parent/child relationships, but the engine runs inside `findClass` — loading
 * another class would re-enter and could split type identity; only **category-level** info is used here.
 * **Precision boundaries**: method-parameter types come from the descriptor and are **exact**; method-body
 * local types **converge** where two paths merge (references → `Ljava/lang/Object;`, int family → `I`); no
 * **assignability check** between reference types (that needs the class hierarchy) — `Local(type =
 * "Ljava/lang/Runnable;")` will not match a slot declared `Ljava/util/concurrent/CompletableFuture;`.
 */
internal class LocalFrame(private val values: List<BasicValue?>) {

    /**
     * The type of the live value at slot [index]; `null` = no live value there (dead slot /
     * uninitialized / the second half of a long·double).
     */
    fun live(index: Int): Type? =
        values.getOrNull(index)
            ?.takeIf { it !== BasicValue.UNINITIALIZED_VALUE && it.type != null }
            ?.type

    fun size(): Int = values.size

    /** Resolves [DslValue.Local]; [isStatic] decides whether `this` occupies slot 0. */
    fun resolve(value: DslValue.Local, isStatic: Boolean): LocalResolution {
        val declared = value.type?.let { runCatching { Type.getType(it) }.getOrNull() }
        if (value.type != null && declared == null) {
            return LocalResolution.Missing("illegal type descriptor: \"${value.type}\"")
        }

        val index = value.index
        if (index != null) {
            if (index < 0) return LocalResolution.Missing("slot cannot be negative ($index)")
            val actualType = live(index)
                ?: return LocalResolution.Missing(
                    "slot $index has no live value at the injection point" +
                        " (dead slot / uninitialized / the second half of a long·double) ${context()}"
                )
            if (declared != null && !compatible(declared, actualType)) {
                return LocalResolution.Missing(
                    "slot $index is ${actualType.descriptor} at the injection point, but the rule declared ${declared.descriptor}"
                )
            }
            return LocalResolution.Found(index, declared ?: actualType)
        }

        if (declared == null) {
            return LocalResolution.Missing("neither index nor type given, cannot locate the local variable (give at least one)")
        }
        val candidates = candidatesOf(declared, isStatic)
        val slot = candidates.getOrNull(value.ordinal)
            ?: return LocalResolution.Missing(
                "only ${candidates.size} locals of type ${declared.descriptor} at the injection point, " +
                    "cannot take occurrence ${value.ordinal + 1}${context()}"
            )
        return LocalResolution.Found(
            slot,
            // candidatesOf only returns live slots; a miss here means that invariant was broken
            checkNotNull(live(slot)) { "candidate slot $slot is not live at the injection point ${context()}" },
        )
    }

    /**
     * Finds candidate slots by type (ascending). Exact descriptor takes priority — otherwise asking for `Z`
     * would first hit an ordinary `int` parameter (legal at the JVM level, but nonsensical); only without an
     * exact match does it fall back to int-family interchange (`local(type = "I")` reading a `Z` parameter).
     * Instance methods **start from slot 1**: `this` never participates in type-based resolution (use
     * [DslValue.This] to fetch the target instance), otherwise "the 1st reference-typed local" shifts entirely
     * because of `this`, an offset far harder to trace than an error.
     */
    private fun candidatesOf(type: Type, isStatic: Boolean): List<Int> {
        val exact = mutableListOf<Int>()
        val family = mutableListOf<Int>()
        var i = if (isStatic) 0 else 1
        while (i < values.size) {
            val actual = live(i)
            if (actual == null) {
                i++
                continue
            }
            when {
                actual.descriptor == type.descriptor -> exact += i
                compatible(type, actual) -> family += i
            }
            i += actual.size
        }
        return exact.ifEmpty { family }
    }

    /** For diagnostic output: `[0]=L…Minecraft;, [1]=Z, [2]=<empty>`. */
    fun describe(limit: Int = 12): String {
        val shown = values.take(limit).mapIndexed { i, v -> "[$i]=${nameOf(v)}" }
        val more = if (values.size > limit) " … (${values.size} slots in total)" else ""
        return shown.joinToString(", ") + more
    }

    private fun context(): String = " (locals at the injection point: ${describe()})"

    private fun nameOf(value: BasicValue?): String = when {
        value == null -> "<empty>"
        value === BasicValue.UNINITIALIZED_VALUE || value.type == null -> "<unknown>"
        else -> value.type.descriptor
    }

    companion object {
        /**
         * Whether the two types are **enough for the JVM to accept** this read.
         *
         * - The int family (`Z`/`B`/`C`/`S`/`I`) interoperates: the JVM verifier treats them as one type, so passing
         *   an `ILOAD`-produced value to a parameter declared `Z` is legal;
         * - Reference types require **exactly identical** descriptors (no parent/child check; see the class docs).
         */
        fun compatible(wanted: Type, actual: Type): Boolean {
            return wanted.descriptor == actual.descriptor || wanted.isIntFamily && actual.isIntFamily
        }

        /**
         * Whether the two types are merely **of the same category** (int family / long / float / double / reference).
         *
         * Looser than [compatible] (which demands exactly identical reference descriptors, for "find slot by
         * type"): the slot's type at the write anchor may already have been **merged coarser** by another path
         * (declared `Object`, each branch assigns `String`/`Integer` ⇒ the slot becomes `Object`), which is not
         * a rule error and shouldn't hard-fail. Only "handler declares int but the slot is a reference" is a
         * provable error — exactly what the JVM verifier would reject.
         */
        fun sameCategory(wanted: Type, actual: Type): Boolean {
            return wanted.descriptor == actual.descriptor || category(wanted) == category(actual)
        }

        private fun category(type: Type): Int = when {
            type.isIntFamily -> 0
            type.sort == Type.LONG -> 1
            type.sort == Type.FLOAT -> 2
            type.sort == Type.DOUBLE -> 3
            else -> 4 // reference and array
        }

        private val Type.isIntFamily: Boolean
            get() = sort == Type.BOOLEAN || sort == Type.CHAR || sort == Type.BYTE ||
                sort == Type.SHORT || sort == Type.INT
    }
}

/** The result of [LocalFrame.resolve]. */
internal sealed class LocalResolution {
    /** Success: the slot and its actual type (when [DslValue.Local.type] is declared, what was declared wins). */
    data class Found(val slot: Int, val type: Type) : LocalResolution()

    /** Could not resolve / validation failed; [reason] is a human-readable sentence. */
    data class Missing(val reason: String) : LocalResolution()
}

/**
 * The local-variable snapshot at an injection point + an explanation of "why it couldn't be obtained".
 *
 * A `null` [frame] has two possible causes: the anchor is unreachable (that code has no execution path),
 * or the dataflow analysis itself failed (legacy bytecode shapes, overly long methods…). The two must be told apart
 * clearly — "anchor unreachable" is a rule written wrong, "analysis failed" is an engine capability boundary, and
 * they are handled completely differently.
 */
internal class AnchorLocals(private val frame: LocalFrame?, private val unavailable: String?) {

    /** The snapshot; `null` = not obtainable (reason in [explain]). */
    fun frame(): LocalFrame? = frame

    fun explain(): String =
        unavailable ?: "anchor unreachable (no execution path in this method reaches this injection point)"

    companion object {
        /** For an unobtainable snapshot: non-null [reason] = why the analysis failed; `null` = the anchor is unreachable. */
        fun unavailable(reason: String?): AnchorLocals = AnchorLocals(null, reason)
    }
}

/**
 * The **all-injection-point** snapshots of a method: dataflow runs once per bytecode state and
 * however many injection points a method has, they all reuse it.
 *
 * Multiple injection points per method are the norm ([InjectionSpec] is "each rule × each anchor"),
 * and `Analyzer.analyze` is whole-method-level cost, so the cache hangs on the method rather than
 * on each anchor. The cache must be re-run whenever the instruction list grew: injected blocks
 * **shift every later instruction index**, and the frame table is addressed by index
 * (`instructions.indexOf(anchor)`), so a table computed against the pre-insertion list answers
 * at the wrong instruction for every anchor after the insertion.
 */
internal class MethodFrames(private val owner: String, private val method: MethodNode) {

    private var analyzedAtSize = -1
    private var table: Array<Frame<BasicValue>?>? = null
    private var failure: String? = null

    /**
     * Takes the local-variable snapshot at an injection point.
     *
     * [after] = the injection block is inserted **after** the anchor, in which case the frame of the successor
     * instruction is examined (e.g. for `afterField`, that `PUTFIELD` has already completed).
     */
    fun at(anchor: AbstractInsnNode, after: Boolean): AnchorLocals {
        ensure()
        val frames = table ?: return AnchorLocals.unavailable(failure)
        var node: AbstractInsnNode? = anchor
        if (after) node = nextExecutable(anchor)
        // A frame describes the state "before executing that instruction"; when inserted before the anchor, it is
        // exactly the anchor's own frame.
        val target = firstExecutableFrom(node) ?: anchor
        val frame: Frame<BasicValue> = frames.getOrNull(method.instructions.indexOf(target))
            ?: return AnchorLocals.unavailable(null)
        val slots: List<BasicValue?> = List(frame.locals) { i -> frame.getLocal(i) }
        return AnchorLocals(LocalFrame(slots), null)
    }

    private fun nextExecutable(node: AbstractInsnNode): AbstractInsnNode? = firstExecutableFrom(node.next)

    /**
     * The type of the live value in that slot **before** the instruction executes (`null` = not obtainable).
     *
     * A local-variable **read** anchor uses it to ask "what type is being read this time".
     */
    fun localTypeAt(node: AbstractInsnNode, slot: Int): Type? = at(node, after = false).frame()?.live(slot)

    /**
     * The stack-top type **before** the instruction executes (`null` = not obtainable, or the stack is empty).
     *
     * A local-variable **write** anchor uses it to ask "what value is being written this time" — right before the
     * write the value is on the stack top, which is more reliable than "reading the slot's type after the write":
     * the latter may have been **merged coarser** by another branch (declared `Object`, each branch assigns
     * `String`/`Integer` ⇒ the slot's type becomes `Object`).
     */
    fun stackTopAt(node: AbstractInsnNode): Type? {
        ensure()
        val frames = table ?: return null
        val target = firstExecutableFrom(node) ?: return null
        val frame = frames.getOrNull(method.instructions.indexOf(target)) ?: return null
        if (frame.stackSize == 0) return null
        return frame.getStack(frame.stackSize - 1).type
    }

    /**
     * The local-variable table **before** some instruction executes, encoded into the form [FrameNode] understands
     * (`Opcodes.INTEGER` / `LONG` / … or a class internal-name string).
     *
     * Its purpose is to rebuild "slots dropped by the original frames" (see [FrameRepair]): whatever a frame declares,
     * the verifier pushes down from that type, so the **real types from the dataflow analysis** are supplied here,
     * not guesses.
     *
     * `null` = analysis unavailable, or no state obtainable at that instruction.
     */
    fun frameLocalsAt(node: AbstractInsnNode, after: Boolean = false): Array<Any>? {
        ensure()
        val frames = table ?: return null
        val target = firstExecutableFrom(if (after) node.next else node) ?: return null
        val frame = frames.getOrNull(method.instructions.indexOf(target)) ?: return null
        return Array(frame.locals) { slot -> encodeLocal(frame.getLocal(slot)) }
    }

    /**
     * [BasicValue] → frame encoding.
     *
     * Everything with a `null` type is recorded as `TOP`: that covers both "no live value here" and "uninitialized
     * object" as well as a `long`/`double` second slot — the frame format can't distinguish them anyway, and where an
     * exact type is needed ([FrameRepair]) it treats `TOP` as "couldn't derive", never passing it off as a type.
     */
    private fun encodeLocal(value: BasicValue): Any {
        val type = value.type ?: return Opcodes.TOP
        return when (type.sort) {
            Type.BOOLEAN, Type.CHAR, Type.BYTE, Type.SHORT, Type.INT -> Opcodes.INTEGER
            Type.FLOAT -> Opcodes.FLOAT
            Type.LONG -> Opcodes.LONG
            Type.DOUBLE -> Opcodes.DOUBLE
            else -> type.internalName
        }
    }

    private fun firstExecutableFrom(start: AbstractInsnNode?): AbstractInsnNode? {
        var node = start
        while (node != null && (node is LabelNode || node is LineNumberNode || node is FrameNode)) node = node.next
        return node
    }

    private fun ensure() {
        val size = method.instructions.size()
        if (analyzedAtSize == size) return
        analyzedAtSize = size
        table = null
        failure = null
        try {
            val analyzer = Analyzer(PreciseInterpreter())
            analyzer.analyze(owner, method)
            @Suppress("UNCHECKED_CAST")
            table = analyzer.frames as Array<Frame<BasicValue>?>
        } catch (e: AnalyzerException) {
            failure = "dataflow analysis failed (${e.message})"
        } catch (t: Throwable) {
            // e.g. an overly long method hitting Analyzer's iteration limit, or a strange shape in legacy bytecode
            failure = "dataflow analysis error (${t.javaClass.simpleName}: ${t.message})"
        }
    }
}

/**
 * An interpreter that preserves **exact types**: the default [BasicInterpreter] folds `Z`/`B`/`C`/`S`/`I` all into
 * `I` and folds every reference into `Object` — so "find a local by type" would only have categories left. Here
 * [newValue] keeps the descriptor's type as-is (so method parameters are always exact), and convergence back to a
 * category happens only at **merge** time (see [merge]).
 */
private class PreciseInterpreter : BasicInterpreter(ASM9) {

    override fun newValue(type: Type?): BasicValue? = when {
        type == null -> BasicValue.UNINITIALIZED_VALUE
        type.sort == Type.VOID -> null
        else -> BasicValue(type)
    }

    /**
     * Merges the values on two paths. The only difference from the default implementation (return "unknown"
     * when not equal): **same-category** values converge to the category's representative (int family → `I`,
     * references → `Object`), so "a local assigned on both sides of an if/else" stays usable instead of the
     * whole slot becoming unknown; cross-category still returns unknown — such code would fail the verifier
     * anyway. Convergence must be **one-way coarsening**, otherwise the abstract interpretation never
     * converges (`Analyzer` reports "unstable code"): the result of `merge(a, b)` is never more precise than
     * `a` or `b`.
     */
    override fun merge(value1: BasicValue, value2: BasicValue): BasicValue {
        if (value1 == value2) return value1
        // unknown is the "most general" element, consistent with the default implementation
        if (value1 === BasicValue.UNINITIALIZED_VALUE || value2 === BasicValue.UNINITIALIZED_VALUE) {
            return BasicValue.UNINITIALIZED_VALUE
        }
        val type1 = value1.type ?: return BasicValue.UNINITIALIZED_VALUE
        val type2 = value2.type ?: return BasicValue.UNINITIALIZED_VALUE
        if (type1.sort == Type.OBJECT || type1.sort == Type.ARRAY) {
            return if (type2.sort == Type.OBJECT || type2.sort == Type.ARRAY) {
                BasicValue.REFERENCE_VALUE
            } else {
                BasicValue.UNINITIALIZED_VALUE
            }
        }
        return if (type1.sort == type2.sort || (type1.isIntFamily && type2.isIntFamily)) {
            BasicValue(categoryValue(type1))
        } else {
            BasicValue.UNINITIALIZED_VALUE
        }
    }

    private val Type.isIntFamily: Boolean
        get() = sort == Type.BOOLEAN || sort == Type.CHAR || sort == Type.BYTE ||
            sort == Type.SHORT || sort == Type.INT

    /** The category's representative value: the int family uniformly uses `I` (they are one shape at the JVM level); the rest use themselves. */
    private fun categoryValue(type: Type): Type = when {
        type.isIntFamily -> Type.INT_TYPE
        else -> type
    }
}
