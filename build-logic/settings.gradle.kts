// The version catalog points at the repository's shared gradle/libs.versions.toml so the
// conventions and the main build cannot end up on different Kotlin or ASM versions.
dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }

    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"