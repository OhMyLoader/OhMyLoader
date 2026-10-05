package org.ohmyloader.core.mod

/**
 * One parsed `@Mod(dependencies = …)` entry: the required mod id plus an optional version
 * constraint. The string form is `"modId"` (any version) or `"modId@<constraint>"` where the
 * constraint is one of `>=`, `<=`, `>`, `<`, `=` (also `==`) followed by a dotted version —
 * `"other@>=1.2.0"`. Versions compare dot-segment by dot-segment, numerically when both segments
 * are numeric, lexically otherwise.
 */
data class DependencySpec(val modId: String, val op: Op?, val version: String?) {

    enum class Op(private val symbol: String) {
        AT_LEAST(">="), AT_MOST("<="), GREATER(">"), LESS("<"), EQUAL("=");

        fun satisfiedBy(comparison: Int): Boolean = when (this) {
            AT_LEAST -> comparison >= 0
            AT_MOST -> comparison <= 0
            GREATER -> comparison > 0
            LESS -> comparison < 0
            EQUAL -> comparison == 0
        }

        override fun toString(): String = symbol
    }

    /** True when [loaded] (the required mod's actual version) satisfies this spec's constraint. */
    fun satisfiedBy(loaded: String): Boolean {
        val constraint = version ?: return true
        val check = op ?: return true
        return check.satisfiedBy(compareVersions(loaded, constraint))
    }

    /** The constraint as originally written, for error messages (`"other@>=1.2.0"`). */
    val display: String get() = if (op == null || version == null) modId else "$modId@$op$version"

    companion object {

        private val OPS = listOf(">=", "<=", "==", ">", "<", "=")

        fun parse(entry: String): DependencySpec {
            val at = entry.indexOf('@')
            if (at < 0) return DependencySpec(entry.trim(), null, null)
            val modId = entry.substring(0, at).trim()
            val constraint = entry.substring(at + 1).trim()
            if (constraint.isEmpty()) throw IllegalStateException("dependency '$entry' has a constraint with no version")
            val symbol = OPS.firstOrNull { constraint.startsWith(it) }
                ?: throw IllegalStateException(
                    "dependency '$entry' has no readable constraint — expected one of >=, <=, >, <, = after '@'"
                )
            val version = constraint.removePrefix(symbol).trim()
            if (version.isEmpty()) throw IllegalStateException("dependency '$entry' has a constraint with no version")
            val op = when (symbol) {
                ">=" -> Op.AT_LEAST
                "<=" -> Op.AT_MOST
                ">" -> Op.GREATER
                "<" -> Op.LESS
                else -> Op.EQUAL
            }
            return DependencySpec(modId, op, version)
        }

        /** Dotted-version ordering: numeric segments compare numerically, missing segments count as 0. */
        fun compareVersions(a: String, b: String): Int {
            val sa = a.split('.')
            val sb = b.split('.')
            for (i in 0 until maxOf(sa.size, sb.size)) {
                val pa = sa.getOrNull(i) ?: "0"
                val pb = sb.getOrNull(i) ?: "0"
                val na = pa.toLongOrNull()
                val nb = pb.toLongOrNull()
                val c = if (na != null && nb != null) na.compareTo(nb) else pa.compareTo(pb)
                if (c != 0) return c
            }
            return 0
        }
    }
}
