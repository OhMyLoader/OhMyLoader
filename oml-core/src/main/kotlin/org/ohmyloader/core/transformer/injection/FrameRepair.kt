package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

/**
 * Ensures the local variables the injection block reads are actually declared in the stack frame covering the
 * injection point. Branch-target frames may legally be `chop`ped down to empty locals (dropped slots are
 * "undefined" to the verifier); the original code never reads locals there, but if the injection block reads
 * `this`, the verifier judges by that empty-locals frame and rejects the whole class. A frame declaring fewer
 * locals is legal, so missing slots cannot be patched blanket-wise (that would inflate large methods' frames
 * and break frame matching at exception handlers). Only one case is repaired — a slot the block reads that is
 * **neither declared by the covering frame nor written between the frame and the injection point** (a slot
 * written in between is correctly absent and the verifier infers it). Types come from dataflow truth at the
 * injection point; declared slots are kept; if a type cannot be inferred, returns [Result.Failed] and the
 * caller skips the injection — never guess.
 */
internal object FrameRepair {

    /** Repair result. */
    sealed class Result {
        /** Nothing to repair: the frame already declares every slot to be read (or it was written in between, or there is no remappable frame, or analysis is unavailable). */
        data object Nothing : Result()

        /** Repaired: [slots] are the patched-in slots. */
        data class Fixed(val slots: List<Int>) : Result()

        /** Could not infer a type; the frame was left untouched. The caller should skip this injection with an explanation. */
        data class Failed(val reason: String) : Result()
    }

    /**
     * Checks the frame covering [anchor]; patches in the slots the injection block reads, based on dataflow truth, when necessary.
     *
     * [after] = the block is inserted **after** the anchor (in which case the anchor's own writes also count as "defined").
     */
    fun ensure(
        method: MethodNode,
        anchor: AbstractInsnNode,
        block: InsnList,
        after: Boolean,
        frames: MethodFrames?,
    ): Result {
        val reads = externalReads(block)
        if (reads.isEmpty()) return Result.Nothing

        val covering = lastFrameBefore(anchor) ?: return Result.Nothing
        val written = writtenBetween(covering, anchor, after)
        val declared = covering.local?.toList().orEmpty()
        val missing = reads.filter { slot ->
            slot !in written && declared.getOrNull(slot).let { it == null || it == Opcodes.TOP }
        }
        if (missing.isEmpty()) return Result.Nothing

        // Analysis unavailable ⇒ do nothing: better to keep the status quo than write a frame with a guessed type
        val truth = frames?.frameLocalsAt(anchor, after) ?: return Result.Nothing
        val unresolved = missing.filter { slot ->
            truth.getOrNull(slot).let { it == null || it == Opcodes.TOP }
        }
        if (unresolved.isNotEmpty()) {
            return Result.Failed(
                "they are neither declared by this frame nor written between the frame and the injection point, " +
                    "and dataflow cannot infer a type (possibly an uninitialized object): slots ${unresolved.joinToString()}",
            )
        }

        val size = maxOf(declared.size, missing.maxOf { slot -> slot + widthOf(truth[slot]) })
        val locals = ArrayList<Any>(size)
        for (slot in 0 until size) {
            val kept = declared.getOrNull(slot)?.takeIf { it != Opcodes.TOP }
            locals += kept ?: truth.getOrNull(slot) ?: Opcodes.TOP
        }
        covering.local = locals
        return Result.Fixed(missing)
    }

    /** `long`/`double` take two slots. */
    private fun widthOf(value: Any): Int =
        if (value == Opcodes.LONG || value == Opcodes.DOUBLE) 2 else 1

    /**
     * The slots the block reads **without first writing them itself**.
     *
     * Temporary slots the block allocates itself (an `xSTORE`, then an `xLOAD`) don't count — those are values
     * defined within the block and correctly should not be in the frame. Only "reads of outside values"
     * (`this`, captured locals) need the frame to declare them first.
     */
    private fun externalReads(block: InsnList): List<Int> {
        val defined = HashSet<Int>()
        val reads = LinkedHashSet<Int>()
        for (insn in block.toArray()) {
            when (insn) {
                is VarInsnNode -> {
                    if (isLoad(insn.opcode)) {
                        if (insn.`var` !in defined) reads += insn.`var`
                    } else {
                        defined += insn.`var`
                    }
                }

                is IincInsnNode -> {
                    if (insn.`var` !in defined) reads += insn.`var`
                    defined += insn.`var`
                }

                else -> Unit
            }
        }
        return reads.toList()
    }

    /** Slots written between the covering frame and the injection point (including the anchor itself when [after] is true). */
    private fun writtenBetween(frame: FrameNode, anchor: AbstractInsnNode, after: Boolean): Set<Int> {
        val written = HashSet<Int>()
        var node: AbstractInsnNode? = frame.next
        while (node != null && node !== anchor) {
            record(node, written)
            node = node.next
        }
        if (after) record(anchor, written)
        return written
    }

    private fun record(node: AbstractInsnNode, into: MutableSet<Int>) {
        when (node) {
            is VarInsnNode -> if (!isLoad(node.opcode)) into += node.`var`
            is IincInsnNode -> into += node.`var`
            else -> Unit
        }
    }

    private fun isLoad(opcode: Int): Boolean = opcode in Opcodes.ILOAD..Opcodes.ALOAD

    /** The nearest stack frame before the anchor — the one "in charge of" this stretch. */
    private fun lastFrameBefore(anchor: AbstractInsnNode): FrameNode? {
        var node: AbstractInsnNode? = anchor.previous
        while (node != null) {
            if (node is FrameNode) return node
            node = node.previous
        }
        return null
    }
}
