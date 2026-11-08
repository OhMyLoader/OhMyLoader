package org.ohmyloader.api.hook

/**
 * The original operation handed to a `@WrapOperation` handler, or the original method body handed to a
 * `@WrapMethod` handler (aligned with MixinExtras' `Operation`).
 *
 * [call] re-executes the original operation with the arguments supplied here — not necessarily the ones
 * the handler received, so a handler can change arguments, call it several times, or not call it at all.
 * The returned value is the operation's result (boxed for primitives; `null` for a void operation).
 *
 * The engine creates this object at the injection point; handler authors only call it.
 */
fun interface Operation<R> {
    fun call(vararg args: Any?): R
}