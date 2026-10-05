package org.ohmyloader.api.inject

import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodNode

/**
 * The injection DSL builders: they translate declarative blocks into the rule
 * model ([RuleSet] and its [MethodRule] / [InjectionPoint] / [Payload]).
 *
 * The `*Builder` classes below are DSL receivers — authors only use the methods
 * inside the blocks and never need to reference their types. This file only
 * handles "expressing the intent clearly": anchor resolution, validation, and
 * bytecode construction all live in the engine.
 */

/** The `invokestatic` opcode (class file format §6.5). This is the only place in the DSL that uses an opcode directly. */
private const val OPCODE_INVOKESTATIC: Int = 0xB8

class InjectionBuilder @PublishedApi internal constructor() {
    internal val classRules = LinkedHashMap<String, ClassRules>()
    internal val merges = mutableListOf<MergeSource>()

    /** Declares the rules of a class. The class name is the target class's internal name; the same class may be opened multiple times, and rules accumulate. */
    fun classTarget(internalName: String, block: ClassTargetBuilder.() -> Unit) {
        val target = ClassTargetBuilder(internalName)
        target.block()
        val previous = classRules[internalName]
        classRules[internalName] = ClassRules(
            methods = (previous?.methods ?: emptyList()) + target.rules,
            accessRules = (previous?.accessRules ?: emptyList()) + target.accessRules,
        )
        merges += target.merges
    }
}

class ClassTargetBuilder @PublishedApi internal constructor(
    /** The target class's internal name — [merge] and [MethodRuleBuilder.mergedCall] both need it, so the author never hand-writes the owner. */
    @PublishedApi internal val targetInternal: String,
) {
    internal val rules = mutableListOf<MethodRule>()
    internal val accessRules = mutableListOf<AccessRule>()
    internal val merges = mutableListOf<MergeSource>()

    /**
     * Declares a "merge into the target class" source: its members are **truly merged** into the
     * target class, so the handler can be called with `this` = target instance (see [MergeSource]).
     * [source] is a class name **within the same mod jar** (internal name): the loader only reads
     * its bytecode and never loads it — it necessarily references game classes, and loading it
     * would drag them in. Use member-level markers (`@Shadow` / `@Unique` / …) inside it to state
     * whether each member is "verify existing" or "add new".
     */
    fun merge(source: String, id: String? = null) {
        merges += MergeSource(targetInternal, source, id)
    }

    /** Selects a method: any of [names] matching suffices (handy for giving both an aliased name and the readable name); [desc] being null matches any. */
    fun method(vararg names: String, desc: String? = null, block: MethodRuleBuilder.() -> Unit) {
        val rule = MethodRuleBuilder(MethodSelector(names.toSet(), desc), targetInternal)
        rule.block()
        rules += rule.build()
    }

    /** Selects the constructor `<init>`. */
    fun constructor(desc: String? = null, block: MethodRuleBuilder.() -> Unit) =
        method("<init>", desc = desc, block = block)

    /**
     * Rewrites the **class's own** access flags.
     *
     * A top-level class has only two options — public and package-private;
     * private/protected are tiers that only nested classes have, and they live on
     * the `InnerClasses` attribute (a nested class is changed in both places).
     */
    fun access(block: AccessRuleBuilder.() -> Unit) {
        accessRules += AccessRuleBuilder(MemberKind.CLASS, emptySet(), null).apply(block).build()
    }

    /**
     * Rewrites a **field's** access flags (every field matching any of [names]
     * and whose descriptor matches [desc]).
     *
     * [desc] is the field's type descriptor (`I` / `Ljava/net/Proxy;`); null = no
     * restriction. It **must be written as a named argument** (`desc = "I"`): the
     * member names are a vararg, so a second positional argument would be taken as
     * yet another name — such a rule could never match any member, hence the
     * validation phase reports it directly.
     */
    fun field(vararg names: String, desc: String? = null, block: AccessRuleBuilder.() -> Unit) {
        accessRules += AccessRuleBuilder(MemberKind.FIELD, names.toSet(), desc).apply(block).build()
    }

    /**
     * Rewrites a **method's** access flags. Named separately from [method]: that one injects code
     * into the method body, this one rewrites the declaration's flags — merging them would make
     * "whether a rule changes code" unreadable.
     * [desc] works like [field] and **must be a named argument** (`desc = "()V"`): the names are
     * a vararg, so a second positional argument would be taken as yet another name and could
     * never match — the validation phase reports that directly.
     */
    fun methodAccess(vararg names: String, desc: String? = null, block: AccessRuleBuilder.() -> Unit) {
        accessRules += AccessRuleBuilder(MemberKind.METHOD, names.toSet(), desc).apply(block).build()
    }
}

/**
 * Rule builder for access-flag rewriting.
 *
 * Only "change the visibility tier" and "remove `final`" are offered — neither touches the class
 * file's structural constraints, so the result is always a valid class. The other flags are not
 * exposed: flipping `ACC_STATIC` instantly invalidates every `GETSTATIC`/`INVOKESTATIC` call site;
 * `ACC_ABSTRACT`/`ACC_NATIVE` directly conflict with "whether a Code attribute exists" — both
 * require the rule to also supply a method body, which a single flag cannot express (injection's
 * job).
 */
class AccessRuleBuilder @PublishedApi internal constructor(
    private val kind: MemberKind,
    private val names: Set<String>,
    private val desc: String?,
) {
    private val visibilities = mutableListOf<Visibility>()
    private var removeFinal = false
    private var requireMin: Int? = null
    private var allowMax: Int? = null
    private var expectCount: Int? = null
    private var optionalRule = false

    /** Changes it to `public`. */
    fun makePublic() {
        visibilities += Visibility.PUBLIC
    }

    /** Changes it to `protected`. */
    fun makeProtected() {
        visibilities += Visibility.PROTECTED
    }

    /** Changes it to package-private (clears the three visibility bits). */
    fun makePackagePrivate() {
        visibilities += Visibility.PACKAGE
    }

    /** Changes it to `private`. */
    fun makePrivate() {
        visibilities += Visibility.PRIVATE
    }

    /**
     * Removes `final`.
     *
     * Removing `final` from a field makes it writable (`PUTFIELD`/`PUTSTATIC` and
     * reflective writes are no longer rejected); from a class, inheritable; from a
     * method, overridable.
     */
    fun removeFinal() {
        removeFinal = true
    }

    /** Hits **at least** [n] members, otherwise fails hard ([InjectionError]). The same `require` semantics. */
    fun require(n: Int) {
        requireMin = n
    }

    /** Hits **at most** [n] members; exceeding it fails hard (blocks over-broad name/descriptor). */
    fun allow(n: Int) {
        allowMax = n
    }

    /** Expects to hit [n] members; a mismatch only warns. */
    fun expect(n: Int) {
        expectCount = n
    }

    /** Allows no member to match at all (e.g. a member only present in some versions), merely suppressing the "no match" warning. */
    fun optional() {
        optionalRule = true
    }

    internal fun build(): AccessRule = AccessRule(
        kind = kind,
        names = names,
        desc = desc,
        visibilities = visibilities.toList(),
        removeFinal = removeFinal,
        require = requireMin,
        expect = expectCount,
        allow = allowMax,
        optional = optionalRule,
    )
}

class MethodRuleBuilder @PublishedApi internal constructor(
    private val selector: MethodSelector,
    /** The target class's internal name: a merged-form handler lives inside it, so it is written as the owner when fetching values. */
    @PublishedApi internal val targetInternal: String,
) {
    private val points = mutableListOf<Pair<InjectionPoint, Payload>>()
    private var requireMin: Int? = null
    private var allowMax: Int? = null
    private var expectCount: Int? = null
    private var optionalRule = false

    /** The currently active search window (temporarily set by [within]): its `from to to` two bounds (null = unbounded). */
    private var pendingSlice: Pair<InjectionPoint?, InjectionPoint?>? = null

    internal fun build(): MethodRule =
        MethodRule(selector, points.toList(), requireMin, expectCount, optionalRule, allowMax)

    /**
     * Wraps the block's injection points in a **search window**: search [inner]
     * only between [from] and [to] (excluding both ends).
     *
     * The bounds are built from anchor values in [Anchor] (the same-named methods
     * on the builder mean "inject here"; those in [Anchor] mean "treat this
     * position as a bound"). Nested [within] takes the intersection.
     */
    fun within(
        from: InjectionPoint? = null,
        to: InjectionPoint? = null,
        block: MethodRuleBuilder.() -> Unit,
    ) {
        val previous = pendingSlice
        pendingSlice = from to to
        try {
            block()
        } finally {
            pendingSlice = previous
        }
    }

    /** Wraps the anchor in the current window (if any). Every entry point that attaches an injection point must go through here. */
    private fun at(point: InjectionPoint): InjectionPoint =
        pendingSlice?.let { [from, to] -> InjectionPoint.Sliced(point, from, to) } ?: point

    /**
     * Hits **at least** [n] times, otherwise fails hard ([InjectionError]).
     *
     * Semantics match Mixin's `@Inject(require = n)` (a **lower bound**, not
     * "exactly n times") — a given annotation must behave identically when ported.
     * To express "exactly n times", also write [allow]. When a rule is wrong
     * (name/descriptor/anchor mismatch), the game will not crash — it silently does
     * one thing less, exactly the hardest kind of bug to find, so a "must hit" rule
     * should declare it explicitly.
     */
    fun require(n: Int) {
        requireMin = n
    }

    /** Hits **at most** [n] times; exceeding it fails hard (Mixin's `allow`: prevents hitting a bunch of places by accident). */
    fun allow(n: Int) {
        allowMax = n
    }

    /** Expects to hit [n] times; a mismatch only warns (Mixin's `expect`). */
    fun expect(n: Int) {
        expectCount = n
    }

    /** Allows a miss: only suppresses the "no match" warning (e.g. a target method only present in some versions); does not change hit-count semantics. */
    fun optional() {
        optionalRule = true
    }

    /**
     * Attaches an "anchor + payload" pair directly (**used by the annotation
     * front-end**, bypassing the DSL sugar below).
     *
     * The front-end (`MixinScanner`) already **compiles** annotations into anchor
     * and payload objects, so there is no need to come back through `atHead { … }`
     * sweetener — and combinations like `within`/`slice` are ready-made objects in
     * the front-end, so attaching directly is more straightforward and does not
     * re-interpret the semantics in two places.
     */
    fun point(anchor: InjectionPoint, payload: Payload) {
        points += anchor to payload
    }

    /** Before the first instruction of the method body (aligned with Mixin `HEAD`). */
    fun atHead(block: PayloadBuilder.() -> Unit) {
        points += at(InjectionPoint.Head) to payloadOf(block)
    }

    /**
     * Before the first instruction **after** `super()`/`this()` in a constructor
     * (aligned with Mixin `CTOR_HEAD`); for non-constructor methods it is
     * equivalent to [atHead].
     *
     * To reference `this` safely in a constructor (e.g. as a `This` argument),
     * use this — `this` is not yet initialized before `super()`.
     */
    fun atConstructorHead(block: PayloadBuilder.() -> Unit) {
        points += at(InjectionPoint.ConstructorHead) to payloadOf(block)
    }

    /**
     * Before every return instruction (aligned with Mixin `RETURN`).
     *
     * @param ordinal only hit the Nth return (0-based); null = hit them all
     */
    fun atReturn(ordinal: Int? = null, block: PayloadBuilder.() -> Unit) {
        points += at(InjectionPoint.Return(ordinal)) to payloadOf(block)
    }

    /**
     * Before the **last** return instruction (aligned with Mixin `TAIL`).
     *
     * Use [atReturn] for "all returns".
     */
    fun atTail(block: PayloadBuilder.() -> Unit) {
        points += at(InjectionPoint.FinalReturn) to payloadOf(block)
    }

    /**
     * Before a method-call instruction (aligned with Mixin `INVOKE`).
     *
     * [opcode] selects one of `INVOKESTATIC` / the other three, used to **declare
     * whether this call is static or instance** — a value-rewriting instance
     * handler relies on it to judge "whether a receiver sits on the stack". Passing
     * null means uncertain, treated strictly as an **instance call** (better to
     * demand one extra receiver parameter than to inject onto a misaligned stack).
     */
    fun beforeCall(
        owner: String? = null,
        name: String,
        desc: String? = null,
        opcode: Int? = null,
        ordinal: Int? = null,
        block: PayloadBuilder.() -> Unit,
    ) {
        points += at(
            InjectionPoint.Call(
                owner,
                name,
                desc,
                after = false,
                ordinal = ordinal,
                isStatic = opcode.isStaticCall(),
            ),
        ) to
            payloadOf(block)
    }

    /** After a method-call instruction (aligned with the `INVOKE_ASSIGN` landing point). */
    fun afterCall(
        owner: String? = null,
        name: String,
        desc: String? = null,
        opcode: Int? = null,
        ordinal: Int? = null,
        block: PayloadBuilder.() -> Unit,
    ) {
        points += at(
            InjectionPoint.Call(
                owner,
                name,
                desc,
                after = true,
                ordinal = ordinal,
                isStatic = opcode.isStaticCall(),
            ),
        ) to
            payloadOf(block)
    }

    /** The tri-state conversion of `opcode` → [InjectionPoint.Call.isStatic]; null (not written) = uncertain. */
    private fun Int?.isStaticCall(): Boolean? = when (this) {
        null -> null
        OPCODE_INVOKESTATIC -> true
        else -> false
    }

    /**
     * Before a field-access instruction (aligned with Mixin `FIELD`). Passing one
     * of the four values like `Opcodes.GETFIELD` to [opcode] restricts the read/
     * write direction; pass null to leave it unrestricted; passing null for
     * owner/name/desc also leaves them unrestricted.
     */
    fun beforeField(
        owner: String? = null,
        name: String? = null,
        desc: String? = null,
        opcode: Int? = null,
        ordinal: Int? = null,
        block: PayloadBuilder.() -> Unit,
    ) {
        points += at(InjectionPoint.FieldAccess(owner, name, desc, opcode, after = false, ordinal = ordinal)) to
            payloadOf(block)
    }

    /** After a field-access instruction (after `GETFIELD` the stack holds the value just read). */
    fun afterField(
        owner: String? = null,
        name: String? = null,
        desc: String? = null,
        opcode: Int? = null,
        ordinal: Int? = null,
        block: PayloadBuilder.() -> Unit,
    ) {
        points += at(InjectionPoint.FieldAccess(owner, name, desc, opcode, after = true, ordinal = ordinal)) to
            payloadOf(block)
    }

    /**
     * Before a `new` instruction (aligned with Mixin `NEW`). Only "before" exists:
     * after `NEW` the stack holds an uninitialized reference, and injecting there
     * and passing it to a static method would directly cause a `VerifyError`.
     *
     * When [desc] is non-null, it additionally confirms that this `NEW` is followed
     * by an `<init>` call matching that constructor descriptor.
     */
    fun beforeNew(owner: String? = null, desc: String? = null, ordinal: Int? = null, block: PayloadBuilder.() -> Unit) {
        points += at(InjectionPoint.NewInstance(owner, desc, ordinal)) to payloadOf(block)
    }

    /**
     * Before a constant-loading instruction (aligned with Mixin `CONSTANT`).
     *
     * The type of [value] decides which constant kind to match (`Int`/`Long`/
     * `Float`/`Double`/`String`/`Type`, or `null` to match `ACONST_NULL`). The
     * short instruction forms (`ICONST_*`/`BIPUSH`/`LCONST_*`…) are matched as
     * well — otherwise something like `1` could never be hit.
     */
    fun beforeConstant(value: Any?, ordinal: Int? = null, block: PayloadBuilder.() -> Unit) {
        points += at(InjectionPoint.Constant(value, ordinal, after = false)) to payloadOf(block)
    }

    /**
     * **After** a constant-loading instruction (at that point the value is already
     * on the stack) — the landing point for [PayloadBuilder.modifyConstant].
     */
    fun afterConstant(value: Any?, ordinal: Int? = null, block: PayloadBuilder.() -> Unit) {
        points += at(InjectionPoint.Constant(value, ordinal, after = true)) to payloadOf(block)
    }

    /**
     * **After a local variable is written** (aligned with Mixin `STORE`) — a landing point for
     * [PayloadBuilder.modifyVariable]: the block lands after the write, so "read back, change,
     * write again" is naturally legal straight-line code.
     * `index`/`type`/`localOrdinal`/`argsOnly` stack up into a filter; none written means "every
     * write in the method" (almost always under-targeting; the validation layer warns), e.g.
     * `afterStore(index = 4, type = "I") { … }` or `afterStore(type = "Ljava/lang/String;", ordinal = 1) { … }`
     * (the 2nd String write).
     */
    fun afterStore(
        index: Int? = null,
        type: String? = null,
        localOrdinal: Int? = null,
        ordinal: Int? = null,
        argsOnly: Boolean = false,
        block: PayloadBuilder.() -> Unit,
    ) {
        points += at(InjectionPoint.Store(index, type, localOrdinal, ordinal, argsOnly)) to payloadOf(block)
    }

    /**
     * **Before a local variable is read** (aligned with Mixin `LOAD`) — another
     * landing point for [PayloadBuilder.modifyVariable].
     *
     * It changes "the value the upcoming read sees" (writing back to the same
     * variable), so every subsequent read in the method sees the new value.
     */
    fun beforeLoad(
        index: Int? = null,
        type: String? = null,
        localOrdinal: Int? = null,
        ordinal: Int? = null,
        argsOnly: Boolean = false,
        block: PayloadBuilder.() -> Unit,
    ) {
        points += at(InjectionPoint.Load(index, type, localOrdinal, ordinal, argsOnly)) to payloadOf(block)
    }

    /**
     * Call redirect: replaces the whole matched call instruction with a static
     * handler (descriptor identical to the original call, i.e. the handler is a
     * static mirror image of the original method); the original call no longer
     * executes. Constructor calls are rejected.
     */
    fun redirectCall(
        owner: String? = null,
        name: String,
        desc: String? = null,
        handlerOwner: String,
        handlerMethod: String,
    ) {
        points += at(InjectionPoint.Call(owner, name, desc, after = false)) to
            Payload.Redirect(handlerOwner, handlerMethod)
    }

    private fun payloadOf(block: PayloadBuilder.() -> Unit): Payload =
        PayloadBuilder(targetInternal).apply(block).build()
}

class PayloadBuilder @PublishedApi internal constructor(
    /** The target class's internal name, used by [mergedCall] to fill in the owner — a merged-form handler lives inside the target class. */
    @PublishedApi internal val targetInternal: String,
) {
    private var payload: Payload? = null

    /**
     * Calls a handler **that moved into the target class via class merge** (see
     * [ClassTargetBuilder.merge]). Differs from [handlerCall] in exactly one thing: the `owner` is
     * filled in from the target class — where the handler lives is decided by this rule itself,
     * and a hand-copied class name would only blow up at runtime. All other parameters mean the
     * same, and so does the parameter list (the receiver is not in it: after merging it is `this`).
     */
    fun mergedCall(
        method: String,
        desc: String,
        captures: List<DslValue> = emptyList(),
        variant: HandlerVariant = HandlerVariant.NOTIFY,
        kind: HandlerKind = HandlerKind.INJECT,
        index: Int = 0,
        slot: Int? = null,
    ) = handlerCall(targetInternal, method, desc, captures, variant, kind, index, slot)

    internal fun build(): Payload =
        payload ?: error("no payload defined at the injection point (write at least one of call/raw)")

    /** Inserts a call to a static method. The argument types must match [desc]'s parameters. */
    fun call(owner: String, method: String, desc: String, args: List<DslValue> = emptyList()) {
        payload = Payload.StaticCall(owner, method, desc, args)
    }

    /** Shorthand: calls a static method on OMLCore (the standard entry point for injecting back into OML). */
    fun omlCall(method: String, desc: String = "()V", args: List<DslValue> = emptyList()) =
        call("org/ohmyloader/core/OMLCore", method, desc, args)

    /**
     * Return-value transform (rewrite-level): at the return, feeds the on-stack
     * return value through a static handler and replaces the original value with
     * the handler's return value. [desc] is shaped `(R[, extras...]) R`, R = the
     * target method's return type.
     *
     * When the handler lives in the target class via class merge, use [handlerCall]
     * + [HandlerKind.MODIFY_RETURN] (`@ModifyReturnValue`) — both rewrite the same
     * value; the only difference is where the handler lives.
     */
    fun transformReturn(owner: String, method: String, desc: String, extras: List<DslValue> = emptyList()) {
        payload = Payload.TransformReturn(owner, method, desc, extras)
    }

    /**
     * Cancellable check: on a HEAD + void target method, calls a static handler
     * returning boolean; true short-circuits the whole method body. The engine
     * synthesizes an entry stack frame for the branch target.
     */
    fun cancellableCall(owner: String, method: String, desc: String, args: List<DslValue> = emptyList()) {
        payload = Payload.CheckCall(owner, method, desc, args)
    }

    /**
     * A cancellable check **carrying a return value**: HEAD + non-void target
     * method.
     *
     * The engine creates a `CallbackInfoReturnable`, calls the handler, and when
     * the handler cancels, returns directly using the value it set. [desc] is the
     * bridge method's descriptor, shaped `(captures…, id, L…CallbackInfoReturnable;)V`;
     * [bridgeArgs] is the part **the engine pushes onto the stack** (captured
     * arguments + id), and the trailing handle is pushed by the engine itself.
     */
    fun cancellableReturn(owner: String, method: String, desc: String, bridgeArgs: List<DslValue> = emptyList()) {
        payload = Payload.CancellableReturn(owner, method, desc, bridgeArgs)
    }

    /**
     * Directly calls an "instance handler merged into the target class" (entry-type or value-rewriting): `this` is the target instance,
     * instance methods only. [kind] picks the family: entry-type ([HandlerKind.INJECT]) takes a callback handle at its tail; value-rewriting
     * (`MODIFY_*` / `REDIRECT`) wants no handle — the handler's return value is the conclusion. `MODIFY_RETURN` / `MODIFY_EXPR_VALUE` rewrite
     * the value the anchor **produces** (return value / expression result): the anchor must sit where the value already exists (`atReturn` /
     * `afterCall` / `afterField` / `afterConstant`), handler `(T[, captures…])T`, `T` decided by the anchor instruction (see `ProducedValue`).
     * In both families the **receiver is not in the parameter list** (after merging, `this` is the target instance); the sole exception is
     * `REDIRECT`, whose first parameter is the replaced call's receiver.
     */
    fun handlerCall(
        owner: String,
        method: String,
        desc: String,
        captures: List<DslValue> = emptyList(),
        variant: HandlerVariant = HandlerVariant.NOTIFY,
        kind: HandlerKind = HandlerKind.INJECT,
        index: Int = 0,
        slot: Int? = null,
    ) {
        payload = Payload.HandlerCall(owner, method, desc, captures, variant, kind, index, slot)
    }

    /** Escape hatch: hand-build instructions (receiver is `InsnList`; parameters are the target method and the owning class's internal name, usable to synthesize frames). */
    fun raw(block: InsnList.(MethodNode, String) -> Unit) {
        payload = Payload.Raw { list, method, owner -> list.block(method, owner) }
    }

    /**
     * **Rewrite one argument in place** (aligned with Mixin `@ModifyArg`).
     *
     * The anchor must be a method call (`beforeCall`/`afterCall` both work; the
     * engine rewrites per the stack state before the call instruction). It goes
     * through local variables and is **zero-boxing**; the handler shape is in
     * [Payload.ModifyArg].
     */
    fun modifyArg(
        owner: String,
        method: String,
        desc: String,
        index: Int,
        extras: List<DslValue> = emptyList(),
    ) {
        payload = Payload.ModifyArg(owner, method, desc, index, extras)
    }

    /**
     * **Fetch all arguments at once and rewrite them** (aligned with Mixin
     * `@ModifyArgs`).
     * The handler is shaped `(Args, extras…)V`; note it **boxes** (see the `Args`
     * documentation).
     */
    fun modifyArgs(owner: String, method: String, desc: String, extras: List<DslValue> = emptyList()) {
        payload = Payload.ModifyArgs(owner, method, desc, extras)
    }

    /**
     * **Rewrite a constant** (aligned with Mixin `@ModifyConstant`).
     *
     * The anchor must be `afterConstant(...)` (only after the constant is loaded
     * is the value on the stack); the handler is shaped `(T, extras…)T`, and `T`
     * must equal that constant's type (decided by the anchor instruction; a
     * mismatch errors directly at injection time).
     */
    fun modifyConstant(owner: String, method: String, desc: String, extras: List<DslValue> = emptyList()) {
        payload = Payload.ModifyConstant(owner, method, desc, extras)
    }

    /**
     * **Rewrite "the value the anchor produces"** (aligned with Mixin
     * `@ModifyExpressionValue`).
     *
     * The anchor must be `afterCall` / `afterField` / `afterConstant` (the value is
     * on the stack only after being produced); the handler is shaped
     * `(T[, extras…])T`, where `T` is the type that instruction produces.
     */
    fun modifyExpressionValue(
        owner: String,
        method: String,
        desc: String,
        extras: List<DslValue> = emptyList(),
    ) {
        payload = Payload.ModifyExpressionValue(owner, method, desc, extras)
    }

    /**
     * **Rewrite a local variable** (aligned with Mixin `@ModifyVariable`).
     *
     * The anchor must be [MethodRuleBuilder.afterStore] / [MethodRuleBuilder.beforeLoad];
     * the handler is shaped `(T[, extras…])T`, where `T` is that local variable's
     * type.
     */
    fun modifyVariable(owner: String, method: String, desc: String, extras: List<DslValue> = emptyList()) {
        payload = Payload.ModifyVariable(owner, method, desc, extras)
    }

}

// ---------- Anchor **values** (as opposed to the "inject here" methods on the builders) ----------

/**
 * Anchor value: used to build the bounds of [MethodRuleBuilder.within].
 *
 * The same-named methods on the builders mean "**inject at this position**" (and
 * concomitantly produce a payload); the ones here mean "**treat this position as a
 * bound**".
 */
object Anchor {
    val HEAD: InjectionPoint = InjectionPoint.Head
    val CTOR_HEAD: InjectionPoint = InjectionPoint.ConstructorHead
    val TAIL: InjectionPoint = InjectionPoint.FinalReturn

    fun returnAt(ordinal: Int? = null): InjectionPoint = InjectionPoint.Return(ordinal)

    fun call(
        owner: String? = null,
        name: String? = null,
        desc: String? = null,
        after: Boolean = false,
        ordinal: Int? = null,
    ): InjectionPoint = InjectionPoint.Call(owner, name, desc, after, ordinal)

    fun field(
        owner: String? = null,
        name: String? = null,
        desc: String? = null,
        opcode: Int? = null,
        after: Boolean = false,
        ordinal: Int? = null,
    ): InjectionPoint = InjectionPoint.FieldAccess(owner, name, desc, opcode, after, ordinal)

    fun newInstance(owner: String? = null, desc: String? = null, ordinal: Int? = null): InjectionPoint =
        InjectionPoint.NewInstance(owner, desc, ordinal)

    fun constant(value: Any?, ordinal: Int? = null): InjectionPoint = InjectionPoint.Constant(value, ordinal)

    /** A local variable's write point (for `within` bounds). */
    fun store(
        index: Int? = null,
        type: String? = null,
        localOrdinal: Int? = null,
        ordinal: Int? = null,
        argsOnly: Boolean = false,
    ): InjectionPoint = InjectionPoint.Store(index, type, localOrdinal, ordinal, argsOnly)

    /** A local variable's read point (for `within` bounds). */
    fun load(
        index: Int? = null,
        type: String? = null,
        localOrdinal: Int? = null,
        ordinal: Int? = null,
        argsOnly: Boolean = false,
    ): InjectionPoint = InjectionPoint.Load(index, type, localOrdinal, ordinal, argsOnly)
}

// ---------- Constructor for reading local variables (data-flow analysis) ----------

/**
 * A local variable at the injection point ([DslValue.Local]) — pushed as an argument. Forms:
 * `local(index = 3, type = "I")` (slot 3, verified int), `local(index = 3)` (type inferred from
 * data flow), `local(type = "Ljava/lang/String;")` (the 1st String local, slot not hardcoded),
 * `local(type = "I", ordinal = 1)` (the 2nd int local).
 * vs `DslValue.Arg(i)`: `Arg` fetches **declared method parameters** only and needs the target
 * descriptor hardcoded; this function faces slots directly and reaches locals computed mid-body.
 * Why not "by variable name": see [DslValue.Local].
 */
fun local(index: Int? = null, type: String? = null, ordinal: Int = 0, strict: Boolean = false): DslValue =
    DslValue.Local(index, type, ordinal, strict)

/**
 * Entry point of the injection DSL: declares a batch of "what changes to make to which class"
 * rules and produces a [RuleSet]. Two kinds of rules coexist on one class:
 * **method-body injection** (`method(…) { atHead { … } }` — insert code at a position of the
 * target method) and **access-flag rewriting** (`access` / `field(…)` / `methodAccess(…)` — only
 * visibility and `final`, touching no instruction).
 * Member names are used as-is (26.x jars ship readable names); a method selector can carry several
 * aliases, so the same rule hits a member under any of its declared names. Rules are only data:
 * anchor resolution, validation, and bytecode construction are the engine's job.
 */
fun injection(block: InjectionBuilder.() -> Unit): RuleSet {
    val builder = InjectionBuilder()
    builder.block()
    return RuleSet(builder.classRules, builder.merges)
}
