package org.ohmyloader.core.mixin

/**
 * Internal name of `CallbackInfo`, the handle an injection bridge's last parameter carries on a
 * void-target.
 *
 * Declared here once, together with its descriptor: bridges are resolved by descriptor while
 * `NEW`/`checkcast` and the local-frame map use the internal name, and a pair that disagrees yields a
 * `NoSuchMethodError` or a corrupted frame at injection time — far from the typo that caused it.
 */
internal const val CALLBACK_INFO = "org/ohmyloader/api/mixin/CallbackInfo"

/** [CALLBACK_INFO]'s type descriptor. Never spell it out separately; see the reason above. */
internal const val CALLBACK_INFO_DESC = "L$CALLBACK_INFO;"

/** Internal name of `CallbackInfoReturnable`, the handle carried by a returnable-target bridge. */
internal const val CALLBACK_RETURNABLE = "org/ohmyloader/api/mixin/CallbackInfoReturnable"

/** [CALLBACK_RETURNABLE]'s type descriptor. */
internal const val CALLBACK_RETURNABLE_DESC = "L$CALLBACK_RETURNABLE;"

/** Internal name of `Args`, the all-arguments handle (`@ModifyArgs`' handler parameter). */
internal const val ARGS = "org/ohmyloader/api/mixin/Args"

/** [ARGS]'s type descriptor. */
internal const val ARGS_DESC = "L$ARGS;"
