package org.ohmyloader.core.hook

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AnnotationNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.inject.*
import org.ohmyloader.api.hook.LocalCapture
import org.ohmyloader.core.hook.HookScanner.parseRedirect
import org.ohmyloader.core.hook.HookScanner.policyProblem
import org.ohmyloader.core.hook.HookScanner.refineVarAnchor
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
object HookScanner {

    /**
     * A fully-compiled injection rule: both the anchor and payload are already engine objects
     * (the annotation has been fully interpreted).
     *
     * The front-end **directly constructs engine objects** (instead of going back through `atHead { … }`
     * DSL sugar) because `@Slice`/`shift`/`ordinal` are already ready-made objects in the front-end;
     * attaching them directly interprets the semantics only once.
     */
    internal data class Rule(
        val targetInternal: String,
        val methodNames: List<String>,
        val methodDesc: String?,
        val anchor: InjectionPoint,
        val payload: Payload,
        val policy: Policy,
        val captures: List<Capture>,
        val kind: String,
        /** Non-null when the rule targets a synthetic lambda method instead of the named method itself. */
        val lambda: LambdaSelector? = null,
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
        extraMerges: List<ClassMerger.HookClass> = emptyList(),
        /** Problems in the discovery/retrieval phase of those rule sets, folded into the same self-check report. */
        extraProblems: List<String> = emptyList(),
    ): IClassTransformer? {
        val rules = mutableListOf<Rule>()
        val problems = mutableListOf<String>()
        val merges = mutableListOf<ClassMerger.HookClass>()
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
        if (rules.isNotEmpty()) HookRegistry.ensureBridgeGenerated()

        val spec = injection {
            for (rule in rules) {
                classTarget(rule.targetInternal) {
                    val configure: MethodRuleBuilder.() -> Unit = {
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
                    val lambda = rule.lambda
                    if (lambda != null) {
                        lambdaMethod(
                            *rule.methodNames.distinct().toTypedArray(),
                            desc = rule.methodDesc,
                            interfaceInternal = lambda.interfaceInternal,
                            ordinal = lambda.ordinal,
                            block = configure,
                        )
                    } else {
                        method(*rule.methodNames.distinct().toTypedArray(), desc = rule.methodDesc, block = configure)
                    }
                }
            }
        }.merge(extraRules)
        if (rules.isEmpty() && problems.isNotEmpty()) {
            OmlLog.error(
                "Mixin",
                "the front-end produced no injection rules, but there are ${problems.size} problems:\n" +
                    problems.joinToString("\n") { "  $it" },
            )
        }
        return HookTransformer(
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
        val merges: List<ClassMerger.HookClass> = emptyList(),
    )

    // ---------- annotation descriptors ----------

    private const val HOOKED_DESC = "Lorg/ohmyloader/api/hook/Hooked;"
    private const val MERGED_DESC = "Lorg/ohmyloader/api/hook/Merged;"
    private const val IMPLEMENTS_DESC = "Lorg/ohmyloader/api/hook/Implements;"
    private const val INJECT_DESC = "Lorg/ohmyloader/api/hook/Inject;"
    private const val REDIRECT_DESC = "Lorg/ohmyloader/api/hook/Redirect;"
    private const val MODIFY_ARG_DESC = "Lorg/ohmyloader/api/hook/ModifyArg;"
    private const val MODIFY_ARGS_DESC = "Lorg/ohmyloader/api/hook/ModifyArgs;"
    private const val MODIFY_CONSTANT_DESC = "Lorg/ohmyloader/api/hook/ModifyConstant;"
    private const val MODIFY_VARIABLE_DESC = "Lorg/ohmyloader/api/hook/ModifyVariable;"
    private const val MODIFY_RETURN_VALUE_DESC = "Lorg/ohmyloader/api/hook/ModifyReturnValue;"
    private const val MODIFY_EXPRESSION_VALUE_DESC = "Lorg/ohmyloader/api/hook/ModifyExpressionValue;"
    private const val MODIFY_RECEIVER_DESC = "Lorg/ohmyloader/api/hook/ModifyReceiver;"
    private const val WRAP_WITH_CONDITION_DESC = "Lorg/ohmyloader/api/hook/WrapWithCondition;"
    private const val WRAP_BRANCH_DESC = "Lorg/ohmyloader/api/hook/WrapBranch;"
    private const val WRAP_OPERATION_DESC = "Lorg/ohmyloader/api/hook/WrapOperation;"
    private const val WRAP_METHOD_DESC = "Lorg/ohmyloader/api/hook/WrapMethod;"
    private const val LOCAL_DESC = "Lorg/ohmyloader/api/hook/Local;"
    private const val SHARE_DESC = "Lorg/ohmyloader/api/hook/Share;"
    private const val CANCELABLE_DESC = "Lorg/ohmyloader/api/hook/Cancellable;"
    private const val COERCE_DESC = "Lorg/ohmyloader/api/hook/Coerce;"
    private val OBJECT_SORTS = setOf(Type.OBJECT, Type.ARRAY)
    private const val HOOK_CONTEXT_DESC = "Lorg/ohmyloader/api/hook/HookContext;"
    private const val HOOK_VALUE_DESC = "Lorg/ohmyloader/api/hook/HookValue;"
    private const val EXPRESSION_DESC = "Lorg/ohmyloader/api/hook/Expression;"
    private const val EXPRESSIONS_DESC = "Lorg/ohmyloader/api/hook/Expressions;"
    private const val DEFINITION_DESC = "Lorg/ohmyloader/api/hook/Definition;"
    private const val DEFINITIONS_DESC = "Lorg/ohmyloader/api/hook/Definitions;"
    private const val LAMBDA_DESC = "Lorg/ohmyloader/api/hook/Lambda;"
    private const val BORROWED_DESC = "Lorg/ohmyloader/api/hook/Borrowed;"
    private const val EXTRA_DESC = "Lorg/ohmyloader/api/hook/Extra;"
    private const val REPLACE_DESC = "Lorg/ohmyloader/api/hook/Replace;"

    /** The merge-family members: only a [Model.MERGE] carrier may declare them. */
    private val MERGE_MEMBER_ANNOTATIONS = setOf(BORROWED_DESC, EXTRA_DESC, REPLACE_DESC)

    /** accessor/invoker: only valid on the abstract methods of an **interface** carrier (synthesis path). */
    private val MERGE_ACCESSOR_ANNOTATIONS = setOf(
        "Lorg/ohmyloader/api/hook/FieldAccess;",
        "Lorg/ohmyloader/api/hook/MethodAccess;",
    )

    /** Injection annotations this front-end recognizes → their names (for diagnostics). Exposed for the API-consistency test. */
    internal val INJECTION_ANNOTATIONS = mapOf(
        INJECT_DESC to "@Inject",
        REDIRECT_DESC to "@Redirect",
        MODIFY_ARG_DESC to "@ModifyArg",
        MODIFY_ARGS_DESC to "@ModifyArgs",
        MODIFY_CONSTANT_DESC to "@ModifyConstant",
        MODIFY_VARIABLE_DESC to "@ModifyVariable",
        MODIFY_RETURN_VALUE_DESC to "@ModifyReturnValue",
        MODIFY_EXPRESSION_VALUE_DESC to "@ModifyExpressionValue",
        MODIFY_RECEIVER_DESC to "@ModifyReceiver",
        WRAP_WITH_CONDITION_DESC to "@WrapWithCondition",
        WRAP_BRANCH_DESC to "@WrapBranch",
        WRAP_OPERATION_DESC to "@WrapOperation",
        WRAP_METHOD_DESC to "@WrapMethod",
    )

    /** Tail kind of the handler parameter list (`@Inject` uses the first two; the other annotations have no callback handle). */
    private enum class Tail { CALLBACK_INFO, RETURNABLE, NONE }

    /** The dispatch model, declared by the carrier annotation ( / ) — never inferred. */
    private enum class Model { HOOK, MERGE }

    // ---------- scanning ----------

    private fun scanMod(mod: ModContainer): ScanResult {
        if (!mod.file.isFile) return ScanResult(emptyList(), emptyList())
        val rules = mutableListOf<Rule>()
        val problems = mutableListOf<String>()
        val merges = mutableListOf<ClassMerger.HookClass>()
        JarFile(mod.file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".class")) continue
                // MR-JAR versioned copies are not mixin declarations: scanning them would register the
                // same mixin class twice (duplicate @Overwrite ledger entries / double member synthesis).
                if (entry.name.startsWith("META-INF/")) continue
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

    /** Parse the `@Hooked` / `@Merged` declaration on a single class and the injection annotations on its methods; returns an empty table for non-carrier classes. */
    internal fun parseMixinClass(node: ClassNode, modId: String): ScanResult {
        val hooked = node.visibleAnnotations?.find { it.desc == HOOKED_DESC }
        val merged = node.visibleAnnotations?.find { it.desc == MERGED_DESC }
        if (hooked != null && merged != null) {
            return ScanResult(
                emptyList(),
                listOf("[$modId] ${node.name} declares both @Hooked and @Merged — the dispatch models are mutually exclusive; pick one"),
            )
        }
        val target = (hooked ?: merged)
            ?.let { annotationValue(it, "target") as? String }
            ?: return ScanResult(emptyList(), emptyList())
        val targetInternal = target.replace('.', '/')
        val mixinClass = node.name.replace('/', '.')

        val rules = mutableListOf<Rule>()
        val problems = mutableListOf<String>()
        // Collect `@Slice` first (`@At(slice = "id")` looks up by id; an empty id is the default slice)
        val slices = node.visibleAnnotations.orEmpty()
            .filter { it.desc.endsWith("/Slice;") }.associateBy { (annotationValue(it, "id") as? String).orEmpty() }

        // The dispatch model is **declared by the carrier annotation**, never inferred from the class's
        // members: `@Merged` rides instance handlers into the target class via member merge (the
        // injection point becomes a direct INVOKEVIRTUAL call, `this` = the target instance — see
        // parseInject), while `@Hooked` bridge-dispatches everything and never pretends `this` is the
        // target. Mixing the models is a startup-stage problem, so the two shapes cannot drift apart.
        val model = if (merged != null) Model.MERGE else Model.HOOK
        val mergeInto = if (model == Model.MERGE) targetInternal else null

        // @Hooked is a pure injection carrier: merge-family members belong to @Merged.
        if (model == Model.HOOK) {
            node.fields.forEach { field ->
                if (field.visibleAnnotations.orEmpty().any { it.desc in MERGE_MEMBER_ANNOTATIONS }) {
                    problems += "[$modId] ${node.name}.${field.name} —— @Borrowed/@Extra/@Replace merge into the target class" +
                        " and require @Merged; a @Hooked carrier declares none"
                }
            }
        }

        val handlers = mutableSetOf<String>()

        for (method in node.methods) {
            val annotations = method.visibleAnnotations.orEmpty().filter { it.desc in INJECTION_ANNOTATIONS }
            if (annotations.isEmpty()) continue
            if (method.instructions.size() == 0) {
                problems += "[$modId] $mixinClass.${method.name} —— the method carrying the injection annotation must have a method body" +
                    " (the handler cannot be abstract/native)"
                continue
            }
            // `@Lambda` retargets every rule of this handler at a synthetic lambda method; its `method`
            // names the enclosing method instead of the target
            val lambdaNode = method.visibleAnnotations.orEmpty().firstOrNull { it.desc == LAMBDA_DESC }
            val lambda = lambdaNode?.let { parseLambda(it, method, mixinClass, modId, problems) }
            if (lambdaNode != null && lambda == null) continue
            if (lambda != null && model == Model.MERGE && !method.isDeclaredStatic()) {
                problems += "[$modId] $mixinClass.${method.name} —— an @Lambda handler on a @Merged carrier " +
                    "must be static: the lambda method is static and has no target instance for `this`"
                continue
            }
            val start = rules.size
            for (annotation in annotations) {
                parseInjection(
                    method, annotation, targetInternal, mixinClass, modId, slices,
                    mergeInto, handlers, rules, problems,
                )
            }
            if (lambda != null) {
                for (i in start until rules.size) rules[i] = rules[i].copy(lambda = lambda)
            }
        }

        // The merge phase runs for @Merged carriers (member merge) and for @Hooked **interface**
        // carriers that declare static @FieldAccess/@MethodAccess members: the synthesis is a member
        // landing in the target class, which is merge machinery even though the carrier is @Hooked.
        val merge = if (model == Model.MERGE || (node.access and Opcodes.ACC_INTERFACE) != 0) {
            mergeCandidates(node, targetInternal, mixinClass, modId, handlers, problems)
        } else {
            null
        }
        if (merge == null && node.visibleAnnotations.orEmpty().any { it.desc == IMPLEMENTS_DESC }) {
            problems += "[$modId] $mixinClass —— @Implements needs a merge carrier (@Merged) or an interface carrier"
        }
        return ScanResult(rules, problems, listOfNotNull(merge))
    }

    private fun mergeCandidates(
        node: ClassNode,
        targetInternal: String,
        mixinClass: String,
        modId: String,
        handlerMethods: Set<String>,
        problems: MutableList<String>,
    ): ClassMerger.HookClass? {
        // Interface mixin: `@Accessor`/`@Invoker` are declared on the interface's abstract methods, and the
        // engine synthesizes method bodies in the target class from the signatures. An interface should
        // contain nothing else
        if (node.access and Opcodes.ACC_INTERFACE != 0) {
            val implemented = implementedInterfaces(node)
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
            if (!hasAccessor && implemented.isEmpty()) return null
            if (node.fields.isNotEmpty()) {
                problems += "[$modId] $mixinClass —— an interface mixin cannot declare fields (accessor/invoker are only signature declarations)"
            }
            return ClassMerger.HookClass(
                modId, mixinClass, targetInternal, node, implementedInterfaces = implemented,
            )
        }

        // The model is declared by the carrier annotation — mergeCandidates is only called for
        // @Merged classes (and @Hooked interfaces, for the accessor synthesis), so no member-based
        // inference happens here. A @Merged class without merge-family members is still a carrier:
        // its instance handlers ride the merge.

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
        return ClassMerger.HookClass(
            modId, mixinClass, targetInternal, node, handlerMethods,
            implementedInterfaces = implementedInterfaces(node),
        )
    }

    /** `@Implements` → the interfaces the target should implement (annotation values are ASM `Type`s). */
    private fun implementedInterfaces(node: ClassNode): List<String> {
        val annotation = node.visibleAnnotations.orEmpty().firstOrNull { it.desc == IMPLEMENTS_DESC }
            ?: return emptyList()
        val values = annotationValue(annotation, "value") as? List<*> ?: return emptyList()
        return values.filterIsInstance<Type>().map { it.internalName }
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

        // `@WrapMethod` has no at: it applies to the whole selected method.
        if (annotation.desc == WRAP_METHOD_DESC) {
            parseWrapMethod(
                method, targetInternal, methodNames, methodDesc, mixinClass, modId,
                where, policy, mergeInto, handlers, rules, problems,
            )
            return
        }

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
                val mergedAnchor = withSlice(base, null, annotation, slices, method, modId, where, shortName, problems)
                parseMergedModify(
                    method, annotation, mergedAnchor, targetInternal, methodNames, methodDesc, mixinClass,
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
                problems,
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
                parseAnchor(atNode, method, where, modId, shortName, problems)
            }
            if (parsed == null) {
                if (atNode != null && isExpressionAt(atNode)) return
                problems += if (atNode == null) {
                    "[$modId] $where —— $shortName is missing at = @At(…)"
                } else {
                    "[$modId] $where —— $shortName's ${unrecognizedAt(atNode)}"
                }
                return
            }
            val refined = if (isReturnValue) parsed else forceAfter(parsed)
            val anchor = withSlice(refined, atNode, annotation, slices, method, modId, where, shortName, problems)
            val kind = if (isReturnValue) HandlerKind.MODIFY_RETURN else HandlerKind.MODIFY_EXPR_VALUE
            // Here `mergeInto` is checked directly instead of reusing the `mergedInstance` below -- this block
            // has to come **before** the "must have at" check (which `@ModifyReturnValue` can omit), while
            // `mergedInstance` is only computed after that point.
            if (mergeInto != null && !method.isDeclaredStatic()) {
                parseMergedModify(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc, mixinClass,
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
        val point = parseAnchor(atNode, method, where, modId, shortName, problems)
        if (point == null) {
            if (!isExpressionAt(atNode)) problems += "[$modId] $where —— $shortName's ${unrecognizedAt(atNode)}"
            return
        }

        // @ModifyVariable's discriminator (index/ordinal/argsOnly) lives in its own fields; @At only
        // specifies "which access + which occurrence" -- only the combination yields a complete anchor,
        // so it takes its own path.
        if (annotation.desc == MODIFY_VARIABLE_DESC) {
            val refined = refineVarAnchor(method, annotation, point, where, shortName, problems) ?: return
            val varAnchor = withSlice(refined, atNode, annotation, slices, method, modId, where, shortName, problems)
            val mergedInstance = mergeInto != null && !method.isDeclaredStatic()
            if (mergedInstance) {
                parseMergedModify(
                    method, annotation, varAnchor, targetInternal, methodNames, methodDesc, mixinClass,
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

        val anchor = withSlice(point, atNode, annotation, slices, method, modId, where, shortName, problems)

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
                    method, point, anchor, targetInternal, methodNames, methodDesc, mixinClass,
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
                    problems,
                )
            }

            MODIFY_ARG_DESC -> if (mergedInstance) {
                parseMergedModify(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc, mixinClass,
                    modId, mergeInto, handlers, HandlerKind.MODIFY_ARG, rules, problems,
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
                    problems,
                )
            }

            MODIFY_ARGS_DESC -> if (mergedInstance) {
                parseMergedModify(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc, mixinClass,
                    modId, mergeInto, handlers, HandlerKind.MODIFY_ARGS, rules, problems,
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
                    problems,
                )
            }

            MODIFY_RECEIVER_DESC -> if (mergedInstance) {
                parseMergedModify(
                    method, annotation, anchor, targetInternal, methodNames, methodDesc, mixinClass,
                    modId, mergeInto, handlers, HandlerKind.MODIFY_RECEIVER, rules, problems,
                )
            } else {
                parseModifyReceiver(
                    method, anchor, targetInternal, methodNames, methodDesc, mixinClass, modId,
                    where, policy, rules, problems,
                )
            }

            WRAP_WITH_CONDITION_DESC -> parseWrapWithCondition(
                method, anchor, targetInternal, methodNames, methodDesc, mixinClass, modId,
                where, policy, mergeInto, handlers, rules, problems,
            )

            WRAP_BRANCH_DESC -> parseWrapBranch(
                method, anchor, targetInternal, methodNames, methodDesc, mixinClass, modId,
                where, policy, mergeInto, handlers, rules, problems,
            )

            WRAP_OPERATION_DESC -> parseWrapOperation(
                method, anchor, targetInternal, methodNames, methodDesc, mixinClass, modId,
                where, policy, mergeInto, handlers, rules, problems,
            )
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
        mixinClass: String,
        modId: String,
        mergeInto: String,
        handlers: MutableSet<String>,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val where = "$mergeInto.${method.name}"
        val call = point.core as? InjectionPoint.Call
        val expression = point.core is InjectionPoint.Expression
        if (!expression && call == null) {
            problems += if (point.core is InjectionPoint.NewInstance) {
                "[$modId] $where —— an instance handler cannot redirect a NEW construction: " +
                    "make the handler static (the static bridge path handles @At(\"NEW\"))"
            } else {
                "[$modId] $where —— @Redirect's @At must be a method call (INVOKE / INVOKE_ASSIGN) " +
                    "or an @Expression"
            }
            return
        }
        // Cross-module attributes are not smart-converted ⇒ collect them into local variables, used in several spots below
        val callOwner = call?.owner
        val callName = call?.name
        val callDesc = call?.desc
        if (call != null && (callOwner == null || callName == null || callDesc == null)) {
            problems += "[$modId] $where —— @Redirect's @At must write a full target (Lowner;name(args)ret):" +
                " the engine must determine the handler's descriptor at startup"
            return
        }
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        if (call != null) {
            val callRet = Type.getReturnType(callDesc).descriptor
            val handlerRet = Type.getReturnType(method.desc).descriptor
            // A static call has no receiver to take ⇒ the handler cannot be an instance method (this is exactly
            // the boundary between the instance form and the static form)
            if (call.isStaticCall()) {
                problems += "[$modId] $where —— the @Redirect target $callOwner.$callName is a static call:" +
                    " an instance handler has no receiver to accept, please make the handler static (it will return to the static bridge path)"
                return
            }
            val want = listOf(Type.getObjectType(callOwner).descriptor) +
                Type.getArgumentTypes(callDesc).map { it.descriptor }
            if (split.normal.map { it.descriptor } != want || handlerRet != callRet) {
                problems += "[$modId] $where —— the instance @Redirect handler's non-sugar parameter list must " +
                    "equal the replaced call's receiver + its arguments, and the return type must equal the " +
                    "replaced call's return type: expected ${want.joinToString("")}, actual ${method.desc}"
                return
            }
        }
        // With an @Expression the captured call is only known at injection time; the engine re-checks the
        // handler shape and rejects static calls there.
        handlers += "${method.name}${method.desc}"
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.HandlerCall(
                owner = mergeInto,
                method = method.name,
                desc = method.desc,
                captures = split.bindings,
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
        mixinClass: String,
        modId: String,
        mergeInto: String,
        handlers: MutableSet<String>,
        kind: HandlerKind,
        rules: MutableList<Rule>,
        problems: MutableList<String>,
    ) {
        val where = "$mergeInto.${method.name}"
        val params = Type.getArgumentTypes(method.desc)
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        val ret = Type.getReturnType(method.desc)
        // The parameter list holds "value + captures", with at least one (the value) -- the receiver is
        // supplied by `this` and is not written in; trailing @Local/@Share are sugar
        if (normal.isEmpty()) {
            problems += "[$modId] $where —— ${kind.annotationName}'s instance handler must have at least one value parameter:" +
                " shaped (values…)T, the receiver is provided by this and not written into the parameter list, currently ${method.desc}"
            return
        }
        // Anchor preconditions are one-by-one the same as the static form (the engine re-checks at emission;
        // here it is reported earlier and with the annotation name)
        when (kind) {
            HandlerKind.MODIFY_ARG, HandlerKind.MODIFY_ARGS ->
                if (anchor.core !is InjectionPoint.Call && anchor.core !is InjectionPoint.Expression) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be a method call (INVOKE)" +
                        " or an @Expression"
                    return
                }

            HandlerKind.MODIFY_VAR ->
                if (anchor.core !is InjectionPoint.Store && anchor.core !is InjectionPoint.Load) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be STORE (after the write)" +
                        " or LOAD (before the read)"
                    return
                }

            HandlerKind.MODIFY_RETURN ->
                if (anchor.core !is InjectionPoint.Return && anchor.core !is InjectionPoint.FinalReturn &&
                    anchor.core !is InjectionPoint.Expression
                ) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be RETURN" +
                        " (every return), TAIL (the last return) or an @Expression"
                    return
                }

            HandlerKind.MODIFY_EXPR_VALUE ->
                if (anchor.core !is InjectionPoint.Expression &&
                    anchor.core !is InjectionPoint.Call && anchor.core !is InjectionPoint.FieldAccess &&
                    anchor.core !is InjectionPoint.Constant
                ) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be a value-producing" +
                        " expression (INVOKE / FIELD / CONSTANT / EXPRESSION)"
                    return
                }

            HandlerKind.MODIFY_RECEIVER ->
                if ((anchor.core !is InjectionPoint.Expression &&
                        anchor.core !is InjectionPoint.Call && anchor.core !is InjectionPoint.FieldAccess) ||
                    anchor.after
                ) {
                    problems += "[$modId] $where —— ${kind.annotationName}'s @At must be an instance call (INVOKE) or " +
                        "field access (FIELD), before the instruction"
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
                captures = split.bindings,
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
        if (kind == HandlerKind.MODIFY_RETURN || kind == HandlerKind.MODIFY_EXPR_VALUE || kind == HandlerKind.MODIFY_RECEIVER) {
            // The value param's type must equal the return type (after modification it is put back on the stack) --
            // whether it is actually the target's type is only known at emission time once the anchor
            // instruction / target method is available (see ProducedValue)
            if (ret == Type.VOID_TYPE || normal[0] != ret) {
                problems += "[$modId] $where —— ${kind.annotationName}'s handler must be shaped (T…)T" +
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
            HandlerKind.MODIFY_RECEIVER -> "@ModifyReceiver"
            HandlerKind.WRAP_WITH_CONDITION -> "@WrapWithCondition"
            HandlerKind.WRAP_BRANCH -> "@WrapBranch"
            HandlerKind.WRAP_OPERATION -> "@WrapOperation"
            HandlerKind.WRAP_METHOD -> "@WrapMethod"
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
        // @Inject carries its handle as the mandatory trailing parameter; a @Cancellable marker here would
        // otherwise be silently ignored, so reject it explicitly
        val argumentCount = Type.getArgumentTypes(method.desc).size
        if ((0 until argumentCount).any { i ->
                method.visibleParameterAnnotations?.getOrNull(i)?.any { it.desc == CANCELABLE_DESC } == true
            }
        ) {
            problems += "[$modId] $where —— @Cancellable is only supported on @WrapOperation handlers for now"
            return
        }
        // The typed self parameter: a bridge-dispatched (@Hooked) handler may declare the target
        // instance as its first parameter, typed as the target class — the engine passes it at the
        // injection point and the declared type is checked at startup. The merge model never strips
        // anything: its `this` already is the target instance.
        val selfType = if (mergeInto == null) {
            Type.getArgumentTypes(method.desc).firstOrNull()
                ?.takeIf { it.descriptor == "L$targetInternal;" }?.descriptor
        } else {
            null
        }
        val handlerDesc = if (selfType != null) {
            val params = Type.getArgumentTypes(method.desc).drop(1)
            "(${params.joinToString("") { it.descriptor }})${Type.getReturnType(method.desc).descriptor}"
        } else {
            method.desc
        }
        val [tail, captureTypes] = handlerShape(handlerDesc)
        if (tail == Tail.NONE) {
            problems += "[$modId] $where —— @Inject handler's last parameter must be HookContext or " +
                "HookValue (consistent with Mixin: the handler must be able to take the callback handle), currently ${method.desc}"
            return
        }
        // Parameter-level sugar: @Local params sit among the captures (before the handle) and are bound
        // by their own discriminator instead of the locals = LocalCapture type-order rule.
        val sugar = LinkedHashMap<Int, DslValue>().apply {
            putAll(localBindings(method))
            putAll(shareBindings(method, mixinClass))
        }
        if (sugar.isNotEmpty()) {
            val originalParams = Type.getArgumentTypes(method.desc)
            if (originalParams.size - 1 in sugar) {
                problems += "[$modId] $where —— @Local cannot bind the trailing HookContext/HookValue parameter"
                return
            }
            if (selfType != null && 0 in sugar) {
                problems += "[$modId] $where —— @Local cannot bind the typed self parameter"
                return
            }
            if (!validateSugarBindings(sugar, originalParams, where, modId, problems)) return
        }
        val returnType = Type.getReturnType(method.desc)
        if (returnType != Type.VOID_TYPE) {
            problems += "[$modId] $where —— @Inject handler must return void, currently returns ${returnType.className}"
            return
        }
        val locals = localCaptureOf(annotation)
        val sugarSlots = captureTypes.indices.filterTo(mutableSetOf()) { i ->
            (i + if (selfType != null) 1 else 0) in sugar
        }
        val nonSugarCaptures = captureTypes.size - sugarSlots.size
        if (locals == LocalCapture.NO_CAPTURE && nonSugarCaptures > 0) {
            problems += "[$modId] $where —— the handler declares $nonSugarCaptures capture parameter(s), but locals is the default " +
                "NO_CAPTURE: please write locals = LocalCapture.CAPTURE_FAILHARD explicitly (or CAPTURE_FAILSOFT)." +
                " Consistent with Mixin —— local-variable capture cannot be verified statically, the author must state the intent explicitly"
            return
        }
        if (locals != LocalCapture.NO_CAPTURE && nonSugarCaptures == 0) {
            problems += "[$modId] $where —— locals = $locals but the handler declares no capture parameters " +
                "(besides the callback handle)"
            return
        }
        val captures = capturesOf(captureTypes, locals, where, modId)
        val captureValues = captures.map { capture ->
            val original = capture.handlerSlot + if (selfType != null) 1 else 0
            sugar[original]
                ?: DslValue.Local(type = capture.type, ordinal = capture.ordinal, strict = capture.strict)
        }

        val cancelable = (annotationValue(annotation, "cancelable") as? Boolean) ?: false
        val targetIsVoid = methodDesc == null || Type.getReturnType(methodDesc) == Type.VOID_TYPE
        val entryAnchor = anchor.core is InjectionPoint.Head || anchor.core is InjectionPoint.ConstructorHead

        // The short-circuit paths split into two branches: a void target uses HookContext (CheckCall); a
        // non-void target uses HookValue (CancelableReturn; setReturnValue both cancels and
        // supplies the value).
        var returnsValue = false
        if (cancelable) {
            when {
                !entryAnchor -> OmlLog.warn(
                    "Mixin",
                    "short-circuiting only supports method-entry anchors (HEAD / CTOR_HEAD), degraded to notify: " +
                        "${methodNames.first()} at=${AnchorResolver.describe(anchor)} (mod=$modId)",
                )

                tail == Tail.RETURNABLE && methodDesc == null -> OmlLog.warn(
                    "Mixin",
                    "value short-circuiting must fix the target descriptor (otherwise the return type is unknown), degraded to notify: " +
                        "${methodNames.first()} (mod=$modId)",
                )

                tail == Tail.RETURNABLE && targetIsVoid -> OmlLog.warn(
                    "Mixin",
                    "target ${methodNames.first()}$methodDesc returns void, the handler should use HookContext instead," +
                        " degraded to notify (mod=$modId)",
                )

                tail == Tail.RETURNABLE -> returnsValue = true

                !targetIsVoid -> OmlLog.warn(
                    "Mixin",
                    "target ${methodNames.first()}$methodDesc returns non-void, the short-circuiting handler should use " +
                        "HookValue to supply the return value, degraded to notify (mod=$modId)",
                )
            }
        } else if (tail == Tail.RETURNABLE) {
            OmlLog.warn(
                "Mixin",
                "the handler takes HookValue but did not declare cancelable = true:" +
                    " only registers as notify (setReturnValue will not take effect): ${methodNames.first()} (mod=$modId)",
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
                cancelable && entryAnchor && returnsValue -> HandlerVariant.RETURNABLE
                cancelable && entryAnchor && targetIsVoid -> HandlerVariant.CANCELABLE
                else -> HandlerVariant.NOTIFY
            }
            val handleDesc = if (variant == HandlerVariant.RETURNABLE) CALLBACK_RETURNABLE_DESC
            else CALLBACK_INFO_DESC
            // Descriptor = capture params + trailing handle, all inside the parens; returns void (the value of a
            // value-bearing variant is taken **from the handle**)
            val desc = "(${captureTypes.joinToString("")}$handleDesc)V"
            val args = captureValues
            rules += Rule(
                targetInternal, methodNames, methodDesc, anchor,
                Payload.HandlerCall(mergeInto, method.name, desc, args, variant, HandlerKind.INJECT),
                policy, captures, "Inject",
            )
            return
        }

        // The bridge captures include the typed self when declared; the handler's real parameter count
        // is those captures plus the trailing handle, which is what overload resolution matches on.
        val bridgeCaptures = listOfNotNull(selfType) + captureTypes
        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancelable = cancelable && entryAnchor && !returnsValue && targetIsVoid,
            captureTypes = bridgeCaptures,
            handlerParamCount = bridgeCaptures.size + 1,
            returnsValue = returnsValue,
        )
        val kind = HookRegistry.bridgeKind(id) ?: HookRegistry.BridgeKind.NOTIFY
        val bridgeName = HookRegistry.bridgeMethodName(id, kind)
        val bridgeDesc = HookRegistry.bridgeDesc(id, kind)
        // The typed self (when declared) is pushed as the target instance; a static target abandons
        // the whole injection at emission (emitValue's This guard) and `require` turns it startup-red.
        val args = (selfType?.let { listOf(DslValue.This) } ?: emptyList()) +
            captureValues +
            DslValue.IntVal(id)
        val payload = when (kind) {
            HookRegistry.BridgeKind.CANCELABLE ->
                Payload.CheckCall(HookRegistry.BRIDGE_CLASS, bridgeName, bridgeDesc, args)

            HookRegistry.BridgeKind.RETURNABLE ->
                Payload.CancelableReturn(HookRegistry.BRIDGE_CLASS, bridgeName, bridgeDesc, args)

            else -> Payload.StaticCall(HookRegistry.BRIDGE_CLASS, bridgeName, bridgeDesc, args)
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
        val new = point as? InjectionPoint.NewInstance
        val expression = point.core is InjectionPoint.Expression
        if (!expression && call == null && new == null) {
            problems += "[$modId] $where —— @Redirect's @At must be a method call (INVOKE / INVOKE_ASSIGN), " +
                "a NEW, or an @Expression"
            return
        }
        if (call != null && (call.owner == null || call.name == null || call.desc == null)) {
            problems += "[$modId] $where —— @Redirect's @At must write a full target (Lowner;name(args)ret):" +
                " the engine must determine the bridge method's descriptor at startup"
            return
        }
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val handlerParams = split.normal.map { it.descriptor }
        val handlerRet = Type.getReturnType(method.desc).descriptor
        val coerceParams = (0 until split.normal.size).filterTo(mutableSetOf()) { i ->
            method.visibleParameterAnnotations?.getOrNull(i)
                ?.any { it.desc == COERCE_DESC } == true
        }
        val coerceReturn = method.visibleAnnotations.orEmpty().any { it.desc == COERCE_DESC }

        if (call != null) {
            val callArgs = Type.getArgumentTypes(call.desc).map { it.descriptor }
            val callRet = Type.getReturnType(call.desc).descriptor
            if (coerceParams.isEmpty() && !coerceReturn) {
                // Handler params = the arguments, or "receiver + arguments" (instance call; consistent with Mixin's
                // "first param is the receiver"); trailing sugar params are extras.
                val receiverOk = handlerParams == callArgs || handlerParams.drop(1) == callArgs
                if (!receiverOk || handlerRet != callRet) {
                    problems += "[$modId] $where —— @Redirect handler's non-sugar parameter list must equal the " +
                        "replaced call (an instance call places the receiver as the first parameter): expected " +
                        "(${callArgs.joinToString("")})$callRet or (receiver${callArgs.joinToString("")})$callRet, " +
                        "actual ${method.desc}"
                    return
                }
            } else {
                // With @Coerce the shape is compared against either pushed form (arguments only, or
                // receiver + arguments), allowing reference supertypes at the annotated positions; the
                // engine knows whether the call is static and rejects the wrong form there.
                val forms = listOf(callArgs, listOf("L${call.owner};") + callArgs)
                fun formOk(pushed: List<String>): Boolean =
                    handlerParams.size == pushed.size && pushed.indices.all { i ->
                        handlerParams[i] == pushed[i] ||
                            (i in coerceParams && isReferenceDescriptor(pushed[i]) &&
                                isReferenceDescriptor(handlerParams[i]))
                    }
                val paramsOk = forms.any(::formOk)
                val retOk = if (coerceReturn) {
                    isReferenceDescriptor(handlerRet) && isReferenceDescriptor(callRet)
                } else {
                    handlerRet == callRet
                }
                if (!paramsOk || !retOk) {
                    problems += "[$modId] $where —— with @Coerce the @Redirect handler must declare the replaced " +
                        "call's receiver plus arguments (instance) or its arguments (static), with reference " +
                        "supertypes only where annotated: actual ${method.desc}"
                    return
                }
            }
        }
        if (new != null && new.owner != null && new.desc != null) {
            val ctorArgs = Type.getArgumentTypes(new.desc).map { it.descriptor }
            val constructed = "L${new.owner};"
            val paramsOk = handlerParams.size == ctorArgs.size && ctorArgs.indices.all { i ->
                handlerParams[i] == ctorArgs[i] ||
                    (i in coerceParams && isReferenceDescriptor(ctorArgs[i]) &&
                        isReferenceDescriptor(handlerParams[i]))
            }
            val retOk = if (coerceReturn) {
                isReferenceDescriptor(handlerRet) && isReferenceDescriptor(constructed)
            } else {
                handlerRet == constructed
            }
            if (!paramsOk || !retOk) {
                problems += "[$modId] $where —— @Redirect on NEW handler's non-sugar parameter list must equal " +
                    "the constructor's arguments and return the constructed type (reference supertypes allowed " +
                    "with @Coerce): actual ${method.desc}"
                return
            }
        }
        // With an @Expression or an unqualified NEW the target is only known at injection time, so the
        // engine validates the handler shape and the bridge descriptor against the actual instruction.
        val payload: Payload
        if (call != null && split.bindings.isEmpty() && coerceParams.isEmpty() && !coerceReturn) {
            val id = HookRegistry.register(
                modId = modId,
                mixinClass = mixinClass,
                handlerMethod = method.name,
                targetMethod = methodNames.first(),
                cancelable = false,
                captureTypes = handlerParams,
                handlerParamCount = handlerParams.size,
                returnType = handlerRet,
                mirror = true,
            )
            payload = Payload.Redirect(
                HookRegistry.BRIDGE_CLASS,
                HookRegistry.bridgeMethodName(id, HookRegistry.BridgeKind.MIRROR),
            )
        } else {
            val captures = handlerParams + split.sugarTypes
            val id = HookRegistry.register(
                modId = modId,
                mixinClass = mixinClass,
                handlerMethod = method.name,
                targetMethod = methodNames.first(),
                cancelable = false,
                captureTypes = captures,
                handlerParamCount = captures.size,
                returnType = handlerRet,
                mirror = false,
            )
            val kind = HookRegistry.BridgeKind.MODIFY
            payload = Payload.Redirect(
                HookRegistry.BRIDGE_CLASS,
                HookRegistry.bridgeMethodName(id, kind),
                desc = HookRegistry.bridgeDesc(id, kind),
                extras = split.bindings + DslValue.IntVal(id),
                coerceParams = coerceParams,
                coerceReturn = coerceReturn,
            )
        }
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor, payload, policy, emptyList(), "Redirect",
        )
    }

    /** A `@Coerce` position must be a reference on both sides: wrapper boxing is not implemented here. */
    private fun isReferenceDescriptor(desc: String): Boolean =
        desc.startsWith("L") || desc.startsWith("[")

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
        val core = anchor.core
        if (core !is InjectionPoint.Call && core !is InjectionPoint.Expression) {
            problems += "[$modId] $where —— @ModifyArg's @At must be a method call (INVOKE) or an @Expression"
            return
        }
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        val ret = Type.getReturnType(method.desc)
        if (normal.size != 1) {
            problems += "[$modId] $where —— @ModifyArg handler must be shaped (T[, sugar…])T " +
                "(T is the type of the argument being modified), currently ${method.desc}"
            return
        }
        val coerceParam = method.visibleParameterAnnotations?.getOrNull(0)
            ?.any { it.desc == COERCE_DESC } == true
        val coerceReturn = method.visibleAnnotations.orEmpty().any { it.desc == COERCE_DESC }
        if (normal[0] != ret) {
            val bothReferences = normal[0].sort in OBJECT_SORTS && ret.sort in OBJECT_SORTS
            if ((!coerceParam && !coerceReturn) || !bothReferences) {
                problems += "[$modId] $where —— @ModifyArg handler must be shaped (T[, sugar…])T, or use " +
                    "@Coerce on a reference parameter/return: currently ${method.desc}"
                return
            }
        }
        val index = declaredInt(annotation, "index") ?: -1
        val [id, bridgeName, bridgeDesc] = registerValueHandler(
            modId, mixinClass, method.name, methodNames.first(),
            captureTypes = listOf(normal[0].descriptor) + split.sugarTypes,
            returnType = ret.descriptor,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyArg(
                owner = HookRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                index = index,
                extras = split.bindings + DslValue.IntVal(id),
                coerceParam = coerceParam,
                coerceReturn = coerceReturn,
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
        val core = anchor.core
        if (core !is InjectionPoint.Call && core !is InjectionPoint.Expression) {
            problems += "[$modId] $where —— @ModifyArgs' @At must be a method call (INVOKE) or an @Expression"
            return
        }
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        if (normal.size != 1 || normal[0].descriptor != ARGS_DESC || Type.getReturnType(method.desc) != Type.VOID_TYPE) {
            problems += "[$modId] $where —— @ModifyArgs handler must be shaped " +
                "($ARGS_DESC[, sugar…])V, currently ${method.desc}"
            return
        }
        val [id, bridgeName, bridgeDesc] = registerValueHandler(
            modId, mixinClass, method.name, methodNames.first(),
            captureTypes = listOf(ARGS_DESC) + split.sugarTypes,
            returnType = "V",
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyArgs(
                owner = HookRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = split.bindings + DslValue.IntVal(id),
            ),
            policy, emptyList(), "ModifyArgs",
        )
    }

    private fun parseModifyReceiver(
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
        val core = anchor.core
        if ((core !is InjectionPoint.Expression &&
                core !is InjectionPoint.Call && core !is InjectionPoint.FieldAccess) || anchor.after
        ) {
            problems += "[$modId] $where —— @ModifyReceiver's @At must be an instance call (INVOKE) or field access " +
                "(FIELD), before the instruction (not INVOKE_ASSIGN / after)"
            return
        }
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        val ret = Type.getReturnType(method.desc)
        if (normal.isEmpty() || ret == Type.VOID_TYPE || normal[0] != ret) {
            problems += "[$modId] $where —— @ModifyReceiver handler must be shaped " +
                "(ReceiverType[, operands…, sugar…])ReceiverType " +
                "(the first parameter and the return type are the receiver type), currently ${method.desc}"
            return
        }
        // The bridge captures are the handler's full value parameter list (receiver + operands + sugar);
        // the engine checks them against the anchor instruction's types at injection time.
        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancelable = false,
            captureTypes = normal.map { it.descriptor } + split.sugarTypes,
            handlerParamCount = normal.size + split.sugarTypes.size,
            returnType = ret.descriptor,
        )
        val kind = HookRegistry.BridgeKind.MODIFY
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyReceiver(
                owner = HookRegistry.BRIDGE_CLASS,
                method = HookRegistry.bridgeMethodName(id, kind),
                desc = HookRegistry.bridgeDesc(id, kind),
                extras = split.bindings + DslValue.IntVal(id),
            ),
            policy, emptyList(), "ModifyReceiver",
        )
    }

    private fun parseWrapWithCondition(
        method: MethodNode,
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
        val core = anchor.core
        if ((core !is InjectionPoint.Expression &&
                core !is InjectionPoint.Call && core !is InjectionPoint.FieldAccess &&
                core !is InjectionPoint.ArrayCreate && core !is InjectionPoint.NewInstance) || anchor.after
        ) {
            problems += "[$modId] $where —— @WrapWithCondition's @At must be a call, field write, " +
                "array creation or NEW, before the instruction"
            return
        }
        val ret = Type.getReturnType(method.desc)
        if (ret != Type.BOOLEAN_TYPE) {
            problems += "[$modId] $where —— @WrapWithCondition handler must return boolean " +
                "(whether the original operation runs), currently ${method.desc}"
            return
        }
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        if (core is InjectionPoint.NewInstance && core.owner != null && core.desc != null) {
            val ctorArgs = Type.getArgumentTypes(core.desc).map { it.descriptor }
            if (normal.map { it.descriptor } != ctorArgs) {
                problems += "[$modId] $where —— @WrapWithCondition on NEW handler's non-sugar parameter " +
                    "list must equal the constructor's arguments: expected " +
                    "(${ctorArgs.joinToString("")})Z, actual ${method.desc}"
                return
            }
        }
        if (mergeInto != null && !method.isDeclaredStatic()) {
            handlers += "${method.name}${method.desc}"
            rules += Rule(
                targetInternal, methodNames, methodDesc, anchor,
                Payload.HandlerCall(
                    mergeInto, method.name, method.desc, split.bindings,
                    HandlerVariant.NOTIFY, HandlerKind.WRAP_WITH_CONDITION,
                ),
                policy, emptyList(), "WrapWithCondition",
            )
            return
        }
        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancelable = false,
            captureTypes = normal.map { it.descriptor } + split.sugarTypes,
            handlerParamCount = normal.size + split.sugarTypes.size,
            returnType = ret.descriptor,
        )
        val kind = HookRegistry.BridgeKind.MODIFY
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.WrapWithCondition(
                owner = HookRegistry.BRIDGE_CLASS,
                method = HookRegistry.bridgeMethodName(id, kind),
                desc = HookRegistry.bridgeDesc(id, kind),
                extras = split.bindings + DslValue.IntVal(id),
            ),
            policy, emptyList(), "WrapWithCondition",
        )
    }

    /**
     * `@WrapBranch` — a handler deciding a conditional branch. The handler's non-sugar parameters are
     * the jump's compared values (validated against the matched opcode at injection time, since a
     * wildcard `JUMP` may hit several forms); its return value is whether the branch is taken.
     */
    private fun parseWrapBranch(
        method: MethodNode,
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
        val core = anchor.core
        if ((core !is InjectionPoint.Jump && core !is InjectionPoint.Expression) || anchor.after) {
            problems += "[$modId] $where —— @WrapBranch's @At must be a conditional jump (JUMP / EXPRESSION), " +
                "before the instruction"
            return
        }
        val ret = Type.getReturnType(method.desc)
        if (ret != Type.BOOLEAN_TYPE) {
            problems += "[$modId] $where —— @WrapBranch handler must return boolean " +
                "(whether the branch is taken), currently ${method.desc}"
            return
        }
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        if (normal.isEmpty()) {
            problems += "[$modId] $where —— @WrapBranch handler must take the jump's compared values " +
                "((I)Z for IFEQ, (II)Z for IF_ICMP*, (Ljava/lang/Object;)Z for IFNULL, …), " +
                "currently ${method.desc}"
            return
        }
        if (mergeInto != null && !method.isDeclaredStatic()) {
            handlers += "${method.name}${method.desc}"
            rules += Rule(
                targetInternal, methodNames, methodDesc, anchor,
                Payload.HandlerCall(
                    mergeInto, method.name, method.desc, split.bindings,
                    HandlerVariant.NOTIFY, HandlerKind.WRAP_BRANCH,
                ),
                policy, emptyList(), "WrapBranch",
            )
            return
        }
        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancelable = false,
            captureTypes = normal.map { it.descriptor } + split.sugarTypes,
            handlerParamCount = normal.size + split.sugarTypes.size,
            returnType = ret.descriptor,
        )
        val kind = HookRegistry.BridgeKind.MODIFY
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.WrapBranch(
                owner = HookRegistry.BRIDGE_CLASS,
                method = HookRegistry.bridgeMethodName(id, kind),
                desc = HookRegistry.bridgeDesc(id, kind),
                extras = split.bindings + DslValue.IntVal(id),
            ),
            policy, emptyList(), "WrapBranch",
        )
    }

    private fun parseWrapMethod(
        method: MethodNode,
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
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        if (normal.isEmpty() || normal.last().descriptor != OPERATION_DESC) {
            problems += "[$modId] $where —— @WrapMethod handler's last non-sugar parameter must be " +
                "$OPERATION_DESC (Operation<R>), currently ${method.desc}"
            return
        }
        val ret = Type.getReturnType(method.desc)
        if (methodDesc != null) {
            val targetParams = Type.getArgumentTypes(methodDesc)
            val targetRet = Type.getReturnType(methodDesc)
            if (normal.size != targetParams.size + 1 ||
                normal.dropLast(1).take(targetParams.size) != targetParams.toList()
            ) {
                problems += "[$modId] $where —— @WrapMethod handler must be shaped " +
                    "(params…, Operation<R>)R matching the target descriptor: target $methodDesc, " +
                    "handler ${method.desc}"
                return
            }
            if (ret != targetRet) {
                problems += "[$modId] $where —— @WrapMethod handler return type must equal the method's: " +
                    "target returns ${targetRet.className}, handler returns ${ret.className}"
                return
            }
        }
        if (mergeInto != null && !method.isDeclaredStatic()) {
            handlers += "${method.name}${method.desc}"
            rules += Rule(
                targetInternal, methodNames, methodDesc, InjectionPoint.Head,
                Payload.HandlerCall(
                    mergeInto, method.name, method.desc, split.bindings,
                    HandlerVariant.NOTIFY, HandlerKind.WRAP_METHOD,
                ),
                policy, emptyList(), "WrapMethod",
            )
            return
        }
        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancelable = false,
            captureTypes = normal.map { it.descriptor } + split.sugarTypes,
            handlerParamCount = normal.size + split.sugarTypes.size,
            returnType = ret.descriptor,
        )
        val kind = HookRegistry.BridgeKind.MODIFY
        rules += Rule(
            targetInternal, methodNames, methodDesc, InjectionPoint.Head,
            Payload.WrapMethod(
                owner = HookRegistry.BRIDGE_CLASS,
                method = HookRegistry.bridgeMethodName(id, kind),
                desc = HookRegistry.bridgeDesc(id, kind),
                extras = split.bindings + DslValue.IntVal(id),
            ),
            policy, emptyList(), "WrapMethod",
        )
    }

    private fun parseWrapOperation(
        method: MethodNode,
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
        val core = anchor.core
        if ((core !is InjectionPoint.Expression &&
                core !is InjectionPoint.Call && core !is InjectionPoint.FieldAccess &&
                core !is InjectionPoint.NewInstance && core !is InjectionPoint.TypeCheck &&
                core !is InjectionPoint.ArrayAccess && core !is InjectionPoint.ArrayCreate) || anchor.after
        ) {
            problems += "[$modId] $where —— @WrapOperation's @At must be a call, field access, NEW, type " +
                "check or array instruction, before the instruction"
            return
        }
        val split = trailingSugar(method, mixinClass, where, modId, problems, allowCancellable = true) ?: return
        val normal = split.normal
        if (normal.isEmpty() || normal.last().descriptor != OPERATION_DESC) {
            problems += "[$modId] $where —— @WrapOperation handler's last non-sugar parameter must be " +
                "$OPERATION_DESC (Operation<R>), currently ${method.desc}"
            return
        }
        val cancelable = split.bindings.filterIsInstance<DslValue.CancelableHandle>().firstOrNull()
        if (cancelable != null && methodDesc != null) {
            val targetRet = Type.getReturnType(methodDesc)
            val expected = if (targetRet == Type.VOID_TYPE) HOOK_CONTEXT_DESC else HOOK_VALUE_DESC
            if (cancelable.descriptor != expected) {
                problems += "[$modId] $where —— @Cancellable on a target returning " +
                    "${targetRet.className} must use ${if (targetRet == Type.VOID_TYPE) "HookContext" else "HookValue"}, " +
                    "currently ${Type.getType(cancelable.descriptor).className}"
                return
            }
        }
        val ret = Type.getReturnType(method.desc)
        val operationIndex = normal.size - 1
        val coerceParams = (0 until operationIndex).filterTo(mutableSetOf()) { i ->
            method.visibleParameterAnnotations?.getOrNull(i)
                ?.any { it.desc == COERCE_DESC } == true
        }
        val coerceReturn = method.visibleAnnotations.orEmpty().any { it.desc == COERCE_DESC }
        if (mergeInto != null && !method.isDeclaredStatic()) {
            if (coerceParams.isNotEmpty() || coerceReturn) {
                problems += "[$modId] $where —— @Coerce is not supported on merged @WrapOperation handlers yet: " +
                    "make the handler static"
                return
            }
            handlers += "${method.name}${method.desc}"
            rules += Rule(
                targetInternal, methodNames, methodDesc, anchor,
                Payload.HandlerCall(
                    mergeInto, method.name, method.desc, split.bindings,
                    HandlerVariant.NOTIFY, HandlerKind.WRAP_OPERATION,
                ),
                policy, emptyList(), "WrapOperation",
            )
            return
        }
        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancelable = false,
            captureTypes = normal.map { it.descriptor } + split.sugarTypes,
            handlerParamCount = normal.size + split.sugarTypes.size,
            returnType = ret.descriptor,
        )
        val kind = HookRegistry.BridgeKind.MODIFY
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.WrapOperation(
                owner = HookRegistry.BRIDGE_CLASS,
                method = HookRegistry.bridgeMethodName(id, kind),
                desc = HookRegistry.bridgeDesc(id, kind),
                extras = split.bindings + DslValue.IntVal(id),
                coerceParams = coerceParams,
                coerceReturn = coerceReturn,
            ),
            policy, emptyList(), "WrapOperation",
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
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        val ret = Type.getReturnType(method.desc)
        if (normal.size != 1) {
            problems += "[$modId] $where —— @ModifyConstant handler must be shaped (T[, sugar…])T " +
                "(T is the constant type), currently ${method.desc}"
            return
        }
        val coerceParam = method.visibleParameterAnnotations?.getOrNull(0)
            ?.any { it.desc == COERCE_DESC } == true
        val coerceReturn = method.visibleAnnotations.orEmpty().any { it.desc == COERCE_DESC }
        if (normal[0] != ret) {
            val bothReferences = normal[0].sort in OBJECT_SORTS && ret.sort in OBJECT_SORTS
            if ((!coerceParam && !coerceReturn) || !bothReferences) {
                problems += "[$modId] $where —— @ModifyConstant handler must be shaped (T[, sugar…])T, or use " +
                    "@Coerce on a reference parameter/return: currently ${method.desc}"
                return
            }
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
        val anchor = withSlice(base, null, annotation, slices, method, modId, where, "@ModifyConstant", problems)
        val [id, bridgeName, bridgeDesc] = registerValueHandler(
            modId, mixinClass, method.name, methodNames.first(),
            captureTypes = listOf(normal[0].descriptor) + split.sugarTypes,
            returnType = ret.descriptor,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyConstant(
                owner = HookRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = split.bindings + DslValue.IntVal(id),
                coerceParam = coerceParam,
                coerceReturn = coerceReturn,
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
        val coerceParam = method.visibleParameterAnnotations?.getOrNull(0)
            ?.any { it.desc == COERCE_DESC } == true
        val coerceReturn = method.visibleAnnotations.orEmpty().any { it.desc == COERCE_DESC }
        if (ret == Type.VOID_TYPE || params.isEmpty()) {
            problems += "$where —— $shortName's handler must be shaped (T[, captures…])T (T is the type of the local variable being modified," +
                " the return value is the new value), currently ${method.desc}"
            return null
        }
        if (params[0] != ret) {
            val bothReferences = params[0].sort in OBJECT_SORTS && ret.sort in OBJECT_SORTS
            if ((!coerceParam && !coerceReturn) || !bothReferences) {
                problems += "$where —— $shortName's handler must be shaped (T[, captures…])T, or use " +
                    "@Coerce on a reference parameter/return: currently ${method.desc}"
                return null
            }
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
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        val valueType = Type.getReturnType(method.desc).descriptor
        val captureTypes = normal.drop(1).map { it.descriptor }
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

        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = method.name,
            targetMethod = methodNames.first(),
            cancelable = false,
            // The bridge's param list is exactly "the engine's push order": value, captures, sugars, then id
            captureTypes = listOf(valueType) + captureTypes + split.sugarTypes,
            handlerParamCount = params.size,
            returnType = valueType,
        )
        val kind = HookRegistry.BridgeKind.MODIFY
        val bridgeName = HookRegistry.bridgeMethodName(id, kind)
        val bridgeDesc = HookRegistry.bridgeDesc(id, kind)
        val coerceParam = method.visibleParameterAnnotations?.getOrNull(0)
            ?.any { it.desc == COERCE_DESC } == true
        val coerceReturn = method.visibleAnnotations.orEmpty().any { it.desc == COERCE_DESC }

        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyVariable(
                owner = HookRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = captures.map { DslValue.Local(type = it.type, ordinal = it.ordinal, strict = it.strict) } +
                    split.bindings +
                    DslValue.IntVal(id),
                coerceParam = coerceParam,
                coerceReturn = coerceReturn,
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
        is InjectionPoint.Expression -> if (point.after) point else point.copy(after = true)
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
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        val ret = Type.getReturnType(method.desc)
        if (ret == Type.VOID_TYPE || normal.size != 1) {
            problems += "[$modId] $where —— @ModifyReturnValue handler must be shaped (R[, sugar…])R " +
                "(R is the return type, put back on the stack after the change), currently ${method.desc}"
            return
        }
        val coerceParam = method.visibleParameterAnnotations?.getOrNull(0)
            ?.any { it.desc == COERCE_DESC } == true
        val coerceReturn = method.visibleAnnotations.orEmpty().any { it.desc == COERCE_DESC }
        if (normal[0] != ret) {
            val bothReferences = normal[0].sort in OBJECT_SORTS && ret.sort in OBJECT_SORTS
            if ((!coerceParam && !coerceReturn) || !bothReferences) {
                problems += "[$modId] $where —— @ModifyReturnValue handler must be shaped (R[, sugar…])R, " +
                    "or use @Coerce on a reference parameter/return: currently ${method.desc}"
                return
            }
        }
        val [id, bridgeName, bridgeDesc] = registerValueHandler(
            modId, mixinClass, method.name, methodNames.first(),
            captureTypes = listOf(normal[0].descriptor) + split.sugarTypes,
            returnType = ret.descriptor,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.TransformReturn(
                owner = HookRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = split.bindings + DslValue.IntVal(id),
                coerceParam = coerceParam,
                coerceReturn = coerceReturn,
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
        val split = trailingSugar(method, mixinClass, where, modId, problems) ?: return
        val normal = split.normal
        val ret = Type.getReturnType(method.desc)
        if (ret == Type.VOID_TYPE || normal.size != 1) {
            problems += "[$modId] $where —— @ModifyExpressionValue handler must be shaped (T[, sugar…])T " +
                "(T is the type produced by the expression, put back on the stack after the change), " +
                "currently ${method.desc}"
            return
        }
        val coerceParam = method.visibleParameterAnnotations?.getOrNull(0)
            ?.any { it.desc == COERCE_DESC } == true
        val coerceReturn = method.visibleAnnotations.orEmpty().any { it.desc == COERCE_DESC }
        if (normal[0] != ret && !coerceParam && !coerceReturn) {
            problems += "[$modId] $where —— @ModifyExpressionValue handler must be shaped " +
                "(T[, sugar…])T, or use @Coerce on the parameter/return: currently ${method.desc}"
            return
        }
        val [id, bridgeName, bridgeDesc] = registerValueHandler(
            modId, mixinClass, method.name, methodNames.first(),
            captureTypes = listOf(normal[0].descriptor) + split.sugarTypes,
            returnType = ret.descriptor,
        )
        rules += Rule(
            targetInternal, methodNames, methodDesc, anchor,
            Payload.ModifyExpressionValue(
                owner = HookRegistry.BRIDGE_CLASS,
                method = bridgeName,
                desc = bridgeDesc,
                extras = split.bindings + DslValue.IntVal(id),
                coerceParam = coerceParam,
                coerceReturn = coerceReturn,
            ),
            policy, emptyList(), "ModifyExpressionValue",
        )
    }

    /**
     * Register a value-rewriting handler with its full bridge capture list (normal value params plus any
     * trailing sugar params). The bridge appends the handler id, so the descriptor is `(captures, I)return`.
     */
    private fun registerValueHandler(
        modId: String,
        mixinClass: String,
        handlerMethod: String,
        targetMethod: String,
        captureTypes: List<String>,
        returnType: String,
    ): Triple<Int, String, String> {
        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = handlerMethod,
            targetMethod = targetMethod,
            cancelable = false,
            captureTypes = captureTypes,
            handlerParamCount = captureTypes.size,
            returnType = returnType,
        )
        val kind = HookRegistry.BridgeKind.MODIFY
        return Triple(id, HookRegistry.bridgeMethodName(id, kind), HookRegistry.bridgeDesc(id, kind))
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
        val id = HookRegistry.register(
            modId = modId,
            mixinClass = mixinClass,
            handlerMethod = handlerMethod,
            targetMethod = targetMethod,
            cancelable = false,
            captureTypes = listOf(type),
            handlerParamCount = 1,
            returnType = if (returnVoid) "V" else type,
        )
        val kind = HookRegistry.BridgeKind.MODIFY
        return Triple(id, HookRegistry.bridgeMethodName(id, kind), HookRegistry.bridgeDesc(id, kind))
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
            OmlLog.info(
                "Mixin",
                "$where declares locals = PRINT: should be able to capture at the injection point " +
                    captures.joinToString(", ") { "${it.type}#${it.ordinal}" } + " (mod=$modId)",
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
        method: MethodNode,
        modId: String,
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
        val fromNode = annotationValue(sliceNode, "from") as? AnnotationNode
        val toNode = annotationValue(sliceNode, "to") as? AnnotationNode
        val from = fromNode?.let { parseAnchor(it, method, where, modId, shortName, problems) }
        val to = toNode?.let { parseAnchor(it, method, where, modId, shortName, problems) }
        // A boundary that is written but unrecognizable is a rule error, not "no boundary": silently
        // dropping it would widen the search window to the whole method (the engine side refuses for
        // the same reason when a boundary cannot be resolved, see Anchors.applySlice).
        if (fromNode != null && from == null) {
            problems += "$where —— $shortName's @Slice(from = @At(\"${annotationValue(fromNode, "value")}\")) " +
                "is not a recognized injection point (available: ${AtParser.SUPPORTED.joinToString("/")})"
        }
        if (toNode != null && to == null) {
            problems += "$where —— $shortName's @Slice(to = @At(\"${annotationValue(toNode, "value")}\")) " +
                "is not a recognized injection point (available: ${AtParser.SUPPORTED.joinToString("/")})"
        }
        if (from == null && to == null) return point
        return InjectionPoint.Sliced(point, from, to)
    }

    // ---------- annotation retrieval ----------

    /** All `@Expression`s on the handler, whether written directly or through the repeatable container. */
    private fun expressionAnnotations(method: MethodNode): List<AnnotationNode> =
        method.visibleAnnotations.orEmpty().flatMap { annotation ->
            when (annotation.desc) {
                EXPRESSION_DESC -> listOf(annotation)
                EXPRESSIONS_DESC -> (annotationValue(annotation, "value") as? List<*>).orEmpty()
                    .filterIsInstance<AnnotationNode>()
                else -> emptyList()
            }
        }

    /** All `@Definition`s on the handler, whether written directly or through the repeatable container. */
    private fun definitionAnnotations(method: MethodNode): List<AnnotationNode> =
        method.visibleAnnotations.orEmpty().flatMap { annotation ->
            when (annotation.desc) {
                DEFINITION_DESC -> listOf(annotation)
                DEFINITIONS_DESC -> (annotationValue(annotation, "value") as? List<*>).orEmpty()
                    .filterIsInstance<AnnotationNode>()
                else -> emptyList()
            }
        }

    /** `@Lambda` → selector info; a malformed one is reported and the method skipped. */
    private fun parseLambda(
        node: AnnotationNode,
        method: MethodNode,
        mixinClass: String,
        modId: String,
        problems: MutableList<String>,
    ): LambdaSelector? {
        val type = annotationValue(node, "interfaceType") as? Type
        if (type == null || type.sort != Type.OBJECT) {
            problems += "[$modId] $mixinClass.${method.name} —— @Lambda's interfaceType must be a class"
            return null
        }
        val ordinal = (annotationValue(node, "ordinal") as? Int) ?: 0
        if (ordinal < 0) {
            problems += "[$modId] $mixinClass.${method.name} —— @Lambda's ordinal cannot be negative ($ordinal)"
            return null
        }
        return LambdaSelector(type.internalName, ordinal)
    }

    /** Whether this `@At` names the expression anchor. */
    private fun isExpressionAt(atNode: AnnotationNode): Boolean =
        ((annotationValue(atNode, "value") as? String) ?: "")
            .substringBefore(':').trim().equals("EXPRESSION", ignoreCase = true)

    /**
     * `@At` → injection point. The expression anchor is special: its pattern lives on the handler
     * (`@Expression`), not in the `@At`, so the front-end assembles it here and validates the pattern
     * by parsing it now — a malformed expression must fail the startup check, not the class load.
     */
    private fun parseAnchor(
        atNode: AnnotationNode,
        method: MethodNode,
        where: String,
        modId: String,
        shortName: String,
        problems: MutableList<String>,
    ): InjectionPoint? {
        if (!isExpressionAt(atNode)) return AtParser.parse(atNode)

        val id = (annotationValue(atNode, "id") as? String).orEmpty()
        val patterns = expressionAnnotations(method)
            .filter { ((annotationValue(it, "id") as? String).orEmpty()) == id }
            .flatMap { stringArray(annotationValue(it, "value")) }
            .filter { it.isNotBlank() }
        if (patterns.isEmpty()) {
            problems += "[$modId] $where —— $shortName's @At(EXPRESSION) needs an @Expression(\"…\") " +
                (if (id.isEmpty()) "on the handler" else "with id = \"$id\" on the handler")
            return null
        }
        for (pattern in patterns) {
            try {
                org.ohmyloader.core.expression.ExpressionParserFacade.parse(pattern)
            } catch (failure: RuntimeException) {
                problems += "[$modId] $where —— @Expression pattern cannot be parsed: ${failure.message}"
                return null
            }
        }
        val definitions = definitionAnnotations(method)
            .map { node ->
                val types = (annotationValue(node, "type") as? List<*>).orEmpty()
                    .filterIsInstance<org.objectweb.asm.Type>()
                    .map { it.descriptor }
                val locals = (annotationValue(node, "local") as? List<*>).orEmpty()
                    .filterIsInstance<AnnotationNode>()
                    .map { local ->
                        InjectionPoint.Expression.Definition.Local(
                            index = (annotationValue(local, "index") as? Int)?.takeIf { it >= 0 },
                            type = (annotationValue(local, "type") as? String)?.takeIf { it.isNotEmpty() },
                            ordinal = (annotationValue(local, "ordinal") as? Int)?.takeIf { it >= 0 } ?: 0,
                            argsOnly = annotationValue(local, "argsOnly") as? Boolean ?: false,
                            print = annotationValue(local, "print") as? Boolean ?: false,
                        )
                    }
                InjectionPoint.Expression.Definition(
                    id = (annotationValue(node, "id") as? String).orEmpty(),
                    methods = stringArray(annotationValue(node, "method")),
                    fields = stringArray(annotationValue(node, "field")),
                    types = types,
                    locals = locals,
                )
            }
        for (definition in definitions) {
            if (definition.id.isBlank()) {
                problems += "[$modId] $where —— @Definition needs a non-empty id (the identifier the expression uses)"
                return null
            }
            if (definition.locals.any { it.index == null && it.type == null }) {
                problems += "[$modId] $where —— a @DefinitionLocal needs index or type " +
                    "(otherwise the local variable cannot be located)"
                return null
            }
        }
        // Pure syntax check: every identifier a pattern uses must be declared, and this is decidable without
        // the target class. Catching it here turns a weaving-time IllegalStateException into a startup problem.
        val memberIds = definitions
            .filter { it.methods.isNotEmpty() || it.fields.isNotEmpty() || it.locals.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.id } + org.ohmyloader.core.expression.pool.IdentifierPool.DEFAULT_MEMBER_IDS
        val typeIds = definitions
            .filter { it.types.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.id } + org.ohmyloader.core.expression.pool.IdentifierPool.DEFAULT_TYPE_IDS
        var undeclared = false
        for (pattern in patterns) {
            val used = runCatching {
                org.ohmyloader.core.expression.ExpressionParserFacade.collectIdentifiers(pattern)
            }.getOrNull() ?: continue
            for (id in used.members) {
                if (id in memberIds) continue
                undeclared = true
                problems += "[$modId] $where —— @Expression \"$pattern\" uses undeclared identifier \"$id\": " +
                    "declare it with @Definition(id = \"$id\", method|field|local = …)"
            }
            for (id in used.types) {
                if (id in typeIds) continue
                undeclared = true
                problems += "[$modId] $where —— @Expression \"$pattern\" uses undeclared type \"$id\": " +
                    "declare it with @Definition(id = \"$id\", type = …)"
            }
        }
        if (undeclared) return null
        val ordinal = (annotationValue(atNode, "ordinal") as? Int)?.takeIf { it >= 0 }
        return InjectionPoint.Expression(patterns, ordinal, definitions)
    }

    /**
     * Diagnostic text for an unrecognized `@At`: the value plus, when written, the target — a legacy
     * dotted owner or a bad specifier suffix hides in the target just as often as in the value.
     */
    private fun unrecognizedAt(atNode: AnnotationNode): String {
        val target = (annotationValue(atNode, "target") as? String).orEmpty()
        val targetPart = if (target.isEmpty()) "" else ", target = \"$target\""
        val shiftRaw = annotationValue(atNode, "shift")
        val shiftName = AtParser.enumName(shiftRaw)
        val shiftPart = if (shiftRaw != null && shiftName !in AtParser.SUPPORTED_SHIFTS) {
            ", shift = \"$shiftName\" is not supported (only BEFORE/AFTER)"
        } else {
            ""
        }
        return "@At(value = \"${annotationValue(atNode, "value")}\"$targetPart$shiftPart) " +
            "is not a recognized injection point (available: ${AtParser.SUPPORTED.joinToString("/")})"
    }

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
    /**
     * Parameter-level sugar (`@Local`) on a handler: parameter index → the local binding it requests.
     * The parameter's own type is the fallback lookup type, so `@Local(ordinal = 1)` means "the 2nd local
     * of this parameter's type". A missing local is made a hard error: the author asked for it explicitly.
     */
    private fun localBindings(method: MethodNode): Map<Int, DslValue> {
        val annotations = method.visibleParameterAnnotations ?: return emptyMap()
        val paramTypes = Type.getArgumentTypes(method.desc)
        val out = LinkedHashMap<Int, DslValue>()
        for (i in annotations.indices) {
            val annotation = annotations[i]?.firstOrNull { it.desc == LOCAL_DESC } ?: continue
            val index = declaredInt(annotation, "index")
            val ordinal = declaredInt(annotation, "ordinal") ?: 0
            val declaredType = (annotationValue(annotation, "type") as? String)?.takeIf { it.isNotEmpty() }
            val paramType = paramTypes.getOrNull(i)?.descriptor
            val typedRef = paramType?.let { TYPED_REFS[it] }
            val isRef = paramType == LOCAL_REF_DESC || typedRef != null
            if (annotationValue(annotation, "print") == true) {
                OmlLog.info(
                    "Mixin",
                    "@Local(print) ${method.name}${method.desc} parameter $i -> " +
                        (index?.let { "slot $it" }
                            ?: "the $ordinal-th local of type ${declaredType ?: if (isRef) "the ref's slot type" else paramType}"),
                )
            }
            out[i] = if (isRef) {
                // The parameter type is the ref, not the local: a typed ref fixes the local's type, while a
                // generic LocalRef needs the type written or a pinned slot.
                DslValue.RefLocal(
                    index = index,
                    type = declaredType ?: typedRef?.primitive?.descriptor,
                    ordinal = ordinal,
                    strict = true,
                    refType = paramType,
                )
            } else {
                val type = declaredType ?: paramType ?: continue
                DslValue.Local(index = index, type = type, ordinal = ordinal, strict = true)
            }
        }
        return out
    }

    /** `@Share` bindings: parameter index → the shared ref it requests. */
    private fun shareBindings(method: MethodNode, mixinClass: String): Map<Int, DslValue> {
        val annotations = method.visibleParameterAnnotations ?: return emptyMap()
        val out = LinkedHashMap<Int, DslValue>()
        for (i in annotations.indices) {
            val annotation = annotations[i]?.firstOrNull { it.desc == SHARE_DESC } ?: continue
            val id = (annotationValue(annotation, "value") as? String).orEmpty()
            val namespace = (annotationValue(annotation, "namespace") as? String)?.takeIf { it.isNotEmpty() }
                ?: mixinClass
            out[i] = DslValue.SharedRef(namespace, id, LOCAL_REF_DESC)
        }
        return out
    }

    /**
     * A non-@Inject handler split into its normal parameter prefix, the trailing sugar parameters'
     * descriptors, and the sugar bindings themselves.
     */
    private class SugarSplit(
        val normal: Array<Type>,
        val sugarTypes: List<String>,
        val bindings: List<DslValue>,
    )

    /**
     * Splits a handler of a value-rewriting injector: `@Local`/`@Share` parameters must form a
     * contiguous **trailing** suffix, and the prefix is the injector's normal parameter list.
     */
    private fun trailingSugar(
        method: MethodNode,
        mixinClass: String,
        where: String,
        modId: String,
        problems: MutableList<String>,
        allowCancellable: Boolean = false,
    ): SugarSplit? {
        val params = Type.getArgumentTypes(method.desc)
        val annotations = method.visibleParameterAnnotations
        val annotated = params.indices.filter { i ->
            annotations?.getOrNull(i)?.any {
                it.desc == LOCAL_DESC || it.desc == SHARE_DESC || it.desc == CANCELABLE_DESC
            } == true
        }
        if (annotated.isEmpty()) return SugarSplit(params, emptyList(), emptyList())
        val first = annotated.first()
        if (annotated.size != params.size - first || annotated != (first until params.size).toList()) {
            problems += "[$modId] $where —— @Local/@Share/@Cancellable parameters must be a contiguous trailing suffix"
            return null
        }
        val locals = localBindings(method)
        val shares = shareBindings(method, mixinClass)
        val bindings = (first until params.size).map { i ->
            val cancellable = annotations?.getOrNull(i)?.any { it.desc == CANCELABLE_DESC } == true
            if (cancellable) {
                if (!allowCancellable) {
                    problems += "[$modId] $where —— @Cancellable is only supported on @WrapOperation handlers for now"
                    return null
                }
                if (i != params.size - 1) {
                    problems += "[$modId] $where —— @Cancellable must be the last handler parameter"
                    return null
                }
                val descriptor = params[i].descriptor
                if (descriptor != HOOK_CONTEXT_DESC && descriptor != HOOK_VALUE_DESC) {
                    problems += "[$modId] $where —— @Cancellable parameter $i must be HookContext (void target) " +
                        "or HookValue (non-void target), currently ${params[i].className}"
                    return null
                }
                DslValue.CancelableHandle(descriptor)
            } else {
                locals[i] ?: shares[i] ?: run {
                    problems += "[$modId] $where —— internal: no sugar binding for parameter $i"
                    return null
                }
            }
        }
        if (!validateSugarBindings((first until params.size).associateWith { bindings[it - first] }, params, where, modId, problems)) {
            return null
        }
        return SugarSplit(
            params.copyOfRange(0, first),
            (first until params.size).map { params[it].descriptor },
            bindings,
        )
    }

    /** Shared validation for @Local/@Share bindings (parameter type, discriminator, non-empty share id). */
    private fun validateSugarBindings(
        sugar: Map<Int, DslValue>,
        params: Array<Type>,
        where: String,
        modId: String,
        problems: MutableList<String>,
    ): Boolean {
        for ((param, binding) in sugar) {
            if (binding is DslValue.RefLocal && binding.index == null && binding.type == null) {
                problems += "[$modId] $where —— @Local on a LocalRef parameter (parameter $param) needs " +
                    "index or type: the parameter type is the ref, not the local"
                return false
            }
            if (binding is DslValue.SharedRef) {
                if (binding.id.isEmpty()) {
                    problems += "[$modId] $where —— @Share needs a non-empty value (the share id)"
                    return false
                }
                if (params.getOrNull(param)?.descriptor != LOCAL_REF_DESC) {
                    problems += "[$modId] $where —— @Share parameter $param must be LocalRef<T>"
                    return false
                }
            }
        }
        return true
    }

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
                "intValue", "longValue", "floatValue", "doubleValue", "stringValue", "classValue" ->
                    return values[i + 1]
            }
            i += 2
        }
        return null
    }
}
