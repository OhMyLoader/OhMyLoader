package org.ohmyloader.api.mixin

/**
 * The argument list of one target invocation — for injections that rewrite all arguments in place.
 * The handler modifies this object in place; the engine unboxes the modified values and pushes
 * them back onto the call stack.
 * The elements are **boxed**: primitive-typed arguments box on every call (Mixin generates a
 * typed `Args` subclass per handler to avoid this; OML does not). So on hot paths (calls invoked
 * every frame) use `ModifyArg` (zero boxing, via locals); `ModifyArgs` suits cold paths that want
 * all arguments at once. [values] is exposed directly to the engine (`@JvmField`); handlers use
 * [get]/[set]/[size].
 */
class Args(@JvmField val values: Array<Any?>) {

    val size: Int
        get() = values.size

    /** The argument at [index] (boxed value). */
    operator fun get(index: Int): Any? = values[index]

    /** Rewrites the argument at [index] in place (boxed value; the engine unboxes it per the call site's declared type). */
    operator fun set(index: Int, value: Any?) {
        values[index] = value
    }

    override fun toString(): String = values.joinToString(", ", "Args[", "]")
}
