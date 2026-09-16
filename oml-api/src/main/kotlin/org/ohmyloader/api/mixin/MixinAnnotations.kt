package org.ohmyloader.api.mixin

/**
 * Declares an OML Mixin: methods carrying [Inject] / [Redirect] / `@Modify*` annotations are compiled into injection rules and woven in when the target class loads; [target] is the mapped fully qualified name.
 * Two models, decided by whether a merge annotation ([Shadow]/[Unique]/[Overwrite]/`@Accessor`/`@Invoker`) is declared: **injection** (default) calls a bridge method at the injection point, handler in the mixin class — `this` is the mixin instance, target members need reflection.
 * **Class merge** merges the members into the target class and the injection point calls the handler directly (`this` = target instance, `@Shadow` members visible, no bridging or reflection).
 * Handlers never declare the receiver (it is `this`): [Inject] `(captures…, CallbackInfo)` (or `CallbackInfoReturnable` when cancellable/returning a value); Modify* `(value[, captures…])T`; [ModifyArgs] `(Args[, captures…])V`.
 * [Redirect] alone takes the replaced call's receiver as first parameter — the handler's `this` is the target instance, the replaced call's receiver is another object.
 * Hit counts are declared per annotation (Java annotations have no field inheritance) but share one semantics: `require` = minimum (fewer throws), `allow` = maximum (more throws), `expect` = warns; -1 = undeclared, where a no-hit only warns.
 * A wrong rule silently does one thing less instead of crashing — so write `require = 1` on must-hit rules.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Mixin(val target: String)

/**
 * The injection location (aligned with Mixin's `@At`). Constants: [HEAD] first instruction; [CTOR_HEAD] after a constructor's `super()`/`this()` (equals [HEAD] elsewhere); [RETURN] every return; [TAIL] the last return; [INVOKE] / [INVOKE_ASSIGN] before / after a matched call; [FIELD] a field access; [NEW] a `new`; [CONSTANT] a constant load ([args] writes the value); [STORE] / [LOAD] after a local write / before a local read.
 * [target]: `INVOKE`-family `"Lowner/name;member(args)ret"` (package dots allowed), `"member(args)ret"` or `"member"` (unrestricted); `FIELD` `"Lowner/name;field:desc"` / `"field:desc"` / `"field"`; `NEW` `"Lowner/name;"` or `"(args)V"`.
 * [args] (used by [CONSTANT]) takes `intValue` / `longValue` / `floatValue` / `doubleValue` / `stringValue` / `nullValue`. [ordinal] takes the Nth match (0-based; -1 = all); [opcode] restricts a field access to an `org.objectweb.asm.Opcodes` value (-1 = any).
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
 * A constant matcher (aligned with Mixin's `@Constant`), used in [ModifyConstant.constant].
 * **Only fields explicitly written count** (annotation defaults are not written into the
 * bytecode), so `@Constant(intValue = 0)` expresses "match 0" — the same approach Mixin uses.
 * [intValue] and [longValue] are different types: `1` does not match `1L`.
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
    val ordinal: Int = -1,
)

/**
 * Local variable capture modes (aligned with Mixin's `LocalCapture`):
 * - [NO_CAPTURE] (default): capture nothing — the handler parameter list can **only** be `(CallbackInfo)` / `(CallbackInfoReturnable)`;
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
 * Inserts a callback at the specified location (aligned with Mixin's `@Inject`). Handler shape `([captures…,] CallbackInfo)` —
 * `CallbackInfo` (void target) or `CallbackInfoReturnable<T>` (non-void, `setReturnValue(v)` cancels and specifies the return value);
 * [methods] is the **union** of target method names.
 * [desc] empty matches **any overload**; when left empty, the [locals] capture is resolved at the injection point by data-flow analysis
 * over types — the static rule "i-th capture = i-th target parameter" needs the descriptor to compute slots.
 * [cancellable] really short-circuits only at the method-entry anchors (HEAD / CTOR_HEAD): a void target with `CallbackInfo`, non-void with
 * `CallbackInfoReturnable<T>`; other positions degrade to a notification.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Inject(
    val at: At,
    val method: String = "",
    val methods: Array<String> = [],
    val desc: String = "",
    val cancellable: Boolean = false,
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
 * startup; a missing one is an explicit startup-stage problem, not a silent no-op.
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
 * The anchor must be a method call (`@At(value = "INVOKE", target = "…")`). The engine does not
 * box: it stores the arguments into temporary locals in reverse order, modifies one, pushes them
 * back in order.
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
 * The handler has the form `(Args)V`, where `args[0]` is the receiver (for an
 * instance call) or the first argument. This path **necessarily boxes**, so on hot
 * paths use [ModifyArg] instead.
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
