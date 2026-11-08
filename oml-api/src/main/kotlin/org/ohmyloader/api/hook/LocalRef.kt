package org.ohmyloader.api.hook

/**
 * A **writable** handle to a local variable at the injection point (aligned with MixinExtras' `LocalRef`).
 *
 * Declare the handler parameter as `LocalRef<T>` together with `@Local`; the engine creates the ref,
 * initializes it from the local's current value, passes it to the handler, and writes [value] back to the
 * local after the handler returns. Read [value] before writing if the original value is needed.
 *
 * The ref is only valid for the duration of one injection call; do not store it. Mutating refs are
 * boxed (unlike a read-only `@Local` parameter, which is unboxed).
 */
interface LocalRef<T> {
    var value: T?
}

/** The engine-created implementation of [LocalRef]; handlers only see the interface. */
class LocalRefImpl<T>(initial: T? = null) : LocalRef<T> {
    override var value: T? = initial
}