# oml-api Style Guide

This file constrains the public API shape of `oml-api` and `oml-api-26_3`. Use Kotlin idioms: design new
capabilities against this guide, and move existing ones toward it. If code and this file conflict, this file
is the target.

## Five rules

### 1. Declaration trees use receiver lambdas; the receiver is the declaration

Configure an object with a trailing lambda whose receiver is the object (or its declaration class). Do not add
a facade or builder layer. `ContentRegistry` is the model:

```kotlin
registry.declareItem("test_sword") {
    maxDurability = 592
    attackDamage = 7.0
}
```

One-time sub-declarations (`blockEntity { }`, `generateAsOre { }`) must reject a second declaration with
`check`.

### 2. Reactions use trailing lambdas and verb names

Event registration, block behavior hooks, and command `executes { }` use `onX { }` or `register { }` trailing
lambdas. Callback parameters carry game objects, not wrappers.

### 3. Actions are extension functions on game types

A single action on a game object (spawn an entity, teleport, ignite, read or write a block state) belongs in
`oml-api-26_3` as an extension function or property. Do not allocate a wrapper. Extension names use game
vocabulary that players and mod authors know, not internal class method names.

### 4. Prefer typed handles over string lookup

When a runtime lookup name is known at declaration time, return a passable typed handle so the lookup type is
checked at compile time. Command arguments are the model: declaration returns `OMLArgument<T>`, and the
handler reads `T` with `source[handle]`. A misspelled name or wrong type fails compilation. String lookup is
for genuinely dynamic cases only, such as config keys and block-entity data keys.

### 5. Stable-layer vocabulary does not reference vanilla internals

Names in `oml-api` (the version-neutral layer) must use OML or game semantics. Vanilla class names, method
names, and component keys may appear only in KDoc as mapping notes and in `oml-api-26_3` or adapter
implementations. A counterexample is `destroyTime`, a `BlockBehaviour.Properties` method name: a 26.x rename
would break the stable layer's source-level stability promise. The ore declaration shows the intended form:
the API name is `veinSize`, and the KDoc notes the vanilla name `size`.

## When not to use a DSL

A DSL is a tool, not a goal. Use a normal function in these cases:

- **A single action on a game object:** `player.teleport(...)` and `first.ignite(60)` are correct. Do not
  invent `player.action { teleportTo(...) }`.
- **Read-only queries:** keep `level.blockIdAt(pos)` and `server.gameRuleValue(id)` as normal functions.
- **No configuration tree:** do not create a receiver for a one-argument API or a DSL for two positional
  arguments.
- **One usable member in the receiver:** it is not a useful scope.

## Stability boundary

The division between style and stability is in [README.md](README.md). One boundary applies here:
`oml-api` promises source-level stability within the 26.x line, so these five rules are hard constraints in
`oml-api`. In `oml-api-26_3` they are a preference; that layer may follow the game's shape, but the extension
style in rule 3 is still preferred.
