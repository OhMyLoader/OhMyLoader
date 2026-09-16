package org.ohmyloader.api.inject

/**
 * The **compiled form** of a rule: which method to select, at which points, what
 * to insert at each, and how many hits count as correct.
 *
 * This is the runtime form of a rule — the front-end (DSL, annotations) builds
 * it and the engine executes it. [points] uses `Pair` rather than a single
 * "anchor + payload" type because the two inherently appear **in pairs**: one
 * payload is inserted at each anchor, the anchor decides "where to insert" and
 * the payload "what to insert"; there is no third combination.
 */
class MethodRule(
    val selector: MethodSelector,
    val points: List<Pair<InjectionPoint, Payload>>,
    /** **Lower** bound on hits; going below fails hard ([InjectionError]). */
    val require: Int? = null,
    /** Expected hit count; a mismatch produces a warning. */
    val expect: Int? = null,
    /** Allows a miss (only suppresses the "unmatched" warning; does not change hit-count semantics). */
    val optional: Boolean = false,
    /** **Upper** bound on hits; exceeding it fails hard. */
    val allow: Int? = null,
)

/** All rules on one target class: method-body injection + access-flag rewriting. */
class ClassRules(
    val methods: List<MethodRule> = emptyList(),
    val accessRules: List<AccessRule> = emptyList(),
)

/**
 * A source for "merging into" a target class: its declared members are **truly merged** into the
 * target class. After merging, the handler is an instance method of the target class (`this` =
 * target instance) called directly via `INVOKEVIRTUAL` — no bridge, registry, or reflection; the
 * cost is that the source class is moved in **verbatim**, so reading the target class's fields
 * requires member-level markers (`@Shadow` / `@Unique` / `@Overwrite` / `@Accessor` / `@Invoker`).
 * [sourceInternal] is a class name **within the same mod jar**: the loader only reads its
 * bytecode and **never loads it** — it references game classes, and loading a class that
 * references a game class would drag that game class in.
 */
class MergeSource(
    val targetInternal: String,
    val sourceInternal: String,
    /** A human-readable name appearing in startup logs and diagnostics; falls back to the class name if null. */
    val id: String? = null,
)

/**
 * A rule set: target-class internal name → the rules on that class.
 *
 * Each mod supplies one; the loader [merge]s multiple into one handed to the
 * engine. Class names are the target version's **internal names** (readable as shipped).
 */
class RuleSet(
    val classes: Map<String, ClassRules> = emptyMap(),
    /** Sources to merge into target classes; see [MergeSource]. */
    val merges: List<MergeSource> = emptyList(),
) {

    /** All classes this rule set concerns. */
    val targets: Set<String> get() = classes.keys

    /**
     * Merges two rule sets: rules on the same class **accumulate** (they are not
     * overridden).
     *
     * Accumulation rather than overriding is because the two rule sets come from
     * two mods, or from an "annotation front-end + handwritten DSL", both of which
     * are declared in parallel — neither should override the other.
     */
    fun merge(other: RuleSet): RuleSet {
        if (classes.isEmpty() && merges.isEmpty()) return other
        if (other.classes.isEmpty() && other.merges.isEmpty()) return this
        val merged = LinkedHashMap(classes)
        for ([target, rules] in other.classes) {
            val previous = merged[target]
            merged[target] = ClassRules(
                methods = (previous?.methods ?: emptyList()) + rules.methods,
                accessRules = (previous?.accessRules ?: emptyList()) + rules.accessRules,
            )
        }
        // A given source is merged into a given target only once: merging twice would make the second collide on every name (the members are already there)
        val allMerges = (merges + other.merges).distinctBy { "${it.targetInternal}|${it.sourceInternal}" }
        return RuleSet(merged, allMerges)
    }

    companion object {
        val EMPTY: RuleSet = RuleSet()
    }
}


/**
 * Declares that "this class hands over a rule set"; annotated classes must implement
 * [RuleSetProvider]. The loader discovers them by reading bytecode only (without loading the
 * class), then loads them up to fetch the value — hence a hard constraint: **annotated classes
 * may depend only on JDK, Kotlin runtime, `oml-api`, and the classes of their own mod**, and must
 * not reference game classes (see [RuleSetProvider]). [value] is a human-readable name appearing
 * in startup logs and diagnostics, falling back to the class name when blank.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class RuleSource(val value: String = "")

/**
 * A source of a rule set. Rules must be in place **before game classes are loaded** (otherwise those classes are missed), so rule classes
 * are loaded at that moment. Once a rule class references a game class, the JVM loads that game class at link/verification time — and
 * since the transformer is not installed then, the dragged-in class is **untransformed**, and every hook depending on it misses.
 * Game class names in rules must therefore be **strings** (`classTarget("net/minecraft/client/Minecraft")`), never class references. The
 * loader statically validates this and reports an annotated class that references anything outside the whitelist.
 */
interface RuleSetProvider {
    fun rules(): RuleSet
}
