// The repository's own build: the loader, the installer and the per-version adapter.
//
// oml-gradle and oml-testmod are separate repositories; nothing is included across the boundary
// (a build that includes another may not include it in return — re-entrant plugin resolution
// deadlocks Gradle's settings evaluation). `./gradlew build` here builds the loader; a development
// run happens in the OhMyLoaderTestMod repository, which consumes this one by Maven coordinate.
pluginManagement {
    repositories {
        gradlePluginPortal()
    }

    // plugin ids: `oml.convention.*`, derived from the source package name.
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        maven("https://maven.minecraftforge.net/")
        maven("https://libraries.minecraft.net/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":oml-launcher")
include(":oml-api")
include(":oml-core")
include(":oml-devtools")
include(":oml-native")
include(":oml-installer")
include(":oml-adapter-26_3")
include(":oml-adapter-snapshot")

rootProject.name = "OhMyLoader"
