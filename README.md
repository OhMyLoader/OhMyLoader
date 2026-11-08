# OhMyLoader (OML)

A mod loader for Minecraft Java Edition 26.x. It provides a custom bytecode injection engine, a two-layer
mod API (version-neutral declarations and scheduling, plus a 26.3 access layer expressed in native game
types), and declarative content registration. It supports the client and the dedicated server. The current
stable line is 26.3; the stack runs on Java 27.

## Features

- **Injection engine.** A declarative injection DSL and Mixin class merging (`@Shadow`, `@Overwrite`,
  `@Accessor`, and others). Rules are self-checked at startup (handler existence, signature, and
  anchor/access-rule sanity); target existence and `require`/`allow` hit counts are enforced when the
  target class loads, and the bundled adapters' anchors are checked against the real game jar at build
  time. A bad rule fails fast instead of silently doing less in game.
- **Mod API.** `@Mod` entry points and lambda-based event registration for ticks, lifecycle, block and entity
  interactions, damage, death, chat, GUI, world load, chunk lifecycle, HUD, and frame-rate limits. Event
  payloads are the game's own objects (`ServerPlayer`, `BlockPos`, `BlockState`, `DamageSource`, `Screen`),
  with no wrapper layer. Command sources, network contexts, and registered block handles expose a `platform`
  handle where a typed surface is not yet available.
- **26.3 access API (`org.ohmyloader.api26_3`).** `OMLGame.server` and `OMLGame.client` are
  `MinecraftServer` and `Minecraft`. Other capabilities are extension functions and properties on game types,
  so there are no wrapper objects or cross-tick stale handles. They cover entity spawn and query, motion,
  attributes and modifiers, damage, ignition, block state read/write, particles, weather, game rules,
  per-dimension clocks and timelines, dimensions and dimension types, forced chunks, permission nodes,
  cross-dimension teleport, block entities and persistent block data, spawn and respawn anchors, containers,
  menus, equipment, effects, sounds, teleport, permission levels, chunk load checks, and item removal.
  `OMLScheduler` moves work from non-game threads back to the owning side's tick queue.
- **Declarative content.** `ContentRegistry` declares blocks, items, recipes, drops, damage types, clocks and
  markers, dimension types and dimensions, tags, behavior hooks, block entities, and ore worldgen. Blocks and
  items materialize into native content at the registry freeze point; recipes, drops, damage types, clocks,
  timelines, dimensions, and tags become data pack JSON in an injected resource pack. Tags use the tag's own
  namespace (`data/<tag-ns>/tags/<registry>/<tag>.json`); OML merges multiple mods' entries into one file and
  rejects conflicting `replace` declarations instead of choosing by load order.
- **Registry and tag reads.** `byId`, `ids`, `contains`, `membersOf`, `tagsOf`, and `tagIds` address
  `BuiltInRegistries` and data pack tables by id. Missing ids return null; `tagIds` distinguishes an empty tag
  from a tag the game did not load.
- **Custom network payloads.** `OMLNetworkProvider` declares channels and codecs with `OMLPacketBuffer`; both
  sides route by channel name through vanilla's two payload packet ids. Singleplayer integrated servers and
  dedicated servers share the declaration.
- **Asset injection.** Each mod's `assets/` tree is visible to the game through the injected resource pack.
- **Both sides.** Separate client and dedicated-server hooks; `oml.side` selects the side.
- **No remapping.** 26.x jars are unobfuscated, so class and member names are readable and no remapping stage
  exists.

## Install

Download the installer from [Releases](https://github.com/OhMyLoader/OhMyLoader/releases), run `java -jar`,
and select a launcher target (standard, Prism, or dedicated server), game version, and directory. The
launcher downloads the game; the installer adds only the OML layer.

## Write a mod

```kotlin
@Mod(id = "my_mod", name = "My Mod", version = "1.0.0")
class MyMod : OMLModInitializer {
  override fun onInitialize(context: ModContext) {
    Events.CLIENT_TICK.register { /* per logical tick */ }
    Events.CHAT_RECEIVED.register { event ->
      if (event.message.contains("bad_word")) {
        event.cancel() // cancellable events use cancel(); canceled is read-only
      }
      // event.packet is the received Clientbound...Packet, not pre-digested text
    }
    Events.BLOCK_BREAK.register { event ->
      if (event.state.blockId == "minecraft:bedrock") event.cancel() // player and position are game objects
    }
  }
}
```

The Gradle plugin supplies `oml-api`, `oml-api-26_3` (derived from `oml { minecraftVersion }`), the game jar,
and the version's libraries. Mod classes must not use the `org.ohmyloader.` prefix. That prefix is
parent-first: the launcher layer defines those classes without the game libraries, so the first
`net.minecraft.*` reference fails with `NoClassDefFoundError`. The loader rejects and skips such a jar while
scanning `mods/` and names the jar and packed classes; `omlJar` also rejects it.

For game internals without an API, write an injection rule in a `@RuleSource` object that implements
`RuleSetProvider`. The loader reads and checks it at startup.

```kotlin
@RuleSource("my_mod")
object MyRules : RuleSetProvider {
  override fun rules(): RuleSet = injection {
    classTarget("net/minecraft/client/Minecraft") {
      method("runTick") {
        atHead { call("com/example/MyRules", "onGameTick", "()V") }
        require(1) // startup check; a miss fails the launch
      }
      field("proxy", desc = "Ljava/net/Proxy;") {
        makePublic()
        removeFinal()
      }
    }
  }
}
```

A mod ships as a jar. Pure declarative content can ship without code: put a `content/` tree (one file per
entity) and its `assets/` into an `.oml` archive (a zip with the `.oml` extension) under `mods/`. The archive
file name is the resource namespace; two archives with the same file name in different subdirectories
conflict, and the loader refuses the later one. Mod jars and `.oml` archives are discovered
recursively under `mods/`; dot-directories are ignored.

## Development build

The workspace separates the loader, Gradle plugin, and verification mod; they consume each other only through
Maven coordinates.

```bash
# After changing this repository, publish to the local Maven repository:
./gradlew publishToMavenLocal
# In OhMyLoaderTestMod, build and launch the game
# (the plugin fetches the game jar, libraries, natives, and assets):
./gradlew runClient
./gradlew runServer
# Compile and run unit tests in this repository:
./gradlew build
# Build the installer fat jar in dist/:
./gradlew :oml-installer:shadowJar
```

**Snapshot tracking.** In the test mod, `oml { minecraftVersion.set("snapshot") }` resolves the manifest's
`latest.snapshot` at runtime and uses `oml-adapter-snapshot`, so a new snapshot needs no configuration
change.

### Toolchain

JDK 27 (Zulu), Gradle 9.9.0, and Kotlin 2.5.0-Beta2. Kotlin 2.5.0-Beta2 is currently the only line that
declares JVM 27 as its target. `build-logic` runs on JVM 26 because `kotlin-dsl` binds to Gradle's embedded
compiler; this is valid.

### Modules

- `oml-core`: loader core (class loading, injection executor, Mixin front end and class merging, SPI).
- `oml-api`: version-neutral mod API (event primitives, content declarations, scheduling, config, network,
  injection, and Mixin).
- `oml-api-26_3`: 26.3-facing API; event payloads and extensions name game types directly. The main loader
  must define it child-first.
- `oml-content`: version-neutral content registration and `.oml`/TOML loading.
- `oml-launcher`: bootstrap header.
- `oml-adapter-common`: shared hooks, event bridge, content materialization, and network bridge.
- `oml-adapter-26_3` and `oml-adapter-snapshot`: thin version drivers.
- `oml-native`: Zig-built C library for Zstandard codecs.
- `oml-devtools`: downloader.
- `oml-installer`: installer, including the Java 8 bootstrap stub.

### Debug switches

- `-Doml.injection.verify=fail|warn|off`: injection rule self-check; default `fail`.
- `-Doml.diagnostics=<any value except false>`: rewrite timing and size statistics; the switch is Boolean.

### installLauncher

Each adapter module exposes an `installLauncher` task that installs OML into a real launcher. Version
isolation is required: `-PomlIsolation=true|false`; set the install id with `-PomlInstallId`; add
`-PomlSide=server` for a server instance.

### Platform

The toolchain covers Windows, Linux, and macOS (including arm64). `runClient` and `runServer` have been
verified on Windows; the dual-side E2E script gates run on Linux CI.

### Roadmap

26.4 stable adaptation (snapshot tracking), a multi-version matrix (long term), and distribution channels.

## License

AGPL-3.0.
