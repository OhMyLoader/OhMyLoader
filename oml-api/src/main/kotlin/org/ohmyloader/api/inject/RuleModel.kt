package org.ohmyloader.api.inject

import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.MethodNode

/**
 * The injection rule model: **anchor** (where to insert) + **payload** (what to insert) + **value** (where arguments come from). These types are the **single representation** of a rule — the annotation front-end (`org.ohmyloader.api.mixin`) only translates rules into these objects, so the field semantics are the engine's contract; changing them changes the rule language.
 * The namespace holds **the target version's readable names** (26.x jars ship class and member names unobfuscated), not obfuscated ones; all descriptors are JVM descriptor strings. The only ASM touchpoint is [Payload.Raw] (an escape hatch; whoever uses it is already writing ASM).
 * Boundary: this package is data plus "questions the data answers itself" (e.g. [InjectionPoint.isMethodHead]); anchor resolution, validation, and bytecode emission need the target class and live in the loader. No name mapping happens here — rule names are the target version's names.
 */

/** Method selector: a match succeeds when any of [names] matches; [desc] being null matches any descriptor. */
data class MethodSelector(val names: Set<String>, val desc: String?) {
    fun matches(name: String, desc: String): Boolean =
        name in names && (this.desc == null || this.desc == desc)

    fun describe(): String =
        "method(${names.joinToString("|")}${this.desc?.let { ", desc=$it" } ?: ""})"
}

/**
 * The injection rule itself is wrong (the kind determinable statically at
 * startup). Throwing it fails this class load.
 *
 * Better to fail loudly at startup than to have "changed something incorrectly
 * without crashing, just behaving wrongly" — the latter is this engine's most
 * dangerous failure mode.
 */
class InjectionError(message: String) : RuntimeException(message)

/**
 * The injection anchor type.
 *
 * Names and semantics are **aligned with Mixin's `@At` codes**: a given `@At`
 * form must behave identically on OML. See each entry's comment for a one-to-one
 * comparison.
 */
sealed class InjectionPoint {

    /**
     * Whether the injection block is inserted **after** the anchor instruction
     * (default: before).
     *
     * Answered by the anchor itself, rather than the engine `when`ing over it —
     * "insert after" holds for both calls and field accesses, and new anchors
     * require no engine changes.
     */
    open val after: Boolean get() = false

    /**
     * The anchor that, once the [Sliced] shell is stripped away, truly decides
     * "where to insert".
     *
     * Payload validation (which payloads may only land on entry/return anchors)
     * looks at the **core**, not the shell — otherwise
     * `within { atHead { … } }` would be misjudged as "not an entry anchor".
     */
    open val core: InjectionPoint get() = this

    /**
     * Whether this is a "method entry" anchor ([Head], or [ConstructorHead] for
     * constructors).
     *
     * Short-circuiting payloads (`CheckCall` / `CancellableReturn`) may only land
     * on these two kinds: both must decide "whether to run / what to return"
     * before the method body executes. Note that **only [ConstructorHead] may be
     * used inside constructors** — returning early before `super()` is illegal.
     */
    fun isMethodHead(): Boolean = core is Head || core is ConstructorHead

    /**
     * Whether this is a "return-type" anchor ([Return] / [FinalReturn]).
     *
     * Payloads that rewrite the return value (`TransformReturn`) may only land on
     * such anchors — they rely on the precondition that a return value is already
     * on the stack.
     */
    fun isReturnAnchor(): Boolean = core is Return || core is FinalReturn

    /** Before the first instruction of the method body (aligned with Mixin `HEAD`). */
    data object Head : InjectionPoint()

    /**
     * The first instruction after the "**delegate call**" in a constructor (aligned with Mixin
     * `CTOR_HEAD`); for non-constructor methods it degrades to [Head].
     * The difference from [Head] is legality, not just location: before `super()`/`this()`, `this`
     * is not yet initialized, so pushing it for a static call produces illegal bytecode — use this
     * anchor to reference `This` safely inside a constructor.
     * Mixin's `CTOR_HEAD` also skips field initializers (`POST_MIXIN` semantics); merged members'
     * initial values live in `<clinit>`, which constructors do not run, so this anchor is
     * equivalent to Mixin's `POST_DELEGATE`.
     */
    data object ConstructorHead : InjectionPoint()

    /**
     * Before every return instruction (aligned with Mixin `RETURN`).
     *
     * Only matches **the target method's own return instructions** (an `I` method
     * recognizes only `IRETURN`), consistent with Mixin — otherwise an
     * unreachable other `xRETURN` in the body could be matched by mistake.
     *
     * [ordinal] selects the Nth match (0-based); null matches them all.
     */
    data class Return(val ordinal: Int? = null) : InjectionPoint()

    /**
     * Before the **last** return instruction (aligned with Mixin `TAIL`).
     *
     * Use [Return] for "all returns".
     */
    data object FinalReturn : InjectionPoint()

    /**
     * Before/after a method-call instruction (aligned with Mixin `INVOKE` /
     * `INVOKE_ASSIGN`).
     * Passing null for owner/name/desc means "no constraint"; [ordinal] selects
     * the Nth match.
     */
    data class Call(
        val owner: String?,
        val name: String?,
        val desc: String?,
        override val after: Boolean,
        val ordinal: Int? = null,
        /**
         * Whether it is a **static call** (`INVOKESTATIC`): "whether an instance receiver counts
         * toward the parameter list" depends entirely on it. `null` = not declared, treated
         * strictly as "possibly an instance call" — a handler that includes the receiver merely
         * gets one extra parameter on a static call, whereas omitting it misaligns the stack and
         * only blows up at runtime.
         */
        val isStatic: Boolean? = null,
    ) : InjectionPoint() {
        /** True only when known to be a static call (`null` counts as "uncertain", treated strictly as an instance call). */
        fun isStaticCall(): Boolean = isStatic == true
    }

    /**
     * Before/after a field-access instruction (aligned with Mixin `FIELD`).
     * [opcode] restricts to one of the four accesses (`GETFIELD`/`PUTFIELD`/`GETSTATIC`/
     * `PUTSTATIC`); null [owner]/[name]/[desc]/[opcode] = unrestricted.
     * Mixin's `array=`/`fuzz=` array-access sub-feature is **not** implemented (a separate fuzzy
     * search of array opcodes near a field access, unrelated to the anchor itself).
     */
    data class FieldAccess(
        val owner: String?,
        val name: String?,
        val desc: String?,
        val opcode: Int?,
        override val after: Boolean,
        val ordinal: Int? = null,
    ) : InjectionPoint()

    /**
     * Before a `new` instruction (aligned with Mixin `NEW`). Only "before" exists: after `NEW` the
     * stack holds a **not-yet-initialized** reference, and injecting there and passing it to a
     * static method is a direct `VerifyError`.
     * A non-null [desc] additionally confirms this `NEW` is followed by an `<init>` call matching
     * that descriptor (nested `new`s may sit between `NEW` and `<init>`, so it scans by depth).
     */
    data class NewInstance(
        val owner: String?,
        val desc: String?,
        val ordinal: Int? = null,
    ) : InjectionPoint()

    /**
     * Before a constant-loading instruction (aligned with Mixin `CONSTANT`). The type of [value]
     * picks the kind: `Int` / `Long` / `Float` / `Double` / `String` / `org.objectweb.asm.Type`,
     * or `null` (matches `ACONST_NULL`).
     * Besides `LDC`, the short instruction forms (`ICONST_M1..5`/`BIPUSH`/`SIPUSH`/`LCONST_0/1`/
     * `FCONST_*`/`DCONST_*`) match too — javac does not emit `LDC` for `1`, so something like
     * `if (x == 1)` is otherwise never found.
     */
    data class Constant(
        val value: Any?,
        val ordinal: Int? = null,
        /**
         * `true` = insert **after** the constant-loading instruction (at that
         * point the value is already on the stack, as `ModifyConstant` requires);
         * default `false` = before loading (use this for "observe this constant
         * was seen").
         */
        override val after: Boolean = false,
    ) : InjectionPoint()

    /**
     * After a local variable is **written** (aligned with Mixin `STORE`) — [ModifyVariable]'s anchor: the block lands after the write
     * instruction, so "read back, change, write again" is naturally legal straight-line code ([after] is always true).
     * Locating "which variable": `index` (slot; `long`/`double` take two) / `type` (the value's JVM descriptor) / `localOrdinal` (n-th
     * among slots matching `type`, slot-ascending) / `argsOnly` (parameter slots only) stack up into a filter, and [ordinal] ("which
     * write") layers on top; none written = "every write", which the validation layer warns about as under-targeting.
     * The type filter reads the **top-of-stack type** (data-flow, path-independent), degrading to instruction kind (`ISTORE` → int family,
     * `ASTORE` → reference) when analysis is unavailable; `type = "Ljava/lang/Object;"` matches any reference/array — what Mixin's
     * `Object`-typed `@ModifyVariable` intends.
     */
    data class Store(
        val index: Int? = null,
        val type: String? = null,
        val localOrdinal: Int? = null,
        val ordinal: Int? = null,
        val argsOnly: Boolean = false,
    ) : InjectionPoint() {
        /** **After** the write instruction — the value is already in the variable. */
        override val after: Boolean get() = true
    }

    /**
     * Before a local variable is **read** (aligned with Mixin `LOAD`).
     *
     * The fields mean exactly what they mean in [Store], except "which
     * occurrence" counts **reads** rather than writes. When `ModifyVariable` lands
     * "before the read", it changes **what the upcoming read sees** (writing back
     * to the same variable), so every subsequent read in the method sees the new
     * value.
     */
    data class Load(
        val index: Int? = null,
        val type: String? = null,
        val localOrdinal: Int? = null,
        val ordinal: Int? = null,
        val argsOnly: Boolean = false,
    ) : InjectionPoint()

    /**
     * Wraps any anchor in a **search window** (one window here, unlike Mixin's slice chain): finds
     * [inner] only strictly between [from] and [to] — both ends excluded; [from] takes its **last**
     * match as the window start, [to] its **first** as the end.
     * A bound that resolves nothing means **no injection** and the reason is printed — ignoring it
     * would degrade to whole-method scope, exactly what `within` avoids. E.g.
     * `within(from = Anchor.call(name = "begin"), to = Anchor.call(name = "end")) { atReturn { … } }`
     * injects only on the returns between the two calls.
     */
    data class Sliced(
        val inner: InjectionPoint,
        val from: InjectionPoint?,
        val to: InjectionPoint?,
    ) : InjectionPoint() {
        override val after: Boolean get() = inner.after
        override val core: InjectionPoint get() = inner.core
    }
}

/** An argument that can be injected into a payload call, pushed in declaration order. */
sealed class DslValue {
    /** The current instance (`this`; only available in instance methods — static methods are skipped with a warning). */
    data object This : DslValue()

    /**
     * The [index]-th method parameter (0-based); the slot is computed automatically
     * from the method descriptor.
     *
     * Can only express the target method's **declared parameters**, and the target
     * descriptor must be written literally. For local variables computed in the
     * body, or to avoid hardcoding a slot, use [Local].
     */
    data class Arg(val index: Int) : DslValue()

    /**
     * A **local variable** at the injection point (via data-flow analysis). [index] alone = slot **assertion** — contradicting the
     * data-flow-computed state fails hard with [InjectionError]; [type] alone = the engine locates the `ordinal`-th local of that type
     * (the int family Z/B/C/S/I counts as one kind); not found → the injection is skipped with the reason printed, hit count guarded by
     * `require`/`expect` — "a rule matches several overloads, only some of which have the local" is a legal pattern.
     * Or both (verify). [strict] turns the by-type failure into a hard error (Mixin's `CAPTURE_FAILHARD`; default soft, and writing
     * [index] is already strict). Located **by type, never by variable name**: a mangled jar's `LocalVariableTable` is only sometimes
     * present and its names are mangled. vs [Arg]: `Arg` fetches a **declared parameter** and needs the target descriptor hardcoded;
     * [Local] faces the injection-point slot, reaching locals computed mid-body without hardcoding the target overload.
     */
    data class Local(
        val index: Int? = null,
        val type: String? = null,
        val ordinal: Int = 0,
        val strict: Boolean = false,
    ) : DslValue()

    data class IntVal(val value: Int) : DslValue()
    data class LongVal(val value: Long) : DslValue()
    data class Str(val value: String) : DslValue()

    /** A class constant (LDC org/objectweb/asm/Type). */
    data class Cls(val internalName: String) : DslValue()

    data object Null : DslValue()
}

/** An injection payload. */
sealed class Payload {
    /**
     * Inserts an INVOKESTATIC. The types of [args] must match the parameters of
     * [desc]; this is the caller's responsibility to guarantee.
     */
    data class StaticCall(
        val owner: String,
        val method: String,
        val desc: String,
        val args: List<DslValue>,
    ) : Payload()

    /**
     * Return-value transform (rewrite-level): the anchor is a return instruction.
     * A return value R is already on the stack; the engine pushes [extras] after
     * it and calls a static handler shaped `(R[, extras...]) R`, replacing R with
     * its return value. That is, "the original return value, processed by the
     * handler, is then returned".
     */
    data class TransformReturn(
        val owner: String,
        val method: String,
        val desc: String,
        val extras: List<DslValue>,
    ) : Payload()

    /**
     * Call redirect (rewrite-level): the anchor is the matched method-call
     * instruction, and the entire instruction is replaced by a call to the static
     * [handlerOwner].[handlerMethod] (descriptor identical to the original call,
     * i.e. the handler is a static mirror image of the original method). The
     * original call no longer executes.
     */
    data class Redirect(
        val handlerOwner: String,
        val handlerMethod: String,
    ) : Payload()

    /**
     * Cancellable check (rewrite-level): HEAD anchor only, and the target method
     * must be void. At the method head it calls a static handler shaped
     * `(...args)Z`; returning true short-circuits the whole method body with an
     * immediate `RETURN` (the engine synthesizes an entry stack frame for the
     * branch target).
     */
    data class CheckCall(
        val owner: String,
        val method: String,
        val desc: String,
        val args: List<DslValue>,
    ) : Payload()

    /**
     * A cancellable check **carrying a return value**: HEAD anchor only, and the target method
     * must be **non-void**. Calls a static handler shaped `(...args)Z`; on cancel, the engine
     * returns the value from a `CallbackInfoReturnable` handle it created itself — the engine must
     * read back `canceled` and the return value in the same bytecode segment, so the handle lives
     * in a local slot at the injection point (a void target uses [CheckCall]'s plain `RETURN`).
     * [bridgeArgs] are **all arguments the engine pushes onto the stack** (captures + handler id),
     * matching [desc]'s parameters one-to-one; the trailing handle parameter of [desc] is not part
     * of [bridgeArgs] — the engine `ALOAD`s it last.
     */
    data class CancellableReturn(
        val owner: String,
        val method: String,
        val desc: String,
        val bridgeArgs: List<DslValue>,
    ) : Payload()

    /**
     * **Rewrites one argument in place** (aligned with Mixin `@ModifyArg`). The anchor must be a **method call** ([Call]). The engine
     * stores the call's arguments in reverse order into temporary locals (the receiver stays on the stack, the argument slots empty),
     * edits the [index]-th one, and pushes them back — straight-line code, **zero boxing**.
     * Handler shapes (decided by [desc]'s parameter count after removing [extras]): **single-argument** `(T, extras…)T`, `T` = the
     * [index]-th argument's type; **multi-argument** `(T0, T1, …, extras…)T` mapping one-to-one to all arguments (so siblings are visible),
     * return type `T` = the [index]-th argument's type. `index = -1` auto-locates by return type (exactly one matching argument; zero or
     * several error).
     */
    data class ModifyArg(
        val owner: String,
        val method: String,
        val desc: String,
        val index: Int,
        val extras: List<DslValue> = emptyList(),
    ) : Payload()

    /**
     * **Fetches all arguments at once and rewrites them** (aligned with Mixin `@ModifyArgs`). The
     * anchor must be a method call; the engine boxes the arguments into an `Object[]` wrapped in
     * [org.ohmyloader.api.mixin.Args] and hands it to the handler, then unboxes each item per the
     * **call-site declared type** and pushes it back. This path necessarily boxes — use
     * [ModifyArg] on hot paths. Handler shape `(Args, extras…)V`.
     */
    data class ModifyArgs(
        val owner: String,
        val method: String,
        val desc: String,
        val extras: List<DslValue> = emptyList(),
    ) : Payload()

    /**
     * **Rewrites a constant** (aligned with Mixin `@ModifyConstant`). The anchor must be an
     * [`InjectionPoint.Constant`] and **must be `after`** (`afterConstant(...)`) — only after the
     * load is the value on the stack for the handler to rewrite.
     * Handler shape `(T, extras…)T`, `T` = the constant's type, given by the anchor's loading
     * instruction and checked item-by-item against the handler's declaration (see
     * `ProducedValue`) — a mismatch errors immediately at injection time instead of surfacing as
     * a `VerifyError` when the class is defined.
     */
    data class ModifyConstant(
        val owner: String,
        val method: String,
        val desc: String,
        val extras: List<DslValue> = emptyList(),
    ) : Payload()

    /**
     * **Rewrites "the value the anchor produces"** (aligned with Mixin
     * `@ModifyExpressionValue`). The anchor can be any value-producing expression — non-void call,
     * field read (`GETFIELD`/`GETSTATIC`), constant load — and must sit where the value has
     * already been produced (`afterCall` / `afterField` / `afterConstant`). The whole expression
     * still runs; only the value it leaves behind is replaced (vs [Redirect], which replaces the
     * whole call, arguments included). Handler shape `(T, extras…)T`, `T` decided by that
     * instruction; a mismatch errors immediately at injection time.
     */
    data class ModifyExpressionValue(
        val owner: String,
        val method: String,
        val desc: String,
        val extras: List<DslValue> = emptyList(),
    ) : Payload()

    /**
     * **Rewrites a local variable** (aligned with Mixin `@ModifyVariable`). The anchor must be an [`InjectionPoint.Store`] or
     * [`InjectionPoint.Load`]; the handler is shaped `(T[, captures…])T` and the engine synthesizes `LOAD slot → captures → INVOKESTATIC
     * handler → STORE slot` — no stack juggling needed, because the changed value lives in that one local (contrast [ModifyArg], where the
     * argument may sit mid-stack). "Which variable / which occurrence" is decided by the **anchor**; the payload only decides "change it
     * to what" — one set of semantics shared by the annotation and DSL forms.
     * The value's type is declared by the **handler's return type** (picks `ILOAD`/`LLOAD`/`FLOAD`/`DLOAD`/`ALOAD`), so a handler returning
     * `Object` only serves reference-typed locals. **Never** use [DslValue.Local] to fetch the slot currently being rewritten — it reads
     * the stale value not yet written back.
     */
    data class ModifyVariable(
        val owner: String,
        val method: String,
        val desc: String,
        val extras: List<DslValue> = emptyList(),
    ) : Payload()

    /**
     * **Directly calls a handler "merged into the target class"** (true Mixin `this` semantics): the handler is carried in via
     * **class merge**, so `this` is the target instance (`@Shadow` fields genuinely readable) and the injection point is a plain
     * `INVOKEVIRTUAL` — no registry, bridge, or reflection. A static target method is rejected: there is no `this` to pass.
     * Entry-type variants ([variant]): `NOTIFY` takes `(captures…)CallbackInfo` and reads nothing back; `CANCELLABLE` reads the cancel
     * flag and `RETURN`s short (void target); `RETURNABLE` takes a `CallbackInfoReturnable` and short-circuits with the handle's zero/set
     * value — [desc] still returns `V`: the value comes **from the handle**, not the handler's return. The **engine** creates the handle,
     * since the same bytecode must read it back after the call (as in [CancellableReturn]); value-rewriting kinds ([kind]) want no handle —
     * the handler's return value is the conclusion.
     */
    data class HandlerCall(
        val owner: String,
        val method: String,
        val desc: String,
        val captures: List<DslValue> = emptyList(),
        val variant: HandlerVariant = HandlerVariant.NOTIFY,
        /**
         * The variant family: the three entry-type forms are in [HandlerVariant];
         * the value-rewriting forms are in [HandlerKind].
         *
         * Value-rewriting kinds **want no handle** — the handler's return value is
         * the conclusion ([variant] uses [HandlerVariant.NOTIFY], and [desc]'s last
         * parameter is not a callback handle). Each value-rewriting kind's own
         * discriminator (which argument / which slot) is carried by [index] and
         * [slot].
         */
        val kind: HandlerKind = HandlerKind.INJECT,
        /** [HandlerKind.MODIFY_ARG]: which argument to rewrite (0-based; aligned with `@ModifyArg(index)`). */
        val index: Int = 0,
        /** [HandlerKind.MODIFY_VAR]: the slot to rewrite; `null` = let the engine find by type (aligned with Mixin's `ordinal`). */
        val slot: Int? = null,
    ) : Payload()

    /**
     * Escape hatch: build the instruction sequence directly; [method] provides
     * the target-method context, and [owner] is the owning class's internal name.
     *
     * The parameters are ASM's instruction list and method node — using this
     * requires importing ASM yourself. The engine only inserts this sequence at
     * the anchor and performs no static validation (other payloads surface issues
     * at startup; this one is entirely the responsibility of whoever writes it).
     */
    data class Raw(val build: (InsnList, MethodNode, String) -> Unit) : Payload()
}

/** The three **entry-type** variants of [Payload.HandlerCall] (see its documentation). */
enum class HandlerVariant {
    /** Notify only: the handler takes a `CallbackInfo` and nothing is read back. */
    NOTIFY,

    /** Cancellable (void target): reads back `CallbackInfo.canceled`; if true, short-circuits. */
    CANCELLABLE,

    /** Cancellable + specifiable return value (non-void target): the handle becomes a `CallbackInfoReturnable`. */
    RETURNABLE,
}

/**
 * The **variant family** of [Payload.HandlerCall]: the first three are **entry-type** (the handler
 * takes a callback handle, subdivided by [HandlerVariant]); the rest are **value-rewriting** — no
 * handle, the handler's **return value** is "what to change it to", matching the external static
 * handler shapes (e.g. [Payload.ModifyArg]). The split is structural in the engine: entry-type
 * builds a handle and may read back short-circuiting; value-rewriting rewrites in place, merely
 * swapping `INVOKESTATIC bridge` for `INVOKEVIRTUAL target class` (`this` = target instance).
 */
enum class HandlerKind {
    /** `@Inject`: the handler's tail takes a `CallbackInfo` / `CallbackInfoReturnable`. */
    INJECT,

    /** `@Redirect`: replaces the whole call with the handler's call (in instance form the receiver is a parameter too). */
    REDIRECT,

    /** `@ModifyArg`: rewrites the [Payload.HandlerCall.index]-th argument, handler `(T…)T`. */
    MODIFY_ARG,

    /** `@ModifyArgs`: fetches all arguments at once, handler `(Object[])V` (instance form uses the bare array, no `Args` handle). */
    MODIFY_ARGS,

    /** `@ModifyConstant`: rewrites a constant, handler `(T)T`. */
    MODIFY_CONST,

    /** `@ModifyVariable`: rewrites a local variable, handler `(T…)T`. */
    MODIFY_VAR,

    /** `@ModifyReturnValue`: rewrites the target method's **return value**, handler `(R[, captures…])R`, R = the method's return type. */
    MODIFY_RETURN,

    /**
     * `@ModifyExpressionValue`: rewrites the value **produced** by the anchor
     * expression, handler `(T[, captures…])T`.
     *
     * `T` is decided by the anchor instruction (call return type / field type /
     * constant type; see `ProducedValue`), and the anchor must sit where the value
     * has already been produced.
     */
    MODIFY_EXPR_VALUE,
}
