package org.ohmyloader.core.transformer.injection

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldNode
import org.objectweb.asm.tree.MethodNode
import org.ohmyloader.api.inject.AccessRule
import org.ohmyloader.api.inject.MemberKind
import org.ohmyloader.api.inject.Visibility

internal object AccessApplier {

    /** An actual change to one member's access flags. */
    internal class Change(val target: String, val from: Int, val to: Int)

    internal class Result(
        /** Members actually rewritten (the "hit count"); skipped members are not counted. */
        val hits: Int,
        val changes: List<Change>,
        /** Problems that can only be judged once the target class is available (not decidable at static-validation time). */
        val problems: List<String>,
    )

    fun apply(node: ClassNode, rule: AccessRule): Result = when (rule.kind) {
        MemberKind.CLASS -> applyToClass(node, rule)
        MemberKind.FIELD -> applyToFields(node, rule)
        MemberKind.METHOD -> applyToMethods(node, rule)
    }

    private fun applyToClass(node: ClassNode, rule: AccessRule): Result {
        // A top-level class can only be public or package-private. private/protected are the levels of **nested**
        // classes, and that visibility lives on the InnerClasses attribute's own entry (`Class.getModifiers()` reads it).
        // The criterion is "does the class file contain an InnerClasses entry pointing at itself" — javac always
        // writes such an entry for nested classes.
        val self = node.innerClasses?.firstOrNull { it.name == node.name }
        val wanted = rule.visibility
        if (wanted != null && wanted != Visibility.PUBLIC && wanted != Visibility.PACKAGE && self == null) {
            return Result(
                hits = 0,
                changes = emptyList(),
                problems = listOf(
                    rejection(
                        node.name, rule, "the class itself",
                        "a top-level class can only be public or package-private (${wanted.label} is a level only nested classes have, " +
                            "recorded in the InnerClasses attribute, but it is not in its own InnerClasses table)"
                    )
                ),
            )
        }

        val changes = mutableListOf<Change>()
        rewrite(node.access, rule, "the class itself") { node.access = it }?.let { changes += it }
        // Nested class: the class file's own flags and the InnerClasses self-entry must agree, otherwise reflection still reads the old level
        self?.let { entry ->
            rewrite(entry.access, rule, "InnerClasses self-entry") { entry.access = it }?.let { changes += it }
        }
        return Result(hits = 1, changes = changes, problems = emptyList())
    }

    private fun applyToFields(node: ClassNode, rule: AccessRule): Result {
        val matched = node.fields.filter { it.name in rule.names && (rule.desc == null || rule.desc == it.desc) }
        val problems = mutableListOf<String>()
        val usable = matched.filter { field ->
            val reason = fieldRejection(node, field, rule)
            if (reason == null) true
            else {
                problems += rejection(node.name, rule, "field ${field.name} ${field.desc}", reason)
                false
            }
        }
        val changes = usable.mapNotNull { rewriteField(it, rule) }
        problems += narrowing(node.name, rule, usable.map { it.access })
        return Result(usable.size, changes, problems)
    }

    private fun applyToMethods(node: ClassNode, rule: AccessRule): Result {
        val matched = node.methods.filter { it.name in rule.names && (rule.desc == null || rule.desc == it.desc) }
        val problems = mutableListOf<String>()
        val usable = matched.filter { method ->
            val reason = methodRejection(node, method, rule)
            if (reason == null) true
            else {
                problems += rejection(node.name, rule, "method ${method.name}${method.desc}", reason)
                false
            }
        }
        val changes = usable.mapNotNull { rewriteMethod(it, rule) }
        problems += narrowing(node.name, rule, usable.map { it.access })
        return Result(usable.size, changes, problems)
    }

    /**
     * Whether a field accepts this rule; a reason string otherwise.
     *
     * Interface fields are pinned by JVMS 4.5 to `public static final`; removing `final` or narrowing visibility
     * would produce an illegal class.
     */
    private fun fieldRejection(node: ClassNode, field: FieldNode, rule: AccessRule): String? {
        if (node.access and Opcodes.ACC_INTERFACE == 0) return null
        if (rule.removeFinal) return "interface fields must carry ACC_FINAL; final cannot be removed"
        val wanted = rule.visibility ?: return null
        if (wanted != Visibility.PUBLIC) return "interface fields must be public (cannot be ${wanted.label})"
        return null
    }

    /**
     * Whether a method accepts this rule; a reason string otherwise.
     *
     * - `<clinit>` is invoked by the JVM itself, so its visibility is meaningless to any caller (changing it only
     *   yields a rule that looks effective but does nothing);
     * - interface methods (class file ≥ 52) must have exactly one of `ACC_PUBLIC` or `ACC_PRIVATE`, so
     *   `protected`/package-private are illegal; and `ACC_PRIVATE` requires a method body, so an abstract method
     *   cannot be made private.
     */
    private fun methodRejection(node: ClassNode, method: MethodNode, rule: AccessRule): String? {
        if (method.name == "<clinit>") return "<clinit> is invoked by the JVM itself, so its visibility is meaningless to any caller"
        if (node.access and Opcodes.ACC_INTERFACE == 0) return null
        return when (val wanted = rule.visibility) {
            Visibility.PROTECTED, Visibility.PACKAGE ->
                "interface methods can only be public or private (${wanted.label} is illegal)"

            Visibility.PRIVATE -> if (method.access and Opcodes.ACC_ABSTRACT != 0)
                "an interface's abstract method cannot be private (private interface methods must have a body)" else null

            else -> null
        }
    }

    /** Warns on visibility narrowing (still rewrites as usual; only reminds the caller it may fail). */
    private fun narrowing(owner: String, rule: AccessRule, access: List<Int>): List<String> {
        val wanted = rule.visibility ?: return emptyList()
        val narrowed = access.filter { Visibility.of(it).rank < wanted.rank }
        if (narrowed.isEmpty()) return emptyList()
        val from = narrowed.map { Visibility.of(it).label }.distinct().joinToString(", ")
        return listOf(
            "$owner :: ${rule.describe()} — visibility narrowed ($from → ${wanted.label}); rewritten per the rule, " +
                "but callers compiled against the original (wider) access will throw IllegalAccessError at link time"
        )
    }

    private fun rejection(owner: String, rule: AccessRule, target: String, reason: String): String =
        "$owner :: ${rule.describe()} — $target does not accept this rule: $reason; this rule was not applied"

    private fun rewriteField(field: FieldNode, rule: AccessRule): Change? =
        rewrite(field.access, rule, "field ${field.name} ${field.desc}") { field.access = it }

    private fun rewriteMethod(method: MethodNode, rule: AccessRule): Change? =
        rewrite(method.access, rule, "method ${method.name}${method.desc}") { method.access = it }

    /** Rewrites one member's flags; returns null when there is no change. */
    private fun rewrite(from: Int, rule: AccessRule, target: String, set: (Int) -> Unit): Change? {
        var to = from
        rule.visibility?.let { to = (to and Visibility.MASK.inv()) or it.flag }
        if (rule.removeFinal) to = to and Opcodes.ACC_FINAL.inv()
        if (to == from) return null
        set(to)
        return Change(target, from, to)
    }
}
