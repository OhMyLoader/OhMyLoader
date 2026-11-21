# OhMyLoader

A mod loader for Minecraft Java Edition 26.x. It provides bytecode injection with Mixin class
merging, a mod API built on the game's own types, declarative content registration, and an
installer. It runs on the client and the dedicated server.

**Status**: pre-release, running on Java 27. Loader changes are gated by the end-to-end verification
mod ([OhMyLoaderTestMod](https://github.com/OhMyLoader/OhMyLoaderTestMod)) before a release.

## Install

Download the installer from [Releases](https://github.com/OhMyLoader/OhMyLoader/releases), run
`java -jar`, and select a launcher target, game version, and directory. The installer adds only the
OML layer; the launcher downloads the game.

## Write a mod

```kotlin
@Mod(id = "my_mod", name = "My Mod", version = "1.0.0")
class MyMod : OMLModInitializer {
  override fun onInitialize(context: ModContext) {
    Events.BLOCK_BREAK.register { event ->
      if (event.state.blockId == "minecraft:bedrock") event.cancel()
    }
  }
}
```

The Gradle plugin resolves the API and the game artifacts; declarative content can also ship as a
TOML `.oml` archive with no code. The [documentation](https://ohmyloader.github.io) covers setup, the
API reference, and content packs.

## License

Licensed under AGPL-3.0-or-later.
