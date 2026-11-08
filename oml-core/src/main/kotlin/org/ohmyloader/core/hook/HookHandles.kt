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

/** A primitive-specialized local ref: interface, engine-created impl, local type, ctor and getter. */
internal class TypedRef(
    val interfaceInternal: String,
    val implInternal: String,
    val primitive: org.objectweb.asm.Type,
    val ctorDesc: String,
    val getterDesc: String,
)

/** Typed `LocalRef` descriptors → their engine facts. */
internal val TYPED_REFS: Map<String, TypedRef> = buildMap {
    fun reg(
        name: String,
        primitive: org.objectweb.asm.Type,
        ctorDesc: String,
        getterDesc: String,
    ) {
        val internal = "org/ohmyloader/api/hook/$name"
        put("L$internal;", TypedRef(internal, "$internal" + "Impl", primitive, ctorDesc, getterDesc))
    }
    reg("LocalBooleanRef", org.objectweb.asm.Type.BOOLEAN_TYPE, "(Z)V", "()Z")
    reg("LocalByteRef", org.objectweb.asm.Type.BYTE_TYPE, "(B)V", "()B")
    reg("LocalCharRef", org.objectweb.asm.Type.CHAR_TYPE, "(C)V", "()C")
    reg("LocalShortRef", org.objectweb.asm.Type.SHORT_TYPE, "(S)V", "()S")
    reg("LocalIntRef", org.objectweb.asm.Type.INT_TYPE, "(I)V", "()I")
    reg("LocalLongRef", org.objectweb.asm.Type.LONG_TYPE, "(J)V", "()J")
    reg("LocalFloatRef", org.objectweb.asm.Type.FLOAT_TYPE, "(F)V", "()F")
    reg("LocalDoubleRef", org.objectweb.asm.Type.DOUBLE_TYPE, "(D)V", "()D")
}
