package org.ohmyloader.api.hook

import kotlin.reflect.KClass

/**
 * Declares a **hook carrier**: methods carrying [Inject] / [Redirect] / `@Modify*` annotations are
 * compiled into injection rules and woven in when the target class loads; [target] is the mapped
 * fully qualified name. Every handler is dispatched through the engine's generated bridge —
 * `this` inside the handler is the carrier instance, **never** the target object. The bridge calls
 * the handler reflectively (a capture array and a [HookContext] per call); on per-frame hot paths
 * prefer a [Merged] instance handler or a `@RuleSource` static call, which the injection point
 * invokes directly with no bridge or reflection.
 *
 * Need the target instance? Declare it as the handler's **first parameter, typed as the target
 * class** — the bridge passes it at the injection point and the shape is checked at startup:
 *
 * ```kotlin
 * @Hooked(target = "net.minecraft.world.item.ItemStack")
 * class ItemStackHooks {
 *     @Inject(method = "getTooltipLines", desc = "…", at = At(At.RETURN))
 *     fun onTooltip(self: ItemStack, lines: List<Component>, ci: HookContext) { … }
 * }
 * ```
 *
 * Merge-family annotations ([Borrowed]/[Extra]/[Replace]/[FieldAccess]/[MethodAccess]) belong to
 * [Merged] and are rejected here. A handler whose first parameter is the target type is also a
 * startup error on **static** targets and interface targets — there is no instance to pass.
 * Handlers never declare the receiver implicitly: [Inject] `(self?, captures…, HookContext)` (or
 * `HookValue` when cancelable/returning a value); Modify* `(self?, value[, captures…])T`;
 * [ModifyArgs] `(self?, Args)V`.
 * Hit counts are declared per annotation (Java annotations have no field inheritance) but share
 * one semantics: `require` = minimum (fewer throws), `allow` = maximum (more throws), `expect` =
 * warns; -1 = undeclared, where a no-hit only warns. A wrong rule silently does one thing less
 * instead of crashing — so write `require = 1` on must-hit rules.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Hooked(val target: String)

/**
 * Declares a **merge carrier**: the class's members merge into the target class when it loads —
 * [Borrowed] members reference the target's own, [Extra] members are new, [Replace] swaps a body,
 * [FieldAccess]/[MethodAccess] (on interface carriers) synthesize access to private members — and
 * **instance** handlers are invoked directly at the injection point (`this` = the target instance,
 * borrowed members directly readable, no bridging or reflection).
 *
 * This is the model whose `this` is the game object; it exists for the cases that need it —
 * reading the target's own members from a handler, replacing a body. Everything else belongs to
 * [Hooked], whose `this` never pretends to be the target.
 * [target] is the mapped fully qualified name. An interface target cannot take merged members —
 * the scanner rejects the carrier, naming them. Static handlers on a [Merged] carrier dispatch
 * through the bridge (same as [Hooked]).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Merged(val target: String)

/**
 * The injection location (aligned with Mixin's `@At`). Constants: [HEAD] first instruction; [CTOR_HEAD] after a constructor's `super()`/`this()` (equals [HEAD] elsewhere); [RETURN] every return; [TAIL] the last return; [INVOKE] / [INVOKE_ASSIGN] before / after a matched call; [FIELD] a field access; [NEW] a `new`; [JUMP] a conditional branch; [CONSTANT] a constant load ([args] writes the value); [STORE] / [LOAD] after a local write / before a local read.
 * [target]: `INVOKE`-family `"Lowner/name;member(args)ret"` (package dots allowed), `"member(args)ret"` or `"member"` (unrestricted); `FIELD` `"Lowner/name;field:desc"` / `"field:desc"` / `"field"`; `NEW` `"Lowner/name;"` or `"(args)V"`; `JUMP` the opcode name (`"IF_ICMPGE"`, …).
 * [args] (used by [CONSTANT]) takes `intValue` / `longValue` / `floatValue` / `doubleValue` / `stringValue` / `nullValue`. [ordinal] takes the Nth match (0-based; -1 = all); [opcode] restricts a field access or a jump to an `org.objectweb.asm.Opcodes` value (-1 = any).
 * [shift] is `""` / [SHIFT_BEFORE] / [SHIFT_AFTER] — Mixin's `Shift.BY` is unsupported (arbitrary instruction offsets drift as the target code changes); [slice] selects the window (see [Inject.slice]).
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class At(
    val value: String,
    val target: String = "",
    val args: Array<String> = [],
    val ordinal: Int = -1,
    val opcode: Int = -1,
    val shift: String = "",
    val slice: String = "",
    /** Selects which `@Expression` (by its `id`) an `EXPRESSION` anchor refers to. */
    val id: String = "",
) {
    companion object {
        const val HEAD = "HEAD"
        const val CTOR_HEAD = "CTOR_HEAD"
        const val RETURN = "RETURN"
        const val TAIL = "TAIL"
        const val INVOKE = "INVOKE"
        const val INVOKE_ASSIGN = "INVOKE_ASSIGN"
        const val FIELD = "FIELD"
        const val NEW = "NEW"

        /** A conditional branch (`IFEQ`…`IF_ACMPNE`, `IFNULL`/`IFNONNULL`); used with [WrapBranch]. */
        const val JUMP = "JUMP"

        /**
         * A **value-flow expression match** (see [Expression]): the handler's `@Expression` pattern is
         * matched against the target method. Valid on the value-consuming injectors
         * (`@ModifyExpressionValue`, `@WrapOperation`, `@WrapBranch`, …).
         */
        const val EXPRESSION = "EXPRESSION"

        const val CONSTANT = "CONSTANT"

        /** After a local variable is **written** (used with [ModifyVariable]). */
        const val STORE = "STORE"

        /** Before a local variable is **read** (used with [ModifyVariable]). */
        const val LOAD = "LOAD"

        /** No offset (default). */
        const val SHIFT_NONE = ""

        /** **Before** the matched instruction (equivalent to the default behavior). */
        const val SHIFT_BEFORE = "BEFORE"

        /** **After** the matched instruction (`INVOKE_ASSIGN` is the sugar for this). */
        const val SHIFT_AFTER = "AFTER"
    }
}

/**
 * The injection point's search window (aligned with Mixin's `@Slice`): search only **between**
 * [from] and [to], excluding both endpoints. [from] takes its **last** match as the start, [to]
 * its **first** as the end (the default behavior of `INVOKE:LAST` / `INVOKE:FIRST` in Mixin's
 * documentation). A bound that resolves nothing means **no injection** and the reason is printed —
 * ignoring a bound would silently widen the range to the whole method.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Slice(
    val from: At = At(At.HEAD),
    val to: At = At(At.TAIL),
    val id: String = "",
)

/**
 * Declares **additional interfaces the target class should implement** (OML's declarative
 * counterpart to Mixin's `@Implements`). Use it on a `@Merged` carrier or an interface carrier;
 * the interfaces' default methods become available on the target class and casts to the interface
 * succeed. There is no name prefixing: interfaces whose methods collide with target members rely on
 * the JVM's ordinary resolution.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Implements(vararg val value: KClass<*>)

/**
 * Clears `final` on the matching `@Borrowed` target field before merging, so merged methods and
 * injections can write it (a final field may otherwise only be assigned in its declaring class's
 * constructor). Valid only together with [Borrowed]; the field is still not merged in.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mutable

/**
 * Allows a handler parameter or return type to differ from the value's actual type (aligned with
 * MixinExtras' `@Coerce`): annotate the parameter to accept a reference **supertype** of the value,
 * and annotate the handler method to coerce the returned reference back to the value's type.
 * Reference coercion and primitive boxing/unboxing cover every value/operation injector, static and
 * merged: `@ModifyExpressionValue`, `@ModifyReturnValue`, `@ModifyArg` (single-argument mode),
 * `@ModifyConstant`, `@ModifyVariable`, `@ModifyReceiver`, `@Redirect`, `@WrapOperation`
 * (standard, `NEW` and comparison anchors), `@WrapWithCondition`, `@WrapBranch` and `@WrapMethod`.
 * `@Inject` captures and `@ModifyArgs` stay exact: captures are copies and the argument list is an
 * untyped `Args` handle.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Coerce

/**
 * A constant matcher (aligned with Mixin's `@Constant`), used in [ModifyConstant.constant].
 * **Only fields explicitly written count** (annotation defaults are not written into the
 * bytecode), so `@Constant(intValue = 0)` expresses "match 0" — the same approach Mixin uses.
 * [intValue] and [longValue] are different types: `1` does not match `1L`. [classValue] matches a
 * class literal (`LDC` of a `Class`).
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Constant(
    val nullValue: Boolean = false,
    val intValue: Int = 0,
    val longValue: Long = 0L,
    val floatValue: Float = 0f,
    val doubleValue: Double = 0.0,
    val stringValue: String = "",
    val classValue: KClass<*> = Any::class,
    val ordinal: Int = -1,
)

/**
 * Local variable capture modes (aligned with Mixin's `LocalCapture`):
 * - [NO_CAPTURE] (default): capture nothing — the handler parameter list can **only** be `(HookContext)` / `(HookValue)`;
 *   more parameters are an explicit startup-stage problem (same as Mixin).
 * - [CAPTURE_FAILSOFT]: parameters bind by **type order in the local variable table at the injection point**; unresolved skips the
 *   injection and prints the reason. - [CAPTURE_FAILHARD]: same, but unresolved is a hard failure ([InjectionError]).
 * - [PRINT]: print the parameters the handler should declare (the available locals are printed together on resolution failure).
 * OML never captures by variable name: a mangled jar's `LocalVariableTable` is only sometimes present and its names are themselves
 * mangled, so a fetch by name is impossible.
 */
enum class LocalCapture {
    NO_CAPTURE,
    PRINT,
    CAPTURE_FAILSOFT,
    CAPTURE_FAILHARD,
}

/**
 * Inserts a callback at the specified location (aligned with Mixin's `@Inject`). Handler shape `([captures…,] HookContext)` —
 * `HookContext` (void target) or `HookValue<T>` (non-void, `setReturnValue(v)` cancels and specifies the return value);
 * [methods] is the **union** of target method names.
 * [desc] empty matches **any overload**; when left empty, the [locals] capture is resolved at the injection point by data-flow analysis
 * over types — the static rule "i-th capture = i-th target parameter" needs the descriptor to compute slots.
 * [cancelable] short-circuits at the method-entry anchors (HEAD / CTOR_HEAD): a void target with `HookContext`, non-void with
 * `HookValue<T>`. A void target can also cancel right after a **void** call (`INVOKE_ASSIGN`, or `shift = AFTER`
 * with the call's descriptor written); other positions, and every anchor of a merged instance handler, degrade to
 * a notification.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Inject(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val cancelable: Boolean = false,
    val locals: LocalCapture = LocalCapture.NO_CAPTURE,
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Replaces a method call **in its entirety** with a call to the handler (aligned with Mixin's
 * `@Redirect`): the handler's parameter list must equal the replaced call's (for an instance call
 * the receiver is the **first** parameter, consistent with Mixin) and the return type must match;
 * the original call no longer executes.
 * [at]'s `target` **must** be written — the engine must fix the bridge method's descriptor at
 * startup; a missing one is an explicit startup-stage problem, not a silent no-op. The sole
 * exception is `@At("EXPRESSION")`: the captured call is only known at weaving time, so the
 * engine validates the handler shape against it there.
 * With `@At("NEW")` (write the constructor descriptor in `target`) the handler is a static factory
 * `(constructor args…)T` returning the constructed type; the standard `NEW; DUP; args; <init>`
 * shape is required.
 * An exception thrown by the handler is **not isolated**: this annotation replaces real behavior,
 * and swallowing the exception would mean the call silently never happened.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Redirect(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Rewrites in place one argument of a method call (aligned with Mixin's `@ModifyArg`). Handler
 * shape `(T)T`, `T` = the changed argument's type; [index] selects it (0-based), and `-1` (the
 * default) auto-locates by the return type — used when the call has exactly one argument whose
 * type equals the return type; zero or several report an error (guessing the wrong position is
 * far more dangerous than an error).
 * The anchor must be a method call (`@At(value = "INVOKE", target = "…")`) or a call captured
 * by `@At("EXPRESSION")`. The engine does not box: it stores the arguments into temporary locals
 * in reverse order, modifies one, pushes them back in order.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyArg(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val index: Int = -1,
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Gets **all of a call's arguments** at once and rewrites them (aligned with
 * Mixin's `@ModifyArgs`).
 *
 * The handler has the form `(Args)V`, where `args[0]` is the call's first argument — the receiver
 * is not part of the list. This path **necessarily boxes**, so on hot paths use [ModifyArg] instead.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyArgs(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Rewrites in place a **constant** (aligned with Mixin's `@ModifyConstant`).
 *
 * The handler has the form `(T)T`, where `T` is the constant's type
 * (`Int`/`Long`/`Float`/`Double`/`String`). Use [constant] to state which constant
 * to match (see [Constant]); if not written, all constants are matched.
 *
 * The anchor must land **after** the constant-loading instruction (the engine
 * chooses `after` itself), so the handler has a value to change.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyConstant(
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val constant: Constant = Constant(),
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Rewrites a **local variable** (aligned with Mixin's `@ModifyVariable`). Handler shape `(T[, captures…])T`: `T` is the local's type, the
 * return value is the changed value, and the engine synthesizes "read, change, write back" at the anchor (no boxing, no new stack frames).
 * The anchor must be [At.STORE] (after a write) or [At.LOAD] (before a read) — `@At` recognizes only these two (Mixin's stacked `@At`s are
 * not supported).
 * Discriminators layer into a filter: [index] = slot n, [ordinal] = the n-th among same-typed locals, [argsOnly] = only parameter slots;
 * unwritten = every read/write whose type equals `T` (the [ordinal] of `@At` itself means "which read/write" — a different thing).
 * **[name] is not supported**: a mangled jar's `LocalVariableTable` is only sometimes present and its names are mangled — writing it is a
 * startup-stage problem; use [index] or [ordinal].
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyVariable(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val index: Int = -1,
    val ordinal: Int = -1,
    val name: Array<String> = [],
    val argsOnly: Boolean = false,
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Rewrites the target method's **return value** (aligned with Mixin's `@ModifyReturnValue`).
 * Handler shape `(R)R`, `R` = the method's return type; the receiver is not written into the
 * parameter list. It moves into the target class along with the **class merge**, so `this` is the
 * target instance and `@Shadow` members are directly usable.
 * [at] defaults to `RETURN` (every return); `TAIL` changes only the last. A `void` target has no
 * return value to change — use [Inject] as the entry point. Unlike [ModifyVariable] (at =
 * `@At("RETURN")`), the handler shape is checked by return type for any return type; unlike
 * [Redirect], the replaced expression still runs, only the value it leaves behind is swapped.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyReturnValue(
    val at: At = At(At.RETURN),
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Rewrites **the value produced by some expression** (aligned with Mixin's `@ModifyExpressionValue`). Handler shape `(T)T`, `T` = the type
 * the anchor instruction produces (call return / field / constant type), checked item by item at injection time; it moves into the target
 * class along with the **class merge**, so `this` = the target instance.
 * The anchor must produce a value (`@At(value = At.INVOKE/FIELD/CONSTANT, …)`); the value is only on the stack after the instruction
 * finishes, so the front-end pins the anchor to "after" and no `shift` needs to be written.
 * vs [Redirect]: `Redirect` replaces the **entire call** (arguments included, the handler must perform the call itself); this swaps only
 * the result while the call still happens.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyExpressionValue(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Rewrites the **receiver** of an instance call or field access (aligned with MixinExtras' `@ModifyReceiver`).
 * Handler shape `(ReceiverType[, operands…])ReceiverType`: the first parameter and the return type are the
 * receiver type; for a method call the remaining value parameters are the call's arguments, for a field write
 * the field value, for a field read none. The original instruction still runs, on the receiver the handler
 * returns — unlike [Redirect], which replaces the whole call. The anchor must be an instance call (`INVOKE`,
 * before it) or `GETFIELD`/`PUTFIELD`; a static call or static field has no receiver and is rejected at
 * injection time. The engine does not box: receiver and operands go through temporary locals.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModifyReceiver(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Gates an operation on a boolean handler (aligned with MixinExtras' `@WrapWithCondition`): the handler
 * returns whether the original operation runs at all. Handler shape `(receiver?, operands…)Z` — the same
 * value parameters as [Redirect], but returning boolean instead of the operation's result. The anchor must
 * be a method call, a field write, an array creation or a `NEW`, before the instruction. A skipped array
 * creation or instantiation leaves `null`; a non-void call is accepted only when its result is
 * immediately discarded (`POP`/`POP2`).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WrapWithCondition(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Decides a **conditional branch** — OML's own landing point beyond MixinExtras. The anchor must be a
 * conditional jump (`@At(value = "JUMP", target = "IF_ICMPGE")` or `opcode = Opcodes.IF_ICMPGE`). The
 * values the jump compares are already on the operand stack; the handler is shaped
 * `(operands…)Z` and its return value is **whether the branch is taken** (true = jump to the original
 * target, false = fall through). The operands follow the opcode's stack form: `(I)` for the
 * `IFEQ`-family, `(II)` for `IF_ICMP*`, `(Object, Object)` for `IF_ACMP*`, `(Object)` for
 * `IFNULL`/`IFNONNULL`.
 *
 * This is the bytecode-level branch, not the source-level condition: `if (x > 10)` compiles to
 * "jump when `x <= 10`", so relocating the threshold means returning that inverted test. Exceptions
 * thrown by the handler are **not isolated** — it replaces real control flow.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WrapBranch(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Wraps an operation with an [org.ohmyloader.api.hook.Operation] callback (aligned with MixinExtras'
 * `@WrapOperation`): the handler receives the operation's receiver and operands plus an `Operation<R>`
 * as its **last non-sugar parameter**, and may re-run the original operation with different arguments,
 * not run it at all, or run it more than once. Handler return type = `R`. The anchor must be a call,
 * a field access, a `NEW`, a `CHECKCAST`/`INSTANCEOF` or an array instruction, before the instruction.
 * Exceptions thrown by the handler are **not isolated** (it replaces real behavior).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WrapOperation(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val slice: Slice = Slice(),
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Wraps the **whole selected method** (aligned with MixinExtras' `@WrapMethod`). The original body is
 * moved to a private synthetic method and the target becomes a wrapper that calls
 * `(params…, Operation<R>)R`; the handler may run the original body through
 * [org.ohmyloader.api.hook.Operation.call] with different arguments, or not at all. There is no `at`:
 * [method]/[methods] plus [desc] select the method. Constructors, abstract and native methods are
 * rejected. The wrap is applied after the method's ordinary injection rules, so those land in the moved
 * body.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class WrapMethod(
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val require: Int = -1,
    val expect: Int = -1,
    val allow: Int = -1,
)

/**
 * Declares the **expression** a handler is attached to (aligned with MixinExtras' `@Expression`):
 * a Java-like pattern matched against the target method's reconstructed value flow. Use it together
 * with `@At("EXPRESSION")` on the injector; [value] holds one or more alternative patterns (the
 * first that matches counts). Identifiers used by the pattern must be declared with [Definition].
 * A pattern is anchored by its *captures*: an explicit `@(…)` marks the part to inject at, and
 * without one the whole expression is the capture.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class Expression(val value: Array<String>, val id: String = "")

/** The repeatable container of [Expression] (Kotlin/Java compilers emit this for repeated uses). */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Expressions(val value: Array<Expression>)

/**
 * Declares an identifier used by an [Expression] (aligned with MixinExtras' `@Definition`).
 * [method] and [field] take the same strings as `@At(target = …)`; [type] takes classes referenced
 * by `instanceof` / casts / instantiations; [local] declares a local-variable identifier.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class Definition(
    val id: String,
    val method: Array<String> = [],
    val field: Array<String> = [],
    val type: Array<kotlin.reflect.KClass<*>> = [],
    val local: Array<DefinitionLocal> = [],
)

/** The repeatable container of [Definition]. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Definitions(val value: Array<Definition>)

/**
 * One local-variable identifier for [Definition] (aligned with MixinExtras' `Local` inside
 * `@Definition`): [index] pins the slot, otherwise the [ordinal]-th live local of [type] is used, and
 * [argsOnly] restricts the search to parameter slots. [print] logs the resolved slot and the locals
 * available at the matching instruction during weaving.
 */
@Retention(AnnotationRetention.RUNTIME)
annotation class DefinitionLocal(
    val index: Int = -1,
    val ordinal: Int = -1,
    val type: String = "",
    val argsOnly: Boolean = false,
    val print: Boolean = false,
)

/**
 * Targets the **synthetic method a lambda compiles to** instead of writing its generated name
 * (`lambda$tick$0`). Put it on the handler together with the injector annotation whose `method`
 * names the **enclosing** method: [interfaceType] is the lambda's functional interface and
 * [ordinal] picks the Nth matching lambda. Only lambdas whose implementation lives in the same class
 * are resolvable; a method reference to another class is not a lambda body.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Lambda(
    val interfaceType: kotlin.reflect.KClass<*>,
    val ordinal: Int = 0,
)

/**
 * Lets a `@WrapOperation` handler **cancel the target method** instead of only rewriting the operation:
 * the parameter must be `HookContext` for a void target or `HookValue<T>` for a non-void one, and must
 * be the **last** handler parameter. Calling `cancel()` / `setReturnValue(v)` returns from the target
 * method after the handler returns, with the handle's value.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Cancellable

/**
 * Binds a handler parameter to a **local variable at the injection point** (aligned with MixinExtras'
 * `@Local`). Supported on handler parameters of every method annotation: on `@Inject` the sugar sits
 * **before** the trailing `HookContext`/`HookValue`, on every other injector it is a **trailing suffix**
 * after the handler's ordinary parameters. The parameter type is the local's type. [index] pins the slot
 * (and asserts the dataflow type), [ordinal] selects the Nth local of the parameter's type (or [type]),
 * and an omitted [type] defaults to the parameter's own type. Locals are resolved by dataflow, never by
 * variable name, and a missing local is a hard error. A `LocalRef<T>` parameter is **mutable**: the engine
 * creates the ref at the injection point and writes its final value back to the local after the handler
 * returns, so anchor before the local's next read (for example `afterStore`); a value already pushed onto
 * the stack (e.g. as a call argument) is a copy and is not updated.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Local(
    val index: Int = -1,
    val ordinal: Int = -1,
    val type: String = "",
    val print: Boolean = false,
)

/**
 * Shares a mutable [org.ohmyloader.api.hook.LocalRef] between handlers of the **same target method
 * invocation** (aligned with MixinExtras' `@Share`). The parameter type must be `LocalRef<T>`; the ref is
 * created once when the method is entered (initial value `null`) and the same instance is passed to every
 * handler requesting the same [value] within [namespace] (which defaults to the carrier class). Handlers
 * earlier in the method can leave a value for later ones.
 *
 * Follows the same placement rule as [Local]: before the trailing handle on `@Inject`, a trailing suffix
 * elsewhere. The ref is per invocation and never written back to a local; do not store it.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Share(
    val value: String,
    val namespace: String = "",
)
