package org.ohmyloader.core.ruleset

import org.ohmyloader.api.OmlLog
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import org.ohmyloader.api.inject.MergeSource
import org.ohmyloader.api.inject.Payload
import org.ohmyloader.api.inject.RuleSet
import org.ohmyloader.api.inject.RuleSetProvider
import org.ohmyloader.core.mixin.ClassMerger
import org.ohmyloader.core.mod.ModContainer
import java.io.File
import java.util.jar.JarFile

/**
 * Aggregates the rule sets contributed by mods into one: **discovery** (read-only
 * bytecode) → **verification** → **provision**, in that fixed order.
 * Verification must run before loading: once a rule class references a game class, loading it
 * drags that game class into the JVM while the transformer is not yet installed, so the dragged-in
 * copy is an **untransformed** game class and every hook that depends on it will silently miss.
 * Provision is the only step that executes mod code, so it comes last, and every exception is
 * translated into a readable problem rather than bubbling up into the startup flow.
 * Verification inspects only the **static reference surface** (superclass / interfaces / field and method
 * descriptors / owners in instructions). It does not prove that game classes are never touched at runtime
 * (that relies on the author), but it blocks the most common mistake: using a game class as a type.
 */
internal object ModRuleSets {

    /**
     * Package prefixes a rule class is allowed to reference.
     *
     * Only the JDK, the Kotlin runtime and OML's public API — rule classes are loaded
     * **first**, so their dependency surface is exactly the set of things that are guaranteed
     * safe at that point. A mod's own classes are additionally allowed (anything inside the
     * same jar counts).
     */
    private val ALLOWED_PREFIXES = listOf(
        "java/", "javax/", "jdk/", "sun/", "com/sun/",
        "kotlin/", "kotlinx/",
        "org/ohmyloader/api/",
    )

    private const val RULE_SOURCE_DESC = "Lorg/ohmyloader/api/inject/RuleSource;"
    private const val PROVIDER_INTERNAL = "org/ohmyloader/api/inject/RuleSetProvider"
    private const val INSTANCE_FIELD = "INSTANCE"

    /**
     * The result of a single aggregation run.
     *
     * [sources] feeds the startup log ("who contributed how much"), [problems] is merged
     * into the startup self-check (reported together with the annotation front-end problems).
     */
    class Loaded(
        val rules: RuleSet,
        /** Sources declared by the rule set to be merged into target classes, prepared as bytecode (see [MergeSource]). */
        val merges: List<ClassMerger.MixinClass>,
        val sources: List<String>,
        val problems: List<String>,
    )

    /** Intermediate result of the discovery phase: a class declaring `@RuleSource`. */
    private class Found(
        val modId: String,
        val internalName: String,
        val id: String?,
        val node: ClassNode,
        /** The jar that declared it — a merge source is only readable when it lives in the **same jar**. */
        val modFile: File,
        /** Class names inside the same jar — treated as "own" and allowed to reference each other. */
        val ownJarClasses: Set<String>,
    )

    fun load(mods: List<ModContainer>, loader: ClassLoader): Loaded {
        val problems = mutableListOf<String>()
        val found = mutableListOf<Found>()
        // The same jar may declare several @Mod entry points, and they share one set of rule
        // classes: dedupe by class name so a shared @RuleSource is merged once, not once per mod.
        val seenRuleClasses = HashSet<String>()
        for (mod in mods) {
            if (mod.file.isFile) scan(mod, found, problems, seenRuleClasses)
        }

        val sources = mutableListOf<String>()
        val merges = mutableListOf<ClassMerger.MixinClass>()
        var rules = RuleSet.EMPTY
        for (item in found) {
            val label = item.internalName + (item.id?.let { "($it)" } ?: "")
            val issues = verify(item)
            problems += issues
            if (issues.isNotEmpty()) continue
            val provided = provide(item, loader, problems) ?: continue
            rules = rules.merge(provided)
            merges += resolveMerges(item, provided, problems)
            val count = provided.classes.values.sumOf { it.methods.size + it.accessRules.size }
            val merged = if (provided.merges.isEmpty()) "" else ", ${provided.merges.size} merges"
            sources += "${item.modId}: $label -> $count rules / ${provided.classes.size} target classes$merged"
        }
        return Loaded(rules, merges, sources, problems)
    }

    // ------------------------------------------------------------- Discovery

    /** Reads bytecode only: finds classes annotated with `@RuleSource` and collects the class names in the same jars as "own". */
    private fun scan(
        mod: ModContainer,
        found: MutableList<Found>,
        problems: MutableList<String>,
        seenRuleClasses: HashSet<String>,
    ) {
        try {
            JarFile(mod.file).use { zip ->
                val entries = mutableListOf<String>()
                val all = zip.entries()
                while (all.hasMoreElements()) {
                    val name = all.nextElement().name
                    if (name.endsWith(".class")) entries += name.removeSuffix(".class")
                }
                val own = entries.toSet()
                for (entry in entries) {
                    zip.getInputStream(zip.getEntry("$entry.class")).use { stream ->
                        val node = ClassNode(Opcodes.ASM9)
                        ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
                        if (!hasRuleSource(node)) return@use
                        // One class = one merge: the same rule class inside a multi-mod jar is
                        // registered for the first @Mod only, otherwise the merge runs twice and the
                        // second run collides with its own first (@Overwrite ledger / synthetic members)
                        if (!seenRuleClasses.add(node.name)) {
                            OmlLog.warn("OMLCore", "rule class ${node.name} already registered by an earlier @Mod in this jar; skipping duplicate")
                            return@use
                        }
                        found += Found(mod.id, node.name, ruleSourceId(node), node, mod.file, own)
                    }
                }
            }
        } catch (t: Throwable) {
            problems += "[${mod.id}] failed to scan rule set: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    private fun annotationsOf(node: ClassNode) =
        (node.visibleAnnotations ?: emptyList()) + (node.invisibleAnnotations ?: emptyList())

    private fun hasRuleSource(node: ClassNode): Boolean =
        annotationsOf(node).any { it.desc == RULE_SOURCE_DESC }

    /** The `value` of `@RuleSource`; an absent value or blank string counts as none, falling back to the class name. */
    private fun ruleSourceId(node: ClassNode): String? {
        val annotation = annotationsOf(node).firstOrNull { it.desc == RULE_SOURCE_DESC } ?: return null
        // The annotation has a single optional parameter; ASM's values is a flat [name, value] table
        return (annotation.values?.getOrNull(1) as? String)?.takeIf { it.isNotBlank() }
    }

    // ------------------------------------------------------------- Verification

    /** A rule class must implement [RuleSetProvider], and every class it references must be safely loadable. */
    private fun verify(item: Found): List<String> {
        val problems = mutableListOf<String>()
        val where = "[${item.internalName}]"
        if (!item.node.interfaces.contains(PROVIDER_INTERNAL)) {
            problems += "$where declares @RuleSource but does not implement RuleSetProvider"
        }
        for (name in referencedTypes(item.node)) {
            if (isAllowed(name, item)) continue
            problems += "$where references $name -- rule classes are loaded before game classes, may only depend on JDK / " +
                "Kotlin runtime / oml-api and itself; game classes may only appear in rules as strings"
        }
        return problems
    }

    private fun isAllowed(internalName: String, item: Found): Boolean {
        if (internalName == item.internalName) return true
        if (internalName in item.ownJarClasses) return true
        return ALLOWED_PREFIXES.any { internalName.startsWith(it) }
    }

    /**
     * The class's **static reference surface**: superclass, interfaces, field/method
     * descriptors, and owners/types appearing in instructions.
     *
     * Inspecting these alone is sufficient: using a game class as a type (inheritance, field
     * type, method signature, `new`, invocation) all fall here, which is exactly what would
     * drag a game class in when the rule class is loaded. Strings inside annotations are not
     * included — those are the intended way to name game classes in rules.
     */
    internal fun referencedTypes(node: ClassNode): Set<String> {
        val out = mutableSetOf<String>()
        node.superName?.let(out::add)
        out += node.interfaces

        fun collect(type: Type) {
            when (type.sort) {
                Type.ARRAY -> collect(type.elementType)
                Type.OBJECT -> out += type.internalName
                Type.METHOD -> {
                    collect(type.returnType)
                    type.argumentTypes.forEach { collect(it) }
                }

                else -> Unit
            }
        }

        for (field in node.fields) collect(Type.getType(field.desc))
        for (method in node.methods) {
            collect(Type.getMethodType(method.desc))
            method.exceptions?.let(out::addAll)
            for (insn in method.instructions) {
                when (insn) {
                    is TypeInsnNode -> if (!insn.desc.startsWith("[")) out += insn.desc
                    is FieldInsnNode -> {
                        out += insn.owner
                        collect(Type.getType(insn.desc))
                    }

                    is MethodInsnNode -> {
                        out += insn.owner
                        collect(Type.getMethodType(insn.desc))
                    }

                    is InvokeDynamicInsnNode -> {
                        collect(Type.getMethodType(insn.desc))
                        out += insn.bsm.owner
                        for (arg in insn.bsmArgs) if (arg is Type) collect(arg)
                    }

                    is MultiANewArrayInsnNode -> if (!insn.desc.startsWith("[")) out += insn.desc
                    is LdcInsnNode -> if (insn.cst is Type) collect(insn.cst as Type)
                    else -> Unit
                }
            }
            for (block in method.tryCatchBlocks ?: emptyList()) block.type?.let(out::add)
        }
        return out
    }

    // ------------------------------------------------------------- Merge sources

    /**
     * Reads the merge sources declared in the rule set into [ClassMerger.MixinClass].
     *
     * **Bytecode only, classes are not loaded**: a source class is exactly the kind of class
     * that necessarily references game classes (it moves into a game class), so loading it
     * would drag game classes in. Reading requires `EXPAND_FRAMES` — merging moves whole method
     * bodies into the target class, frames must move along and cannot be recovered once dropped.
     */
    private fun resolveMerges(
        item: Found,
        rules: RuleSet,
        problems: MutableList<String>,
    ): List<ClassMerger.MixinClass> {
        if (rules.merges.isEmpty()) return emptyList()
        val out = mutableListOf<ClassMerger.MixinClass>()
        JarFile(item.modFile).use { zip ->
            for (source in rules.merges) {
                val entry = zip.getEntry("${source.sourceInternal}.class")
                if (entry == null) {
                    problems += "[${item.internalName}] the source to merge into ${source.targetInternal} is not in the same jar: " +
                        "${source.sourceInternal} (a merge source must be packaged together with the class declaring it)"
                    continue
                }
                val node = ClassNode(Opcodes.ASM9)
                zip.getInputStream(entry).use { stream ->
                    ClassReader(stream).accept(node, ClassReader.SKIP_DEBUG or ClassReader.EXPAND_FRAMES)
                }
                out += ClassMerger.MixinClass(
                    modId = item.modId,
                    className = source.sourceInternal,
                    targetInternal = source.targetInternal,
                    node = node,
                    handlerMethods = handlerKeys(rules, source.targetInternal),
                )
            }
        }
        return out
    }

    /**
     * Handlers directly referenced by injection points and living in the target class (`name + desc`).
     *
     * During merging they are **renamed and made `public`**, both are required: renaming would
     * cause an injection-point crash at runtime with `NoSuchMethodError`, and a private method
     * cannot be invoked via `INVOKEVIRTUAL` (a `VerifyError` when the class is defined).
     */
    private fun handlerKeys(rules: RuleSet, target: String): Set<String> {
        val classRules = rules.classes[target] ?: return emptySet()
        val out = mutableSetOf<String>()
        for (rule in classRules.methods) {
            for ([_, payload] in rule.points) {
                if (payload is Payload.HandlerCall && payload.owner == target) {
                    out += payload.method + payload.desc
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------- Provision

    /**
     * Loads, instantiates and calls [RuleSetProvider.rules].
     *
     * Two shapes are supported: a Kotlin `object` (reads `INSTANCE`) and an ordinary class with
     * a no-arg constructor. Both are attempted; if neither holds, a problem is reported — the
     * shape of a rule class (singleton vs. created per call) is the author's choice, the loader
     * does not decide it for them.
     */
    private fun provide(item: Found, loader: ClassLoader, problems: MutableList<String>): RuleSet? {
        val binaryName = item.internalName.replace('/', '.')
        return try {
            val type = Class.forName(binaryName, true, loader)
            val instance = type.declaredFields.firstOrNull { it.name == INSTANCE_FIELD }?.get(null)
                ?: type.getDeclaredConstructor().newInstance()
            val provider = instance as? RuleSetProvider
            if (provider == null) {
                problems += "[${item.internalName}] not a RuleSetProvider (actual: ${instance.javaClass.name})"
                null
            } else {
                provider.rules()
            }
        } catch (t: Throwable) {
            problems += "[${item.internalName}] value lookup failed: ${t.javaClass.simpleName}: ${t.message}"
            null
        }
    }
}
