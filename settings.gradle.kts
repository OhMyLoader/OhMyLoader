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
        // No GitHub Packages: oml-native lives in this repository (native/oml-native) and is built
        // from source by this build — there is no published artifact to resolve, and no credential
        // to hold. Everything else resolves from the public mirrors below.
        mavenCentral()
        maven("https://maven.minecraftforge.net/")
        maven("https://libraries.minecraft.net/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":oml-launcher")
include(":native:oml-native")
include(":oml-api-core")
include(":oml-api")
include(":oml-core")
include(":oml-content")
include(":oml-devtools")
include(":oml-installer")
include(":oml-adapter")

rootProject.name = "OhMyLoader"
