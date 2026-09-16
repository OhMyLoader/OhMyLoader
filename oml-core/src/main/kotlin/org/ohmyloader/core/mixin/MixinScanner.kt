package org.ohmyloader.core.mixin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.ohmyloader.api.inject.*
import org.ohmyloader.api.mixin.LocalCapture
import org.ohmyloader.core.mixin.MixinScanner.parseRedirect
import org.ohmyloader.core.mixin.MixinScanner.policyProblem
import org.ohmyloader.core.mixin.MixinScanner.refineVarAnchor
import org.ohmyloader.core.mod.ModContainer
import org.ohmyloader.core.transformer.IClassTransformer
import org.ohmyloader.core.transformer.injection.AnchorResolver
import org.ohmyloader.core.transformer.injection.InjectingTransformer
import java.io.File
import java.util.jar.JarFile

/**
 * Mixed **annotation front-end**: reads `@Mixin` / `@Inject` / `@Redirect` / `@Modify*` from mod classes and
 * translates them into the injection DSL's "anchor + payload + match-count policy", handed off to the engine
 * to weave. The front-end never touches the bytecode itself — its job is interpreting annotation semantics
 * and reporting statically decidable problems at startup, because a wrongly written annotation does not crash
 * the game; it just silently does one thing less. Match-count semantics follow Mixin (`require` lower bound /
 * `allow` upper bound / `expect` count warning). Local capture (`@Inject(locals = …)`) binds handler params by
 * the type order of the local-variable table at the injection point, resolved by dataflow analysis — never by
 * variable name (an obfuscated jar may carry no local-variable table at all, and when present the names are
 * obfuscated). `Shift.BY` offsets, dynamic `@Mixin(targets = "…#…")`, `@Pseudo` and refmap are unsupported
 * (`BY` makes the injection point drift as target code changes). See [AtParser] / [ClassMerger].
 */
object MixinScanner {

    /**
     * A fully-compiled injection rule: both the anchor and payload are already engine objects
     * (the annotation has been fully interpreted).
     *
     * The front-end **directly constructs engine objects** (instead of going back through `atHead { … }`
     * DSL sugar) because `@Slice`/`shift`/`ordinal` are already ready-made objects in the front-end;
     * attaching them directly interprets the semantics only once.
     */
    internal class Rule(
        val targetInternal: String,
        val methodNames: List<String>,
        val methodDesc: String?,
        val anchor: InjectionPoint,
        val payload: Payload,
        val policy: Policy,
        val captures: List<Capture>,
        val kind: String,
    )

    /**
     * One capture: handler param [handlerSlot] corresponds to the **[ordinal]**-th local variable of
     * type [type] at the injection point.
     *
     * Note there is **no "target slot"** here -- the slot is computed by the dataflow analysis at the
     * injection point. Statically we only know "type + which occurrence", which is exactly Mixin's
     * capture semantics (it likewise does not guarantee a stable slot).
     */
    internal data class Capture(val handlerSlot: Int, val type: String, val ordinal: Int, val strict: Boolean)

    /** Match-count policy (Mixin's `require`/`expect`/`allow`; `null` = not declared). */
    internal data class Policy(val require: Int? = null, val expect: Int? = null, val allow: Int? = null)

    /**
     * Scan all mods and generate the mixin transformer.
     *
     * Returns `null` when there are no rules **and** no problems (no mixin); but when there are
     * problems yet no rules, an empty-rule transformer is still returned -- so the problems flow
     * into the startup self-check (which fails by default) instead of **silently vanishing**: returning
     * null would skip the self-check too, making the game look fine while all injections are gone.
     */
    internal fun createTransformer(
        mods: List<ModContainer>,
        /**
         * The rule set supplied by mods themselves (see [org.ohmyloader.core.ruleset.ModRuleSets]).
         *
         * It is merged **into the same spec** as the annotation rules: the same self-check, the same
         * match-count report; two rules hitting the same injection point can also see each other.
         * Running them as two separate transformers would work too, but neither could see the other.
         */
        extraRules: RuleSet = RuleSet.EMPTY,
        /** Sources those rule sets declare to be merged into target classes, following the same path as annotation mixin merges (merge first, then inject). */
        extraMerges: List<ClassMerger.MixinClass> = emptyList(),
        /** Problems in the discovery/retrieval phase of those rule sets, folded into the same self-check report. */
        extraProblems: List<String> = emptyList(),
    ): IClassTransformer? {
        val rules = mutableListOf<Rule>()
        val problems = mutableListOf<String>()
        val merges = mutableListOf<ClassMerger.MixinClass>()
        // A jar with several @Mod entry points yields one container per entry, all sharing the same
        // physical file: scanning it once per entry would register every mixin class twice, and the
        // second merge would collide with its own first (@Overwrite ledger / synthesized accessors).
        // The first @Mod owns the jar's mixins, mirroring the rule-class dedupe in ModRuleSets.
        val seenJars = HashSet<File>()
        for (mod in mods) {
            if (!seenJars.add(mod.file.absoluteFile)) continue
            val scanned = scanMod(mod)
            rules += scanned.rules
            problems += scanned.problems
            merges += scanned.merges
        }
        problems += extraProblems
        merges += extraMerges
        if (rules.isEmpty() && merges.isEmpty() && problems.isEmpty() && extraRules.classes.isEmpty()) {
            return null
        }

        // Bridge classes are generated only after all rules are registered: the generator has to emit
        // method signatures based on the handler's shape
        if (rules.isNotEmpty()) OMLMixinRegistry.ensureBridgeGenerated()

        val spec = injection {
            for (rule in rules) {
                classTarget(rule.targetInternal) {
                    method(*rule.methodNames.distinct().toTypedArray(), desc = rule.methodDesc) {
                        point(rule.anchor, rule.payload)
                        rule.policy.require?.let { require(it) }
                        rule.policy.allow?.let { allow(it) }
                        rule.policy.expect?.let { expect(it) }
                        if (rule.policy.require == null && rule.policy.allow == null) {
                            // Mixin's default is "soft failure" (a missing injection point only logs), and its
                            // expect is only checked under a debug switch -- so, when no policy is declared,
                            // treat the rule as optional so per-version rules stop spamming "unmatched" warnings.
                            optional()
                        }
                    }
                }
            }
        }.merge(extraRules)
        if (rules.isEmpty() && problems.isNotEmpty()) {
            System.err.println("[Mixin] the front-end produced no injection rules, but there are ${problems.size} problems:")
            problems.forEach { System.err.println("[Mixin]   $it") }
        }
        return MixinTransformer(
            id = "mixin",
            merges = merges,
            // InjectingTransformer is an abstract base class (subclasses only declare rules and an id); this is
            // that subclass
            injection = if (rules.isEmpty()) null else object : InjectingTransformer(spec, id = "mixin") {},
            verifyExtras = problems,
        )
    }

    internal class ScanResult(
        val rules: List<Rule>,
        val problems: List<String>,
        /**
         * Whether this class should go through **class merging**.
         *
         * It is a **list**, not a single value: a single jar can contain multiple merge mixins (e.g. one
         * `@Overwrite` and one `@Shadow`); keeping only one would make the other disappear silently.
         */
        val merges: List<ClassMerger.MixinClass> = emptyList(),
    )

    // ---------- annotation descriptors ----------

    private const val MIXIN_DESC = "Lorg/ohmyloader/api/mixin/Mixin;"
    private const val INJECT_DESC = "Lorg/ohmyloader/api/mixin/Inject;"
    private const val REDIRECT_DESC = "Lorg/ohmyloader/api/mixin/Redirect;"
    private const val MODIFY_ARG_DESC = "Lorg/ohmyloader/api/mixin/ModifyArg;"
    private const val MODIFY_ARGS_DESC = "Lorg/ohmyloader/api/mixin/ModifyArgs;"
    private const val MODIFY_CONSTANT_DESC = "Lorg/ohmyloader/api/mixin/ModifyConstant;"
    private const val MODIFY_VARIABLE_DESC = "Lorg/ohmyloader/api/mixin/ModifyVariable;"
    private const val MODIFY_RETURN_VALUE_DESC = "Lorg/ohmyloader/api/mixin/ModifyReturnValue;"
    private const val MODIFY_EXPRESSION_VALUE_DESC = "Lorg/ohmyloader/api/mixin/ModifyExpressionValue;"
    private const val SHADOW_DESC = "Lorg/ohmyloader/api/mixin/Shadow;"
    private const val UNIQUE_DESC = "Lorg/ohmyloader/api/mixin/Unique;"
    private const val OVERWRITE_DESC = "Lorg/ohmyloader/api/mixin/Overwrite;"

    /** Declaring any of these ⇒ this mixin goes through the **class-merge** model (see mergeCandidates). */
    private val MERGE_MEMBER_ANNOTATIONS = setOf(SHADOW_DESC, UNIQUE_DESC, OVERWRITE_DESC)

    /** accessor/invoker: only valid on the abstract methods of an **interface** mixin (synthesis path). */
    private val MERGE_ACCESSOR_ANNOTATIONS = setOf(
        "Lorg/ohmyloader/api/mixin/Accessor;",
        "Lorg/ohmyloader/api/mixin/Invoker;",
    )

    /** Injection annotations this front-end recognizes → their names (for diagnostics). */
    private val INJECTION_ANNOTATIONS = mapOf(
        INJECT_DESC to "@Inject",
        REDIRECT_DESC to "@Redirect",
        MODIFY_ARG_DESC to "@ModifyArg",
        MODIFY_ARGS_DESC to "@ModifyArgs",
        MODIFY_CONSTANT_DESC to "@ModifyConstant",
        MODIFY_VARIABLE_DESC to "@ModifyVariable",
        MODIFY_RETURN_VALUE_DESC to "@ModifyReturnValue",
        MODIFY_EXPRESSION_VALUE_DESC to "@ModifyExpressionValue",
    )

    /** Tail kind of the handler parameter list (`@Inject` uses the first two; the other annotations have no callback handle). */
    private enum class Tail { CALLBACK_INFO, RETURNABLE, NONE }

    private const val CALLBACK_INFO_DESC = "Lorg/ohmyloader/api/mixin/CallbackInfo;"
    private const val CALLBACK_RETURNABLE_DESC = "Lorg/ohmyloader/api/mixin/CallbackInfoReturnable;"
    private const val ARGS_DESC = "Lorg/ohmyloader/api/mixin/Args;"

    // ---------- scanning ----------

    private fun scanMod(mod: ModContainer): ScanResult {
        if (!mod.file.isFile) return ScanResult(emptyList(), emptyList())
        val rules = mutableListOf<Rule>()
        val problems = mutableListOf<String>()
        val merges = mutableListOf<ClassMerger.MixinClass>()
        JarFile(mod.file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".class")) continue
                try {
                    zip.getInputStream(entry).use { stream ->
                        val node = ClassNode(Opcodes.ASM9)
                        // Keep instructions (do not SKIP_CODE): we must decide whether the injection handler has a body --
                        // skipping them would misjudge every handler as abstract/native.
                        // **Keep stack frames (EXPAND_FRAMES)**: class merging moves whole method bodies into
                        // the target class, and the loader writes back with COMPUTE_MAXS (not COMPUTE_FRAMES,
                        // see OMLClassLoader); frames can only be carried along -- dropping them at read time
                        // can never be recovered later.
                        val reader = ClassReader(stream)
                        reader.accept(node, ClassReader.SKIP_DEBUG or ClassReader.EXPAND_FRAMES)
                        val parsed = parseMixinClass(node, mod.id)
                        rules += parsed.rules
                        problems += parsed.problems
                        merges += parsed.merges
                    }
                } catch (t: Throwable) {
                    problems += "[${mod.id}] could not read ${entry.name}: ${t.javaClass.simpleName}: ${t.message}"
                }
            }
        }
        return ScanResult(rules, problems, merges)
    }

    /** Parse the `@Mixin` declaration on a single class and the injection annotations on its methods; returns an empty table for non-mixin classes. */
    internal fun parseMixinClass(node: ClassNode, modId: String): ScanResult {
        val target = node.visibleAnnotations
            ?.find { it.desc == MIXIN_DESC }
            ?.let { annotationValue(it, "target") as? String }
            ?: return ScanResult(emptyList(), emptyList())
        val targetInternal = target.replace('.', '/')
        val mixinClass = node.name.replace('/', '.')

        val rules = mutableListOf<Rule>()
        val problems = mutableListOf<String>()
        // Collect `@Slice` first (`@At(slice = "id")` looks up by id; an empty id is the default slice)
        val slices = node.visibleAnnotations.orEmpty()
            .filter { it.desc.endsWith("/Slice;") }.associateBy { (annotationValue(it, "id") as? String).orEmpty() }

        // Does this mixin use the **merge model**? If so, its **instance** `@Inject` handlers ride into
        // the target class via class merge, and the injection point becomes a direct INVOKEVIRTUAL call
        // (`this` = the target instance) -- see parseInject.
        val mergeInto = if (isMergeAnnotated(node)) targetInternal else null
        val handlers = mutableSetOf<String>()

        for (method in node.methods) {
            val annotations = method.visibleAnnotations.orEmpty().filter { it.desc in INJECTION_ANNOTATIONS }
            if (annotations.isEmpty()) continue
            if (method.instructions.size() == 0) {
                problems += "[$modId] $mixinClass.${method.name} —— the method carrying the injection annotation must have a method body" +
                    " (the handler cannot be abstract/native)"
                continue
            }
            for (annotation in annotations) {
                parseInjection(
                    method, annotation, targetInternal, mixinClass, modId, slices,
                    mergeInto, handlers, rules, problems,
                )
            }
        }

        val merge = mergeCandidates(node, targetInternal, mixinClass, modId, handlers, problems)
        return ScanResult(rules, problems, listOfNotNull(merge))
    }

    /**
     * Whether this mixin uses the **merge model**: it is true when any of `@Shadow` / `@Unique` / `@Overwrite`
     * is declared.
     *
     * The criterion lives on the annotations, not on guesses like "how many members the target class
     * has": guessing would also merge mixins that only write injection handlers, copying their handler
     * methods into the target class. The annotation criterion also lets authors see at a glance which
     * path their mixin takes.
     */
    private fun isMergeAnnotated(node: ClassNode): Boolean =
        node.fields.any { it.visibleAnnotations.orEmpty().any { a -> a.desc in MERGE_MEMBER_ANNOTATIONS } } ||
            node.methods.any { it.visibleAnnotations.orEmpty().any { a -> a.desc in MERGE_MEMBER_ANNOTATIONS } }

    private fun mergeCandidates(
        node: ClassNode,
        targetInternal: String,
        mixinClass: String,
        modId: String,
        handlerMethods: Set<String>,
        problems: MutableList<String>,
    ): ClassMerger.MixinClass? {
        // Interface mixin: `@Accessor`/`@Invoker` are declared on the interface's abstract methods, and the
        // engine synthesizes method bodies in the target class from the signatures. An interface should
        // contain nothing else
        if (node.access and Opcodes.ACC_INTERFACE != 0) {
            val hasAccessor = node.methods.any { m ->
                m.visibleAnnotations.orEmpty().any { a -> a.desc in MERGE_ACCESSOR_ANNOTATIONS }
            }
            val misplaced = node.methods.filter { m ->
                m.visibleAnnotations.orEmpty()
                    .any { a -> a.desc !in MERGE_ACCESSOR_ANNOTATIONS && a.desc in MERGE_MEMBER_ANNOTATIONS }
            }
            for (method in misplaced) {
                problems += "[$modId] $mixinClass.${method.name} —— an interface mixin's methods may only carry @Accessor/@Invoker," +
                    "@Shadow/@Overwrite are for class mixins (the interface has no body to merge in)"
            }
            val injectables = node.methods.filter { m ->
                m.visibleAnnotations.orEmpty().any { a -> a.desc in INJECTION_ANNOTATIONS }
            }
            for (method in injectables) {
                problems += "[$modId] $mixinClass.${method.name} —— injection annotations may only be used in class mixins:" +
                    " an interface mixin only does accessor/invoker synthesis"
            }
            if (!hasAccessor) return null
            if (node.fields.isNotEmpty()) {
                problems += "[$modId] $mixinClass —— an interface mixin cannot declare fields (accessor/invoker are only signature declarations)"
            }
            return ClassMerger.MixinClass(modId, mixinClass, targetInternal, node)
        }

        if (!isMergeAnnotated(node)) return null

        // Writing `@Accessor`/`@Invoker` in a class mixin: they only make sense for interface mixins
        // (synthesis needs "abstract methods", while a class mixin's methods either have a body or are
        // `@Shadow`)
        for (method in node.methods) {
            val misplaced = method.visibleAnnotations.orEmpty().find { a -> a.desc in MERGE_ACCESSOR_ANNOTATIONS }
            if (misplaced != null) {
                problems += "[$modId] $mixinClass.${method.name} —— @Accessor/@Invoker may only be used on interface mixins:" +
                    " synthesis needs the abstract methods to declare signatures, while a class mixin's methods either have a body or are @Shadow"
            }
        }

        // The instance-injection-handler checks are done in parseInjection (there the annotation name is
        // available, so the message is more precise and not double-reported) --
        // `handlerMethods` is the set of "directly callable handlers" registered there.
        return ClassMerger.MixinClass(modId, mixinClass, targetInternal, node, handlerMethods)
    }

    /** Parse one injection annotation → one rule, or several problems. */
    private fun parseInjection(
        method: MethodNode,
        annotation: AnnotationNode,
        targetInternal: String,
        mixinClass: String,
        modId: String,
        slices: Map<String, AnnotationNode>,
        mergeInto: String?,
        handlers: MutableSet<String>,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val where = "$mixinClass.${method.name}"
        val shortName = INJECTION_ANNOTATIONS[annotation.desc] ?: return

        // Target method names (union of method / methods)
        val methodNames = buildList {
            (annotationValue(annotation, "method") as? String)?.takeIf { it.isNotEmpty() }?.let(::add)
            addAll(stringArray(annotationValue(annotation, "methods")))
        }.distinct()
        if (methodNames.isEmpty()) {
            problems += "[$modId] $where —— $shortName must specify a target method name (method = \"…\")"
            return
        }
        val methodDesc = ((annotationValue(annotation, "desc") as? String) ?: "").takeIf { it.isNotEmpty() }
        val policy = Policy(
            require = declaredInt(annotation, "require"),
            expect = declaredInt(annotation, "expect"),
            allow = declaredInt(annotation, "allow"),
        )
        policyProblem(where, shortName, policy)?.let { problems += "[$modId] $it" }

        // `@ModifyConstant` has no at (it locates the constant with `constant = @Constant(...)`)
        if (annotation.desc == MODIFY_CONSTANT_DESC) {
            // Instance handler among merge candidates: the anchor is the same, only the handler merged
            // into the target class
            if (mergeInto != null && !method.isDeclaredStatic()) {
                val constant = annotationValue(annotation, "constant") as? AnnotationNode
                val base = InjectionPoint.Constant(
                    value = constant?.let { constantValue(it) },
                    ordinal = constant?.let { declaredInt(it, "ordinal") },
                    after = true,
                )
                val mergedAnchor = withSlice(base, null, annotation, slices, where, shortName, problems)
                parseMergedModify(
                    method, annotation, mergedAnchor, targetInternal, methodNames, methodDesc,
                    modId, mergeInto, handlers, HandlerKind.MODIFY_CONST, rules, problems,
                )
                return
            }
            parseModifyConstant(
                method,
                annotation,
                targetInternal,
                methodNames,
                methodDesc,
                mixinClass,
                modId,
                where,
                policy,
                slices,
                rules,
                problems
            )
            return
        }

        // `@ModifyReturnValue` / `@ModifyExpressionValue` change a **value**: the value is only on the stack
        // **after** the anchor has produced it, so the anchor is forced to `after` here (except `RETURN`,
        // whose return value is already pushed before it and consumed by it).
        // Mixin's form has no before/after layer, so deciding it for the author is the faithful thing.
        if (annotation.desc == MODIFY_RETURN_VALUE_DESC || annotation.desc == MODIFY_EXPRESSION_VALUE_DESC) {
            val isReturnValue = annotation.desc == MODIFY_RETURN_VALUE_DESC
            val atNode = annotationValue(annotation, "at") as? AnnotationNode
            // `@ModifyReturnValue`'s at defaults to RETURN: use it when the annotation omits it (or the
            // default cannot be read at runtime)
            val parsed = if (atNode == null) {
                if (isReturnValue) InjectionPoint.Return(null) else null
            } else {
                AtParser.parse(atNode)
            }
            if (parsed == null) {
                problems += if (atNode == null) {
                    "[$modId] $where —— $shortName is missing at = @At(…)"
                } else {
                    "[$modId] $where —— $shortName's @At(value = \"${annotationValue(atNode, "value")}\") " +
                        "is not a recognized injection point (available: ${AtParser.SUPPORTED.joinToString("/")})"
                }
                return
            }
            val refined = if (isReturnValue) parsed else forceAfter(parsed)
            val anchor = withSlice(refined, atNode, annotation, slices, where, shortName, problems)
            val kind = if (isReturnValue) HandlerKind.MODIFY_RETURN else HandlerKind.MODIFY_EXPR_VALUE
            // Here `mergeInto` is checked directly instead of reusing the `mergedInstance` below -- this block
            // has to come **before** the "must have at" check (which `@ModifyReturnValue` can omit), while
            // `mergedInstance` is only computed after that point.
            if (mergeInto != null && !method.isDeclaredStatic()) {
                parseMergedModify(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc,
                    modId, mergeInto, handlers, kind, rules, problems,
                )
            } else if (isReturnValue) {
                parseModifyReturnValue(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc,
                    mixinClass, modId, where, policy, rules, problems,
                )
            } else {
                parseModifyExpressionValue(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc,
                    mixinClass, modId, where, policy, rules, problems,
                )
            }
            return
        }

        val atNode = annotationValue(annotation, "at") as? AnnotationNode
        if (atNode == null) {
            problems += "[$modId] $where —— $shortName is missing at = @At(…)"
            return
        }
        val point = AtParser.parse(atNode)
        if (point == null) {
            problems += "[$modId] $where —— $shortName's @At(value = \"${annotationValue(atNode, "value")}\") " +
                "is not a recognized injection point (available: ${AtParser.SUPPORTED.joinToString("/")})"
            return
        }

        // @ModifyVariable's discriminator (index/ordinal/argsOnly) lives in its own fields; @At only
        // specifies "which access + which occurrence" -- only the combination yields a complete anchor,
        // so it takes its own path.
        if (annotation.desc == MODIFY_VARIABLE_DESC) {
            val refined = refineVarAnchor(method, annotation, point, where, shortName, problems) ?: return
            val varAnchor = withSlice(refined, atNode, annotation, slices, where, shortName, problems)
            val mergedInstance = mergeInto != null && !method.isDeclaredStatic()
            if (mergedInstance) {
                parseMergedModify(
                    method, annotation, varAnchor, targetInternal, methodNames, methodDesc,
                    modId, mergeInto, handlers, HandlerKind.MODIFY_VAR, rules, problems,
                )
            } else {
                parseModifyVariable(
                    method, annotation, varAnchor, targetInternal, methodNames, methodDesc,
                    mixinClass, modId, where, policy, rules, problems,
                )
            }
            return
        }

        val anchor = withSlice(point, atNode, annotation, slices, where, shortName, problems)

        // ---------- **instance** handlers among merge candidates ----------
        // The handler rides into the target class via merge ⇒ the injection point calls it directly
        // (`this` = target instance; `@Shadow` fields are really readable), so registry / bridge /
        // reflection are all out of the picture.
        //
        // The criterion must be "instance or non-static", never "static or non-instance": Java methods are
        // marked static in `InvokeDynamic`-style elements (see [isDeclaredStatic]); writing
        // `access and ACC_STATIC != 0` would misjudge an instance handler as static and fall back to the
        // bridge path -- but the bridge calls the **mixin instance**, whose `@Shadow` fields are never
        // initialized and would read null (exactly the failure mode class merging exists to eliminate).
        val mergedInstance = mergeInto != null && !method.isDeclaredStatic()

        when (annotation.desc) {
            INJECT_DESC -> parseInject(
                method, annotation, anchor, targetInternal, methodNames, methodDesc, mixinClass, modId,
                where, policy, mergeInto, handlers, rules, problems,
            )

            // @ModifyVariable was handled above (the synthetic-discriminator path) and never reaches here
            MODIFY_VARIABLE_DESC -> Unit

            REDIRECT_DESC -> if (mergedInstance) {
                parseMergedRedirect(
                    method, point, anchor, targetInternal, methodNames, methodDesc,
                    modId, mergeInto, handlers, rules, problems,
                )
            } else {
                parseRedirect(
                    method,
                    point,
                    anchor,
                    targetInternal,
                    methodNames,
                    methodDesc,
                    mixinClass,
                    modId,
                    where,
                    policy,
                    rules,
                    problems
                )
            }

            MODIFY_ARG_DESC -> if (mergedInstance) {
                parseMergedModify(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc,
                    modId, mergeInto, handlers, HandlerKind.MODIFY_ARG, rules, problems
                )
            } else {
                parseModifyArg(
                    method,
                    annotation,
                    anchor,
                    targetInternal,
                    methodNames,
                    methodDesc,
                    mixinClass,
                    modId,
                    where,
                    policy,
                    rules,
                    problems
                )
            }

            MODIFY_ARGS_DESC -> if (mergedInstance) {
                parseMergedModify(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc,
                    modId, mergeInto, handlers, HandlerKind.MODIFY_ARGS, rules, problems
                )
            } else {
                parseModifyArgs(
                    method,
                    anchor,
                    targetInternal,
                    methodNames,
                    methodDesc,
                    mixinClass,
                    modId,
                    where,
                    policy,
                    rules,
                    problems
                )
            }
        }
    }

    /**
     * Whether the handler is **declared** static -- and looked at **only at the declaration**.
     *
     * Why annotations on the method cannot decide this: `@JvmStatic` **simultaneously** generates an
     * instance method and a static bridge method; looking only at the annotation would misjudge an
     * "instance handler" as static. Mixin's `@Inject` handlers also tend to be written as instance
     * methods. `ACC_STATIC` is the only reliable signal (it is defined by the JVM spec and does not
     * vary with language/compiler).
     */
    private fun MethodNode.isDeclaredStatic(): Boolean = (access and Opcodes.ACC_STATIC) != 0

    // ---------- instance value-changing handlers among merge candidates ----------

    /**
     * An **instance** `@Redirect` among merge candidates: replaces the matched call **in place** with a
     * call to the handler.
     *
     * Unlike the static form ([parseRedirect]), the first parameter is the replaced call's receiver, not
     * `this` — the handler's `this` is the target instance, and the receiver is only obtainable by the
     * handler (it acts on that call's behalf), so it must be passed in explicitly. A static call has no
     * receiver, so an instance handler cannot take it: report a problem and tell the author to make it
     * static.
     */
    private fun parseMergedRedirect(
        method: MethodNode,
        point: InjectionPoint,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        modId: String,
        mergeInto: String,
        handlers: MutableSet<String>,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val where = "$mergeInto.${method.name}"
        val call = point.core as? InjectionPoint.Call
        if (call == null) {
            problems += "[$modId] $where —— @Redirect's @At must be a method call (INVOKE / INVOKE_ASSIGN)"
            return
        }
        // Cross-module attributes are not smart-converted ⇒ collect them into local variables, used in several spots below
        val callOwner = call.owner
        val callName = call.name
        val callDesc = call.desc
        if (callOwner == null || callName == null || callDesc == null) {
            problems += "[$modId] $where —— @Redirect's @At must write a full target (Lowner;name(args)ret):" +
                " the engine must determine the handler's descriptor at startup"
            return
        }
        val callRet = Type.getReturnType(callDesc).descriptor
        val handlerRet = Type.getReturnType(method.desc).descriptor
        // A static call has no receiver to take ⇒ the handler cannot be an instance method (this is exactly
        // the boundary between the instance form and the static form)
        if (call.isStaticCall()) {
            problems += "[$modId] $where —— the @Redirect target $callOwner.$callName is a static call:" +
                " an instance handler has no receiver to accept, please make the handler static (it will return to the static bridge path)"
            return
        }
        val want = "(${Type.getObjectType(callOwner).descriptor}${callDesc.substring(1)}"
        if (method.desc != want || handlerRet != callRet) {
            problems += "[$modId] $where —— the instance @Redirect handler's parameter list must equal the replaced call's receiver + its arguments," +
                " and the return type must equal the replaced call's return type: expected $want, actual ${method.desc}"
            return
        }
        handlers += "${method.name}${method.desc}"
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.HandlerCall(
                owner = mergeInto,
                method = method.name,
                desc = method.desc,
                kind = HandlerKind.REDIRECT,
            ),
            Policy(), emptyList(), "Redirect",
        )
    }

    /**
     * **Instance** `@ModifyArg` / `@ModifyArgs` / `@ModifyConstant` / `@ModifyVariable` among merge candidates.
     *
     * All four share one path, with a shape **identical to the external static form** (the first param is
     * the "value"; the receiver never enters the parameter list -- after merging the receiver is `this`,
     * provided by the injection point's `INVOKEVIRTUAL`). The only difference is where the handler lives
     * and which call instruction is used, expressed by [Payload.HandlerCall.kind]; the engine-side emission
     * logic is likewise shared (see the `instance` parameter of `PayloadEmitter.emitModify*`).
     */
    private fun parseMergedModify(
        method: MethodNode,
        annotation: AnnotationNode,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        modId: String,
        mergeInto: String,
        handlers: MutableSet<String>,
        kind: HandlerKind,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val where = "$mergeInto.${method.name}"
        val params = Type.getArgumentTypes(method.desc)
        val ret = Type.getReturnType(method.desc)
        // The parameter list holds "value + captures", with at least one (the value) -- the receiver is
        // supplied by `this` and is not written in
        if (params.isEmpty()) {
            problems += "[$modId] $where —— ${kind.annotationName}'s instance handler must have at least one value parameter:" +
                " shaped (values…)T, the receiver is provided by this and not written into the parameter list, currently ${method.desc}"
            return
        }
        // Anchor preconditions are one-by-one the same as the static form (the engine re-checks at emission;
        // here it is reported earlier and with the annotation name)
        when (kind) {
            HandlerKind.MODIFY_ARG, HandlerKind.MODIFY_ARGS ->
                if (anchor.core !is InjectionPoint.Call) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be a method call (INVOKE)"
                    return
                }

            HandlerKind.MODIFY_VAR ->
                if (anchor.core !is InjectionPoint.Store && anchor.core !is InjectionPoint.Load) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be STORE (after the write)" +
                        " or LOAD (before the read)"
                    return
                }

            HandlerKind.MODIFY_RETURN ->
                if (anchor.core !is InjectionPoint.Return && anchor.core !is InjectionPoint.FinalReturn) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be RETURN" +
                        " (every return) or TAIL (the last return)"
                    return
                }

            HandlerKind.MODIFY_EXPR_VALUE ->
                if (anchor.core !is InjectionPoint.Call && anchor.core !is InjectionPoint.FieldAccess &&
                    anchor.core !is InjectionPoint.Constant
                ) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be a value-producing" +
                        " expression (INVOKE / FIELD / CONSTANT)"
                    return
                }

            else -> Unit // ModifyConstant's anchor is decided by `constant = @Constant(...)`; no extra requirement
        }

        val handlerDesc = method.desc
        val handlerIndex = if (kind == HandlerKind.MODIFY_ARG) declaredInt(annotation, "index") ?: -1 else 0
        val slot = if (kind == HandlerKind.MODIFY_VAR) declaredInt(annotation, "index") else null
        handlers += "${method.name}${method.desc}"
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.HandlerCall(
                owner = mergeInto,
                method = method.name,
                desc = handlerDesc,
                captures = emptyList(),
                kind = kind,
                index = handlerIndex,
                slot = slot,
            ),
            Policy(), emptyList(), kind.annotationName.removePrefix("@"),
        )
        // Shape differences that can be judged statically (return type vs. target-call argument type, etc.)
        // are left to the engine -- it can see the target instruction and judge more precisely
        // (see PayloadEmitter.emitModify*).
        if (kind == HandlerKind.MODIFY_ARGS && ret != Type.VOID_TYPE) {
            problems += "[$modId] $where —— @ModifyArgs' handler must return void (the modified arguments are read back by the engine)," +
                " currently returns ${ret.className}"
        }
        if (kind == HandlerKind.MODIFY_RETURN || kind == HandlerKind.MODIFY_EXPR_VALUE) {
            // The value param's type must equal the return type (after modification it is put back on the stack) --
            // whether it is actually the target's type is only known at emission time once the anchor
            // instruction / target method is available (see ProducedValue)
            if (ret == Type.VOID_TYPE || params[0] != ret) {
                problems += "[$modId] $where —— ${kind.annotationName}'s handler must be shaped (T)T" +
                    " (T = the value's type, put back on the stack after the change), currently ${method.desc}"
            }
        }
        if (kind == HandlerKind.MODIFY_VAR) {
            val names = stringArray(annotationValue(annotation, "name"))
            if (names.isNotEmpty()) {
                problems += "[$modId] $where —— @ModifyVariable does not support locating by variable name (name = $names):" +
                    " most obfuscated jars have no LocalVariableTable. Use index (slot)" +
                    " or ordinal (the Nth local of the same type) instead"
            }
        }
    }

    /** Shape name → annotation-side spelling (for diagnostics). */
    private val HandlerKind.annotationName: String
        get() = when (this) {
            HandlerKind.INJECT -> "@Inject"
            HandlerKind.REDIRECT -> "@Redirect"
            HandlerKind.MODIFY_ARG -> "@ModifyArg"
            HandlerKind.MODIFY_ARGS -> "@ModifyArgs"
            HandlerKind.MODIFY_CONST -> "@ModifyConstant"
            HandlerKind.MODIFY_VAR -> "@ModifyVariable"
            HandlerKind.MODIFY_RETURN -> "@ModifyReturnValue"
            HandlerKind.MODIFY_EXPR_VALUE -> "@ModifyExpressionValue"
        }

    // ---------- @Inject ----------

    private fun parseInject(
        method: MethodNode,
        annotation: AnnotationNode,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        mixinClass: String,
        modId: String,
        where: String,
        policy: Policy,
        mergeInto: String?,
        handlers: MutableSet<String>,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val [tail, captureTypes] = handlerShape(method.desc)
        if (tail == Tail.NONE) {
            problems += "[$modId] $where —— @Inject handler's last parameter must be CallbackInfo or " +
                "CallbackInfoReturnable (consistent with Mixin: the handler must be able to take the callback handle), currently ${method.desc}"
            return
        }
        val returnType = Type.getReturnType(method.desc)
        if (returnType != Type.VOID_TYPE) {
            problems += "[$modId] $where —— @Inject handler must return void, currently returns ${returnType.className}"
            return
        }
        val locals = localCaptureOf(annotation)
        if (locals == LocalCapture.NO_CAPTURE && captureTypes.isNotEmpty()) {
            problems += "[$modId] $where —— the handler declares ${captureTypes.size} capture parameters, but locals is the default " +
                "NO_CAPTURE: please write locals = LocalCapture.CAPTURE_FAILHARD explicitly (or CAPTURE_FAILSOFT)." +
                " Consistent with Mixin —— local-variable capture cannot be verified statically, the author must state the intent explicitly"
            return
        }
        if (locals != LocalCapture.NO_CAPTURE && captureTypes.isEmpty()) {
            problems += "[$modId] $where —— locals = $locals but the handler declares no capture parameters (besides the callback handle)"
            return
        }
        val captures = capturesOf(captureTypes, locals, where, modId)

        val cancellable = (annotationValue(annotation, "cancellable") as? Boolean) ?: false
        val targetIsVoid = methodDesc == null || Type.getReturnType(methodDesc) == Type.VOID_TYPE
        val entryAnchor = anchor.core is InjectionPoint.Head || anchor.core is InjectionPoint.ConstructorHead

        // The short-circuit paths split into two branches: a void target uses CallbackInfo (CheckCall); a
        // non-void target uses CallbackInfoReturnable (CancellableReturn; setReturnValue both cancels and
        // supplies the value).
        var returnsValue = false
        if (cancellable) {
            when {
                !entryAnchor -> System.err.println(
                    "[Mixin] short-circuiting only supports method-entry anchors (HEAD / CTOR_HEAD), degraded to notify: " +
                        "${methodNames.first()} at=${AnchorResolver.describe(anchor)} (mod=$modId)"
                )

                tail == Tail.RETURNABLE && methodDesc == null -> System.err.println(
                    "[Mixin] value short-circuiting must fix the target descriptor (otherwise the return type is unknown), degraded to notify: " +
                        "${methodNames.first()} (mod=$modId)"
                )

                tail == Tail.RETURNABLE && targetIsVoid -> System.err.println(
                    "[Mixin] target ${methodNames.first()}$methodDesc returns void, the handler should use CallbackInfo instead," +
                        " degraded to notify (mod=$modId)"
                )

                tail == Tail.RETURNABLE -> returnsValue = true

                !targetIsVoid -> System.err.println(
                    "[Mixin] target ${methodNames.first()}$methodDesc returns non-void, the short-circuiting handler should use " +
                        "CallbackInfoReturnable to supply the return value, degraded to notify (mod=$modId)"
                )
            }
        } else if (tail == Tail.RETURNABLE) {
            System.err.println(
                "[Mixin] the handler takes CallbackInfoReturnable but did not declare cancellable = true:" +
                    " only registers as notify (setReturnValue will not take effect): ${methodNames.first()} (mod=$modId)"
            )
        }

        // ---------- **instance** handlers among merge candidates: call the merged-in method directly ----------
        // The handler rides into the target class via merge ⇒ the injection point writes a single
        // `INVOKEVIRTUAL <target class>.<handler>`; `this` is the target instance (`@Shadow` fields really
        // readable), so registry / bridge / reflection are all out of the picture.
        // Static handlers do not take this path (they need no `this`); they go through the
        // runtime-generated bridge instead.
        if (mergeInto != null && !method.isDeclaredStatic()) {
            handlers += "${method.name}${method.desc}"
            val variant = when {
                cancellable && entryAnchor && returnsValue -> HandlerVariant.RETURNABLE
                cancellable && entryAnchor && targetIsVoid -> HandlerVariant.CANCELLABLE
                else -> HandlerVariant.NOTIFY
            }
            val handleDesc = if (variant == HandlerVariant.RETURNABLE) CALLBACK_RETURNABLE_DESC
            else CALLBACK_INFO_DESC
            // Descriptor = capture params + trailing handle, all inside the parens; returns void (the value of a
            // value-bearing variant is taken **from the handle**)
            val desc = "(${captureTypes.joinToString("")}$handleDesc)V"
            val args = captures.map { DslValue.Local(type = it.type, ordinal = it.ordinal, strict = it.strict) }
            rules += Rule(
                targetInternal, methodNames, methodDesc, anchor,
                Payload.HandlerCall(mergeInto, method.name, desc, args, variant, HandlerKind.INJECT),
                policy, captures, "Inject",
            )
            return
        }

        val id = OMLMixinRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancellable = cancellable && entryAnchor && !returnsValue && targetIsVoid,
            captureTypes = captureTypes,
            handlerParamCount = captureTypes.size + 1,
            returnsValue = returnsValue,
        )
        val kind = OMLMixinRegistry.bridgeKind(id) ?: OMLMixinRegistry.BridgeKind.NOTIFY
        val bridgeName = OMLMixinRegistry.bridgeMethodName(id, kind)
        val bridgeDesc = OMLMixinRegistry.bridgeDesc(id, kind)
        val args = captures.map { DslValue.Local(type = it.type, ordinal = it.ordinal, strict = it.strict) } +
            DslValue.IntVal(id)
        val payload = when (kind) {
            OMLMixinRegistry.BridgeKind.CANCELLABLE ->
                Payload.CheckCall(OMLMixinRegistry.BRIDGE_CLASS, bridgeName, bridgeDesc, args)

            OMLMixinRegistry.BridgeKind.RETURNABLE ->
                Payload.CancellableReturn(OMLMixinRegistry.BRIDGE_CLASS, bridgeName, bridgeDesc, args)

            else -> Payload.StaticCall(OMLMixinRegistry.BRIDGE_CLASS, bridgeName, bridgeDesc, args)
        }
        rules += Rule(targetInternal, methodNames, methodDesc, anchor, payload, policy, captures, "Inject")
    }

    // ---------- @Redirect ----------

    private fun parseRedirect(
        method: MethodNode,
        point: InjectionPoint,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        mixinClass: String,
        modId: String,
        where: String,
        policy: Policy,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val call = point as? InjectionPoint.Call
        if (call == null) {
            problems += "[$modId] $where —— @Redirect's @At must be a method call (INVOKE / INVOKE_ASSIGN)"
            return
        }
        if (call.owner == null || call.name == null || call.desc == null) {
            problems += "[$modId] $where —— @Redirect's @At must write a full target (Lowner;name(args)ret):" +
                " the engine must determine the bridge method's descriptor at startup"
            return
        }
        val callArgs = Type.getArgumentTypes(call.desc).map { it.descriptor }
        val callRet = Type.getReturnType(call.desc).descriptor
        val handlerParams = Type.getArgumentTypes(method.desc).map { it.descriptor }
        val handlerRet = Type.getReturnType(method.desc).descriptor

        // Handler params = the arguments, or "receiver + arguments" (instance call; consistent with Mixin's
        // "first param is the receiver")
        val receiverOk = handlerParams == callArgs || handlerParams.drop(1) == callArgs
        if (!receiverOk || handlerRet != callRet) {
            problems += "[$modId] $where —— @Redirect handler's parameter list must equal the replaced call" +
                " (an instance call places the receiver as the first parameter): expected (${callArgs.joinToString("")})$callRet " +
                " or (receiver${callArgs.joinToString("")})$callRet, actual ${method.desc}"
            return
        }
        val id = OMLMixinRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancellable = false,
            captureTypes = handlerParams,
            handlerParamCount = handlerParams.size,
            returnType = handlerRet,
            mirror = true,
        )
        val bridgeName = OMLMixinRegistry.bridgeMethodName(id, OMLMixinRegistry.BridgeKind.MIRROR)
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.Redirect(OMLMixinRegistry.BRIDGE_CLASS, bridgeName), policy, emptyList(), "Redirect",
        )
    }

    // ---------- @ModifyArg / @ModifyArgs / @ModifyConstant ----------

    private fun parseModifyArg(
        method: MethodNode,
        annotation: AnnotationNode,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        mixinClass: String,
        modId: String,
        where: String,
        policy: Policy,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        if (anchor.core !is InjectionPoint.Call) {
            problems += "[$modId] $where —— @ModifyArg's @At must be a method call (INVOKE)"
            return
        }
        val params = Type.getArgumentTypes(method.desc)
        if (params.size != 1 || Type.getReturnType(method.desc) != params[0]) {
            problems += "[$modId] $where —— @ModifyArg handler must be shaped (T)T (T is the type of the argument being modified)," +
                " currently ${method.desc}"
            return
        }
        val index = declaredInt(annotation, "index") ?: -1
        val t = params[0].descriptor
        val [id, bridgeName, bridgeDesc] = registerModify(
            modId, mixinClass, method.name, methodNames.first(), t,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyArg(
                owner = OMLMixinRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                index = index,
                extras = listOf(DslValue.IntVal(id)),
            ),
            policy, emptyList(), "ModifyArg",
        )
    }

    private fun parseModifyArgs(
        method: MethodNode,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        mixinClass: String,
        modId: String,
        where: String,
        policy: Policy,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        if (anchor.core !is InjectionPoint.Call) {
            problems += "[$modId] $where —— @ModifyArgs' @At must be a method call (INVOKE)"
            return
        }
        val params = Type.getArgumentTypes(method.desc)
        if (params.size != 1 || params[0].descriptor != ARGS_DESC || Type.getReturnType(method.desc) != Type.VOID_TYPE) {
            problems += "[$modId] $where —— @ModifyArgs handler must be shaped " +
                "(Lorg/ohmyloader/api/mixin/Args;)V, currently ${method.desc}"
            return
        }
        val [id, bridgeName, bridgeDesc] = registerModify(
            modId, mixinClass, method.name, methodNames.first(), ARGS_DESC, returnVoid = true,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyArgs(
                owner = OMLMixinRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = listOf(DslValue.IntVal(id)),
            ),
            policy, emptyList(), "ModifyArgs",
        )
    }

    private fun parseModifyConstant(
        method: MethodNode,
        annotation: AnnotationNode,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        mixinClass: String,
        modId: String,
        where: String,
        policy: Policy,
        slices: Map<String, AnnotationNode>,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val params = Type.getArgumentTypes(method.desc)
        if (params.size != 1 || Type.getReturnType(method.desc) != params[0]) {
            problems += "[$modId] $where —— @ModifyConstant handler must be shaped (T)T (T is the constant type)," +
                " currently ${method.desc}"
            return
        }
        val constant = annotationValue(annotation, "constant") as? AnnotationNode
        val base = InjectionPoint.Constant(
            value = constant?.let { constantValue(it) },
            ordinal = constant?.let { declaredInt(it, "ordinal") },
            // A constant can only be changed once its value has been read -- the anchor is always `after`
            // (see Payload.ModifyConstant)
            after = true,
        )
        // `slice` also applies to ModifyConstant (finding the constant within the window)
        val anchor = withSlice(base, null, annotation, slices, where, "@ModifyConstant", problems)
        val t = params[0].descriptor
        val [id, bridgeName, bridgeDesc] = registerModify(
            modId, mixinClass, method.name, methodNames.first(), t,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyConstant(
                owner = OMLMixinRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = listOf(DslValue.IntVal(id)),
            ),
            policy, emptyList(), "ModifyConstant",
        )
    }

    // ---------- @ModifyVariable ----------

    /**
     * Combine `@At("STORE"/"LOAD")` with `@ModifyVariable`'s own discriminator into one complete anchor.
     *
     * `@At` says "which access, which occurrence", while `index`/`ordinal`/`argsOnly` say "which
     * variable" — missing either half makes locating impossible, so they are combined here into a fully
     * parseable anchor. The variable's type comes from the handler's return type: the `(T…)T` shape makes
     * `T` both the type to change into and the filter for which variable to look for.
     *
     * @return the combined anchor; `null` = the handler shape/anchor value is wrong (already recorded in [problems])
     */
    private fun refineVarAnchor(
        method: MethodNode,
        annotation: AnnotationNode,
        point: InjectionPoint,
        where: String,
        shortName: String,
        problems: MutableList<String>,
    ): InjectionPoint? {
        val params = Type.getArgumentTypes(method.desc)
        val ret = Type.getReturnType(method.desc)
        if (ret == Type.VOID_TYPE || params.isEmpty() || params[0] != ret) {
            problems += "$where —— $shortName's handler must be shaped (T[, captures…])T (T is the type of the local variable being modified," +
                " the return value is the new value), currently ${method.desc}"
            return null
        }

        val index = declaredInt(annotation, "index")
        val localOrdinal = declaredInt(annotation, "ordinal")
        val argsOnly = (annotationValue(annotation, "argsOnly") as? Boolean) ?: false

        val names = stringArray(annotationValue(annotation, "name"))
        if (names.isNotEmpty()) {
            problems += "$where —— $shortName does not support locating by variable name (name = $names):" +
                " most obfuscated jars have no LocalVariableTable, and the variable names in the table are themselves obfuscated." +
                " Use index (slot) or ordinal (the Nth local of the same type) instead"
        }

        val type = ret.descriptor
        return when (point) {
            is InjectionPoint.Store -> InjectionPoint.Store(index, type, localOrdinal, point.ordinal, argsOnly)
            is InjectionPoint.Load -> InjectionPoint.Load(index, type, localOrdinal, point.ordinal, argsOnly)

            else -> {
                problems += "$where —— $shortName's @At must be STORE (after the write) or LOAD (before the read)," +
                    " actual is ${AnchorResolver.describe(point)}"
                null
            }
        }
    }

    /**
     * `@ModifyVariable`: handler `(T[, captures…])T`, bridge shaped like `(T, captures…, id)T`.
     *
     * The shape and anchor were already validated in [refineVarAnchor]; only capture assembly and rule
     * registration happen here.
     */
    private fun parseModifyVariable(
        method: MethodNode,
        annotation: AnnotationNode,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        mixinClass: String,
        modId: String,
        where: String,
        policy: Policy,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val params = Type.getArgumentTypes(method.desc)
        val valueType = Type.getReturnType(method.desc).descriptor
        val captureTypes = params.drop(1).map { it.descriptor }
        val locals = localCaptureOf(annotation)
        // Same rule as @Inject: captures must be declared explicitly; if inconsistent, **do not register this
        // rule** (rather than injecting as-is). The reason for not injecting as-is: an unresolvable capture
        // silently changes one fewer variable, which is far harder to debug than a startup-time error.
        if (captureTypes.isNotEmpty() && locals == LocalCapture.NO_CAPTURE) {
            problems += "$where —— @ModifyVariable's handler declares ${captureTypes.size} extra parameters (captures)," +
                " but locals is the default NO_CAPTURE: please write locals = CAPTURE_FAILSOFT / CAPTURE_FAILHARD explicitly"
            return
        }
        if (captureTypes.isEmpty() && locals != LocalCapture.NO_CAPTURE) {
            problems += "$where —— @ModifyVariable's locals = $locals but the handler declares no capture parameters (besides the value parameter)"
            return
        }
        val captures = capturesOf(captureTypes, locals, where, modId)

        val id = OMLMixinRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancellable = false,
            // The bridge's param list is exactly "the engine's push order": value first, then captures, then id
            captureTypes = listOf(valueType) + captureTypes,
            handlerParamCount = params.size,
            returnType = valueType,
        )
        val kind = OMLMixinRegistry.BridgeKind.MODIFY
        val bridgeName = OMLMixinRegistry.bridgeMethodName(id, kind)
        val bridgeDesc = OMLMixinRegistry.bridgeDesc(id, kind)

        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyVariable(
                owner = OMLMixinRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = captures.map { DslValue.Local(type = it.type, ordinal = it.ordinal, strict = it.strict) } +
                    DslValue.IntVal(id),
            ),
            policy, captures, "ModifyVariable",
        )
    }

    /**
     * Twist an expression anchor onto "**after**".
     *
     * `@ModifyExpressionValue` fetches the value **produced** by the expression, and the value is only on
     * the stack after the instruction has run; the annotation has no before/after layer, so the front-end
     * decides `after` for the author.
     */
    private fun forceAfter(point: InjectionPoint): InjectionPoint = when (point) {
        is InjectionPoint.Call -> if (point.after) point else point.copy(after = true)
        is InjectionPoint.FieldAccess -> if (point.after) point else point.copy(after = true)
        is InjectionPoint.Constant -> if (point.after) point else point.copy(after = true)
        else -> point
    }

    /**
     * `@ModifyReturnValue` outside merge candidates: the handler stays in the mixin and is called through
     * a bridge.
     *
     * It still changes the same value (the return value on the stack top before `RETURN`); only the
     * handler's location differs -- hence the payload is the "value already on the stack" static form
     * [Payload.TransformReturn].
     */
    private fun parseModifyReturnValue(
        method: MethodNode,
        annotation: AnnotationNode,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        mixinClass: String,
        modId: String,
        where: String,
        policy: Policy,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val params = Type.getArgumentTypes(method.desc)
        val ret = Type.getReturnType(method.desc)
        if (ret == Type.VOID_TYPE || params.size != 1 || params[0] != ret) {
            problems += "[$modId] $where —— @ModifyReturnValue handler must be shaped (R)R (R is the return type," +
                " put back on the stack after the change), currently ${method.desc}"
            return
        }
        val [id, bridgeName, bridgeDesc] = registerModify(
            modId, mixinClass, method.name, methodNames.first(), params[0].descriptor,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.TransformReturn(
                owner = OMLMixinRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = listOf(DslValue.IntVal(id)),
            ),
            policy, emptyList(), "ModifyReturnValue",
        )
    }

    /**
     * `@ModifyExpressionValue` outside merge candidates: likewise goes through a bridge; the value's type
     * is declared by the handler's parameter.
     *
     * Whether the type matches what the anchor produces is checked at **emission** time (only there is it
     * known what that instruction produces), see `ProducedValue`.
     */
    private fun parseModifyExpressionValue(
        method: MethodNode,
        annotation: AnnotationNode,
        anchor: InjectionPoint,
        targetInternal: String,
        methodNames: List<String>,
        methodDesc: String?,
        mixinClass: String,
        modId: String,
        where: String,
        policy: Policy,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val params = Type.getArgumentTypes(method.desc)
        val ret = Type.getReturnType(method.desc)
        if (ret == Type.VOID_TYPE || params.size != 1 || params[0] != ret) {
            problems += "[$modId] $where —— @ModifyExpressionValue handler must be shaped (T)T (T is the type produced by" +
                " the expression, put back on the stack after the change), currently ${method.desc}"
            return
        }
        val [id, bridgeName, bridgeDesc] = registerModify(
            modId, mixinClass, method.name, methodNames.first(), params[0].descriptor,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyExpressionValue(
                owner = OMLMixinRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = listOf(DslValue.IntVal(id)),
            ),
            policy, emptyList(), "ModifyExpressionValue",
        )
    }

    /** Register a "value-changing" handler (bridge `(T, I)T`) and return its id/method name/descriptor. */
    private fun registerModify(
        modId: String,
        mixinClass: String,
        handlerMethod: String,
        targetMethod: String,
        type: String,
        returnVoid: Boolean = false,
    ): Triple<Int, String, String> {
        val id = OMLMixinRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = handlerMethod,
            targetMethod = targetMethod,
            cancellable = false,
            captureTypes = listOf(type),
            handlerParamCount = 1,
            returnType = if (returnVoid) "V" else type,
        )
        val kind = OMLMixinRegistry.BridgeKind.MODIFY
        return Triple(id, OMLMixinRegistry.bridgeMethodName(id, kind), OMLMixinRegistry.bridgeDesc(id, kind))
    }

    // ---------- handler parameter list ----------

    /** Handler parameter list: the kind of trailing handle + the capture types before the handle. */
    private fun handlerShape(handlerDesc: String): Pair<Tail, List<String>> {
        val args = Type.getArgumentTypes(handlerDesc)
        if (args.isEmpty()) return Tail.NONE to emptyList()
        return when (args.last().descriptor) {
            CALLBACK_INFO_DESC -> Tail.CALLBACK_INFO to args.dropLast(1).map { it.descriptor }
            CALLBACK_RETURNABLE_DESC -> Tail.RETURNABLE to args.dropLast(1).map { it.descriptor }
            else -> Tail.NONE to emptyList()
        }
    }

    /**
     * Bind the capture params, per the `locals` mode, to "the Nth local variable of type T at the
     * injection point".
     *
     * `ordinal` = **the occurrence index of that type's param within the handler** (0-based) -- isomorphic
     * to Mixin's greedy matching: it likewise walks params in declaration order, matching each to the
     * "next local of matching type". When the engine resolves candidates it **prefers the exact descriptor**
     * and skips `this` (to get the target instance one must use another approach).
     */
    private fun capturesOf(
        captureTypes: List<String>,
        locals: LocalCapture,
        where: String,
        modId: String,
    ): List<Capture> {
        val strict = locals == LocalCapture.CAPTURE_FAILHARD
        val seen = mutableMapOf<String, Int>()
        val captures = captureTypes.mapIndexed { slot, type ->
            val ordinal = seen.getOrPut(type) { 0 }.also { seen[type] = it + 1 }
            Capture(slot, type, ordinal, strict)
        }
        if (locals == LocalCapture.PRINT) {
            println(
                "[Mixin] $where declares locals = PRINT: should be able to capture at the injection point " +
                    captures.joinToString(", ") { "${it.type}#${it.ordinal}" } + " (mod=$modId)"
            )
        }
        // With NO_CAPTURE, captureTypes is necessarily empty ("capture params but NO_CAPTURE" was already rejected above)
        return captures
    }

    // ---------- match-count policy ----------

    private fun policyProblem(where: String, shortName: String, policy: Policy): String? {
        val negative = listOfNotNull(
            policy.require?.takeIf { it < 0 }?.let { "require=$it" },
            policy.allow?.takeIf { it < 0 }?.let { "allow=$it" },
            policy.expect?.takeIf { it < 0 }?.let { "expect=$it" },
        )
        return negative.takeIf { it.isNotEmpty() }?.let {
            "$where —— $shortName's hit count must be non-negative or left empty (-1), actual: ${it.joinToString(", ")}"
        }
    }

    // ---------- @Slice ----------

    /**
     * Apply a `@Slice` to the anchor (search window).
     *
     * Precedence: `@At(slice = "id")` first; otherwise the annotation-level `slice = @Slice(...)`.
     * The boundary semantics (from takes the last match, to takes the first match) match the engine's
     * `within` -- which is exactly the default behavior of `INVOKE:LAST` / `INVOKE:FIRST` in Mixin docs.
     */
    private fun withSlice(
        point: InjectionPoint,
        atNode: AnnotationNode?,
        annotation: AnnotationNode,
        slices: Map<String, AnnotationNode>,
        where: String,
        shortName: String,
        problems: MutableList<String>,
    ): InjectionPoint {
        val sliceRef = atNode?.let { (annotationValue(it, "slice") as? String).orEmpty() }.orEmpty()
        val inline = annotationValue(annotation, "slice") as? AnnotationNode
        val sliceNode = when {
            sliceRef.isNotEmpty() -> slices[sliceRef]
            inline != null -> inline
            else -> null
        }
        if (sliceNode == null) {
            if (sliceRef.isNotEmpty()) {
                problems += "$where —— $shortName's @At(slice = \"$sliceRef\") found no matching @Slice (id mismatch)"
            }
            return point
        }
        val from = (annotationValue(sliceNode, "from") as? AnnotationNode)?.let { AtParser.parse(it) }
        val to = (annotationValue(sliceNode, "to") as? AnnotationNode)?.let { AtParser.parse(it) }
        if (from == null && to == null) return point
        return InjectionPoint.Sliced(point, from, to)
    }

    // ---------- annotation retrieval ----------

    /** The value of [name] in the annotation; returns null when absent. */
    private fun annotationValue(annotation: AnnotationNode, name: String): Any? =
        AtParser.annotationValue(annotation, name)

    /**
     * An int explicitly written in the annotation.
     *
     * `-1` is the annotation default for these fields (= not declared) ⇒ returns null;
     * **all other negative numbers are returned as-is** -- they are wrong values that must be surfaced by
     * [policyProblem] and friends, not silently treated here as "not written".
     */
    private fun declaredInt(annotation: AnnotationNode?, name: String): Int? {
        if (annotation == null) return null
        val raw = annotationValue(annotation, name) as? Int ?: return null
        return raw.takeIf { it != -1 }
    }

    /**
     * The string array in an annotation.
     *
     * ASM reads it from the class file as a `List`; callers hand-rolling an `AnnotationNode` may pass an
     * `Array` -- both are accepted (missing one branch would silently yield an empty list, and "silently
     * ending up empty" is exactly this project's most feared failure mode).
     */
    private fun stringArray(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is List<*> -> value.filterIsInstance<String>()
        is Array<*> -> value.filterIsInstance<String>()
        else -> emptyList()
    }

    /** `locals = LocalCapture.X` → the enum (in ASM an enum value is a `[desc, name]` pair). */
    private fun localCaptureOf(annotation: AnnotationNode): LocalCapture {
        val name = AtParser.enumName(annotationValue(annotation, "locals"))
        return LocalCapture.entries.firstOrNull { it.name == name } ?: LocalCapture.NO_CAPTURE
    }

    /** `@Constant(...)` values: only **explicitly written** fields count (annotation defaults are not written into the bytecode). */
    private fun constantValue(node: AnnotationNode): Any? {
        val values = node.values ?: return null
        var i = 0
        while (i + 1 < values.size) {
            when (values[i]) {
                "nullValue" -> if (values[i + 1] == true) return null
                "intValue", "longValue", "floatValue", "doubleValue", "stringValue" -> return values[i + 1]
            }
            i += 2
        }
        return null
    }
}
