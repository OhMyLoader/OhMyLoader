package org.ohmyloader.core.hook

/**
 * Internal name of `HookContext`, the handle an injection bridge's last parameter carries on a
 * void-target.
 *
 * Declared here once, together with its descriptor: bridges are resolved by descriptor while
 * `NEW`/`checkcast` and the local-frame map use the internal name, and a pair that disagrees yields a
 * `NoSuchMethodError` or a corrupted frame at injection time — far from the typo that caused it.
 */
internal const val CALLBACK_INFO = "org/ohmyloader/api/hook/HookContext"

/** [CALLBACK_INFO]'s type descriptor. Never spell it out separately; see the reason above. */
internal const val CALLBACK_INFO_DESC = "L$CALLBACK_INFO;"

/** Internal name of `HookValue`, the handle carried by a returnable-target bridge. */
internal const val CALLBACK_RETURNABLE = "org/ohmyloader/api/hook/HookValue"

/** [CALLBACK_RETURNABLE]'s type descriptor. */
internal const val CALLBACK_RETURNABLE_DESC = "L$CALLBACK_RETURNABLE;"

/** Internal name of `Args`, the all-arguments handle (`@ModifyArgs`' handler parameter). */
internal const val ARGS = "org/ohmyloader/api/hook/Args"

/** [ARGS]'s type descriptor. */
internal const val ARGS_DESC = "L$ARGS;"

/** Internal name of `Operation`, the original-operation handle (`@WrapOperation` / `@WrapMethod`). */
internal const val OPERATION = "org/ohmyloader/api/hook/Operation"

/** [OPERATION]'s type descriptor. */
internal const val OPERATION_DESC = "L$OPERATION;"

/** Internal name of `LocalRef`, the writable local handle (`@Local` on a `LocalRef<T>` parameter). */
internal const val LOCAL_REF = "org/ohmyloader/api/hook/LocalRef"

/** [LOCAL_REF]'s type descriptor. */
internal const val LOCAL_REF_DESC = "L$LOCAL_REF;"

/** The engine-created [LOCAL_REF] implementation. */
internal const val LOCAL_REF_IMPL = "org/ohmyloader/api/hook/LocalRefImpl"

/** Factory whose return type makes the shared-ref local slot exactly `LocalRef`. */
internal const val LOCAL_REFS = "org/ohmyloader/api/hook/LocalRefs"
