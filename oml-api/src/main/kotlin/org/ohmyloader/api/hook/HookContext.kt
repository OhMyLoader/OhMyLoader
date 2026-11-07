package org.ohmyloader.api.hook

/**
 * Callback handle for an injection handler. When OML's Mixin front-end weaves
 * the handler invocation into the target class, it passes this object in via a
 * runtime bridge.
 *
 * [open] so that [HookValue] for non-void targets can extend it —
 * the two share identical "cancellation" semantics; the latter merely adds a
 * return value.
 */
open class HookContext(val name: String, val cancelable: Boolean) {

    var canceled: Boolean = false
        private set

    /** Cancels the target method (only effective when [cancelable] and the injection point supports short-circuiting). */
    fun cancel() {
        check(cancelable) { "HookContext($name) is not cancelable" }
        canceled = true
    }
}

/**
 * Callback handle for non-void target methods: besides canceling, it can also **specify the target method's return value**.
 * [setReturnValue] automatically [cancel]s (as in Mixin) — setting a value is "return right here"; calling only `cancel()` yields the
 * return type's zero value (`0` / `false` / `null`).
 * The typed accessors (`getReturnValueI` and friends) exist for the injected bytecode, which must feed the value to the target method's
 * `xRETURN`: an erased `getReturnValue(): T?` would force a `CHECKCAST` + unbox sequence plus a special branch for "no value set = null"
 * at every injection point (unboxing would NPE on primitives); with typed accessors one `INVOKEVIRTUAL` suffices and "no value set"
 * degrades to a zero value inside the accessor.
 */
class HookValue<T>(name: String, cancelable: Boolean) : HookContext(name, cancelable) {

    private var value: T? = null

    /** The currently set return value (null when unset). */
    val returnValue: T?
        get() = value

    /** Sets the return value **and cancels** the target method — the two are the same operation. */
    fun setReturnValue(returnValue: T?) {
        cancel()
        value = returnValue
    }

    // ---- typed accessors: let the engine avoid generating unbox conversions (aligned with Mixin's HookValue) ----
    // When unset, they degrade to zero values rather than throwing NPE, so the injection point's bytecode needs no null branch.

    @Suppress("UNCHECKED_CAST")
    fun getReturnValueZ(): Boolean = value != null && value as Boolean

    @Suppress("UNCHECKED_CAST")
    fun getReturnValueB(): Byte = if (value == null) 0 else value as Byte

    @Suppress("UNCHECKED_CAST")
    fun getReturnValueC(): Char = if (value == null) '\u0000' else value as Char

    @Suppress("UNCHECKED_CAST")
    fun getReturnValueS(): Short = if (value == null) 0 else value as Short

    @Suppress("UNCHECKED_CAST")
    fun getReturnValueI(): Int = if (value == null) 0 else value as Int

    @Suppress("UNCHECKED_CAST")
    fun getReturnValueJ(): Long = if (value == null) 0L else value as Long

    @Suppress("UNCHECKED_CAST")
    fun getReturnValueF(): Float = if (value == null) 0f else value as Float

    @Suppress("UNCHECKED_CAST")
    fun getReturnValueD(): Double = if (value == null) 0.0 else value as Double

    override fun toString(): String =
        "HookValue[name=$name, cancelable=$cancelable, canceled=$canceled, value=$value]"
}
