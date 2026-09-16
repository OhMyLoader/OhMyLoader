package org.ohmyloader.api.inject

/**
 * The **model** of access-flag rewriting: a visibility tier + whether to remove
 * `final`.
 *
 * Only these two things are expressed (the [AccessRule] documentation covers why
 * the other flags are not exposed). There is no ASM in the model: the visibility
 * bit values are hardcoded per the class file format and equal the `ACC_*` values.
 */

/** The `public` bit in the class file (JVMS table 4.1-B). */
private const val FLAG_PUBLIC: Int = 0x0001

/** The `private` bit in the class file (JVMS table 4.1-B). */
private const val FLAG_PRIVATE: Int = 0x0002

/** The `protected` bit in the class file (JVMS table 4.1-B). */
private const val FLAG_PROTECTED: Int = 0x0004

/**
 * A visibility tier. JVM visibility has only four tiers, represented by **three bits** ([MASK]): `public` / `protected` / `private` each
 * occupy one bit, and all three zeroed means package-private. Changing a tier = clear those three bits first, then set the target bit.
 * [rank] goes from widest to narrowest (0 = widest). Rewriting typically widens; narrowing only warns: narrowing itself is legal, but
 * callers compiled against the original (wider) permission will fail at link time.
 */
enum class Visibility(val flag: Int, val rank: Int, val label: String) {
    PUBLIC(FLAG_PUBLIC, 0, "public"),
    PROTECTED(FLAG_PROTECTED, 1, "protected"),
    PACKAGE(0, 2, "package-private"),
    PRIVATE(FLAG_PRIVATE, 3, "private");

    companion object {
        /** The union of the three visibility bits; cleared as a whole before rewriting. */
        const val MASK: Int = FLAG_PUBLIC or FLAG_PROTECTED or FLAG_PRIVATE

        fun of(access: Int): Visibility = when {
            access and FLAG_PUBLIC != 0 -> PUBLIC
            access and FLAG_PROTECTED != 0 -> PROTECTED
            access and FLAG_PRIVATE != 0 -> PRIVATE
            else -> PACKAGE
        }
    }
}

/** The target kind of an access-flag rewrite. */
enum class MemberKind(val label: String) {
    CLASS("class"),
    FIELD("field"),
    METHOD("method"),
}

/**
 * An access-flag rewriting rule.
 *
 * Only two things are expressed: the **visibility tier** and **whether to remove
 * `final`**. The other flags are not exposed: flipping `ACC_STATIC` instantly
 * invalidates every `GETSTATIC`/`INVOKESTATIC` call site; `ACC_ABSTRACT`/
 * `ACC_NATIVE` directly conflict with "whether a Code attribute exists" — both
 * require the rule to also supply a method body, which is injection's job, not a
 * flag's.
 */
class AccessRule(
    val kind: MemberKind,
    /** Member names (always the empty set for [MemberKind.CLASS]). */
    val names: Set<String>,
    /** Type/method-descriptor restriction; null = unrestricted. */
    val desc: String?,
    /**
     * The declared visibilities, in declaration order.
     *
     * All declarations are kept rather than keeping only the last: writing two
     * tiers in one rule is a self-contradictory form that the validation phase
     * must report (keeping only the last would silently make the later one
     * effective).
     */
    val visibilities: List<Visibility>,
    val removeFinal: Boolean,
    val require: Int?,
    val expect: Int?,
    val allow: Int?,
    val optional: Boolean,
) {
    /** The effective visibility; null = this rule does not change visibility. */
    val visibility: Visibility? get() = visibilities.lastOrNull()

    /** A one-line description for diagnostic location. */
    fun describe(): String {
        val modifiers = visibilities.map { it.label } + listOfNotNull("removeFinal".takeIf { removeFinal })
        return when (kind) {
            MemberKind.CLASS -> "access { ${modifiers.joinToString(", ")} }"
            MemberKind.FIELD -> "field(${members()}) { ${modifiers.joinToString(", ")} }"
            MemberKind.METHOD -> "methodAccess(${members()}) { ${modifiers.joinToString(", ")} }"
        }
    }

    private fun members(): String = names.joinToString("|") + (desc?.let { ", desc=$it" } ?: "")
}

/**
 * Applies an [AccessRule] to a resolved [ClassNode]. Only flags are changed, no instructions, so stack frames and data flow are not
 * involved; the writer still rewrites the whole class, but the rewritten bytes differ from the original only in those few flags.
 * **If it does not apply, the whole rule is skipped without counting a hit**: writing an invalid new flag into the class file costs
 * HotSpot rejecting the **entire class** at definition time (`ClassFormatError`), far worse than "missing one flag change"; and not
 * counting lets a rule declaring `require(1)` surface immediately instead of silently passing.
 */