# oml-api Stability and Deprecation Policy

This file is the compatibility contract between `oml-api` and mod authors. It defines which signatures can be
relied on, which may change with the game, and how breaking changes are announced. The version has one source:
`oml_version` in the repository root `gradle.properties`. The build stamps it into `oml-core.properties`, and
the runtime banner and published coordinates read it.

## Three stability layers

| Layer | Content | Commitment |
|---|---|---|
| **Contract** | `@Mod` entry points, `OMLModInitializer` / `ModContext`, `Event` and `EventDefinition`, `ContentRegistry` declaration DSL, and injection DSL (`@RuleSource`, `RuleSetProvider`, anchors, rewrite primitives) | 0.x: breaking changes land directly with a `!` commit and no deprecation period; each migration step is in that commit's body. Frozen at 1.0; see the version policy below. |
| **Provided, still settling** | Command DSL (`command`), config (`config`), network (`network`), keybinds/HUD/creative tabs (`client`), main-thread scheduler (`tick`), and `ModContext.config` | 0.x: may change between versions; record changes in release notes and `docs/forensics.md`. Moves into the contract layer at 1.0. |
| **Version-facing** | `oml-api-26_3`: event payloads and extensions name 26.3 classes (`ServerPlayer`, `BlockPos`, `DamageSource`, `BuiltInRegistries`, and others) | No cross-game-version compatibility. Its compatibility target is that game version. A version change is a different artifact (`oml-api-<version>`), not a different call site. |

A signature belongs to the version-neutral artifact if it does not name a game version (declarations,
scheduling, event primitives, injection DSL); it is covered by the contract. A signature that names a game
type belongs to `oml-api-<version>` and follows the game version. The version-neutral artifact does not wrap
game objects and does not provide a `platform` escape hatch.

## Version policy

- `0.x.y-SNAPSHOT` (current): mainline development. An `x` change is an allowed contract-layer evolution; list
  each break in the release notes. A `y` change contains fixes and additions only.
- From `1.0.0`: the contract layer is frozen. A contract break is a major version event (`2.0.0`). Deprecated
  signatures remain until the next major with `@Deprecated` and a replacement; the runtime logs one WARN at
  startup.
- The settling layer has no compatibility promise before 1.0. Each item moved into 1.0 is announced in the
  release notes.

## Breaking changes

- **0.x (current):** a breaking change lands directly with a `!` commit. Removed signatures are not kept as
  deprecated shims. The migration goes in the commit body's Breaking section: `Breaking for mods:` for mod
  authors and `Breaking:` for plugin and content-pack implementers. A removed surface that follows the game
  version (for example, event payloads) moves into the `oml-api-<game version>` projection instead of staying
  in this artifact.
- **Documentation:** list the change in this file and in the release notes, and update the mod-author site.
- **From 1.0:** a contract break is a major version event. Old signatures remain until the next major with
  `@Deprecated`, `ReplaceWith`, and a reason; the runtime logs one WARN at startup. Deprecation does not add
  an error-level runtime block; a mod must not stop running because of a deprecation.
- **Repository discipline:** a breaking `oml-api` change is a separate commit with its constraint and
  migration in the message. Do not mix it with feature work.
