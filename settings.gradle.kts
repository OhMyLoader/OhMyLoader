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
        // The native library is built and published by the OhMyLoaderNative repository. GitHub
        // Packages requires authentication even for public packages: CI injects GITHUB_ACTOR and
        // GITHUB_TOKEN, a local build reads gpr.user/gpr.key from ~/.gradle/gradle.properties. The
        // content filter keeps the credential off every group except OML's own artifacts.
        // Local native-iteration path: publish a locally built oml-native into ~/.m2 (the same
        // layout the CI publish job produces) and it wins over the remote without credentials. It
        // must sit BEFORE GitHubPackages: a 401 there aborts resolution instead of falling through.
        mavenLocal()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/OhMyLoader/OhMyLoader")
            content {
                includeGroup("org.ohmyloader")
            }
            val user = providers.environmentVariable("GITHUB_ACTOR").orNull
                ?: providers.gradleProperty("gpr.user").orNull
            val token = providers.environmentVariable("GITHUB_TOKEN").orNull
                ?: providers.gradleProperty("gpr.key").orNull
            if (user != null && token != null) {
                credentials {
                    username = user
                    password = token
                }
            }
        }
        maven("https://maven.minecraftforge.net/")
        maven("https://libraries.minecraft.net/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":oml-launcher")
include(":oml-api-core")
include(":oml-api")
include(":oml-core")
include(":oml-content")
include(":oml-devtools")
include(":oml-installer")
include(":oml-adapter")

rootProject.name = "OhMyLoader"
