package org.ohmyloader.api.mixin

/*
 * **Class-merge** annotations: `@Shadow` / `@Unique` / `@Overwrite` (plus `@Accessor`/`@Invoker`
 * synthesis). Unlike the injection family — which inserts a call while the handler stays in the
 * mixin class — these merge the mixin's fields/methods **into the target class**: `this` is the
 * target instance and `private` members are directly visible (no reflection). Mixin's native model.
 * `@Shadow` members are only verified against the target class (references rewritten); everything
 * else merges, renaming automatically on name/descriptor collision; `<init>` merges into target
 * constructors delegating via `super()` only (`this(...)` delegations skipped, else the body runs
 * twice), and the merged segment admits no jumps or local writes (the target's slot layout
 * differs). Per-member details live on each annotation.
 */

/**
 * Declares "this field/method **exists in the target class** and is merely borrowed for use" (aligned with Mixin's `@Shadow`). It is
 * **not** merged — the declaration lets the mixin's code read/write that member of the target class directly. A missing member (name and
 * descriptor both fail to match) is a **startup/merge-stage error**, not a silent ignore. A shadow field must carry **no initializer**:
 * the field itself is not merged, but the initializer lives in the (merged) constructor segment, so the assignment would appear in the
 * target constructor. When the name does not match, use [aliases] (`@Shadow(aliases = ["func_71407_l"])`) — references inside the mixin
 * are rewritten to the matching name, which is how one mixin reuses a rule across versions/namespaces.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Shadow(
    val aliases: Array<String> = [],
    val value: String = "",
)

/**
 * Declares "this is a member the mixin brings in itself; the target class did not have it"
 * (aligned with Mixin's `@Unique`). Members merge even without the annotation; the only
 * difference is documentation — with it, a reader sees at a glance that the member is newly
 * added. Name conflicts are renamed automatically either way.
 */
@Target(AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Unique

/**
 * **Replaces** the target method's entire body (aligned with Mixin's `@Overwrite`). The handler
 * is an ordinary method whose signature (name + descriptor) must **exactly match** the target
 * method; at merge time only the body is swapped, access flags and annotations are preserved.
 * There is **no "call the original implementation" mechanism** — to keep original behavior,
 * rewrite it yourself (to add something before/after, use the injection family). When **two**
 * mixins `@Overwrite` the same target method, it is reported as a conflict rather than silently
 * picking one.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Overwrite

/**
 * **Synthesizes** an accessor for a field in the target class (aligned with Mixin's `@Accessor`). Only usable on an **interface mixin's**
 * abstract methods — you declare the signature, the engine synthesizes the method body in the target class. Empty [value] infers the field
 * from the method name: `getX`/`isX` → `x`, `setX` → `x` (first letter lowercased).
 * Only **instance fields** are supported (an interface abstract method cannot express a static synthesized body) — a static target field
 * reports a problem. An existing **same-signature** method in the target class also reports a problem (the accessor is redundant, most
 * likely a typo). The synthesized method runs inside the target class, so `private` fields are directly visible.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Accessor(val value: String = "")

/**
 * **Synthesizes** a forwarder to a target method (aligned with Mixin's `@Invoker`); likewise only
 * on an **interface mixin's** abstract methods, body = push each argument in order and `INVOKE…`
 * the target method (`INVOKESPECIAL` for a `private` target).
 * Empty [value] infers the method from the name: `callX`/`invokeX` → `x`. Parameters and return
 * type must **exactly match** the target method (a void return may simply be dropped); a static
 * target method and constructor forwarding (`<init>`) are not supported — both report a problem.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Invoker(val value: String = "")
