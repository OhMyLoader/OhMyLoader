// The oml-native Rust crate, wrapped as a Gradle module for exactly one purpose: publishing the
// built library to mavenLocal under the coordinates the Gradle plugin resolves
// (`org.ohmyloader:oml-native:<loader version>:natives-<os>`), so a dev run and the CI-staged E2E
// gate deploy the native the same way a player's installer does. The compile itself stays cargo —
// `cargo build --release` for the host, incremental and skipped when sources are unchanged.
//
// The installer does not go through Maven at all: its `embedNativesJars` task (see
// oml-installer/build.gradle.kts) builds every platform target and packages the natives-<classifier>
// jars straight from this crate.

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

plugins {
    `java-library`
    `maven-publish`
}

val omlVersion = providers.gradleProperty("oml_version")
    .orElse(providers.gradleProperty("oml_native_version"))
    .getOrElse("0.1.0-SNAPSHOT")

group = "org.ohmyloader"
version = omlVersion

/** The platform token + classifier suffix for this build machine (same spelling as the installer). */
val hostClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osToken = when {
        os.contains("mac") || os.contains("darwin") -> "osx"
        os.contains("win") -> "windows"
        else -> "linux"
    }
    val archSuffix = if (arch.contains("aarch64") || arch.contains("arm64")) "-arm64" else ""
    "$osToken$archSuffix"
}

/** The cargo target for the build machine — the default triple is always the host. */
val cargoOutputDir = layout.projectDirectory.dir("target/release")

val buildOmlNative = tasks.register("buildOmlNative") {
    group = "ohmyloader"
    description = "cargo build --release for the host platform (incremental; cargo skips unchanged sources)"
    outputs.dir(cargoOutputDir)
    val crateDir = projectDir
    doLast {
        val process = ProcessBuilder("cargo", "build", "--release")
            .directory(crateDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.buffered().readBytes().toString(Charsets.UTF_8)
        check(process.waitFor() == 0) { "cargo build failed:\n$output" }
    }
}

/** The library cargo produced; [buildOmlNative] runs before any consumer reads the directory. */
val builtLibrary = files(cargoOutputDir).builtBy(buildOmlNative)

val emptyMainJar = tasks.register<Jar>("mainJar") {
    archiveBaseName.set("oml-native")
    archiveVersion.set(omlVersion)
    // The publication's coordinate anchor: GitHub Packages (and mavenLocal consumers) need a main
    // artifact; the library itself travels in the natives-<classifier> jars.
}

val nativesJar = tasks.register<Jar>("nativesJar") {
    archiveBaseName.set("oml-native")
    archiveVersion.set(omlVersion)
    // The classifier spelling the plugin resolves: natives-<os> (natives-windows/natives-linux/natives-osx).
    archiveClassifier.set("natives-$hostClassifier")
    // The cargo output carries the crate identifier (lib-prefixed on unix); the jar entry is
    // renamed to exactly the hyphenated `oml-native.<ext>` the loader resolves — a naive
    // replaceFirst on `liboml_native.so` leaves the lib prefix in (`liboml-native.so`), which
    // the deploy regex rejects and the loader then loads a stale deployed copy.
    from(builtLibrary) {
        include("oml_native.*", "liboml_native.*")
        rename { it -> it.removePrefix("lib").replace('_', '-') }
    }
}

tasks.named("assemble") { dependsOn(buildOmlNative, nativesJar, emptyMainJar) }

publishing {
    publications {
        create<MavenPublication>("omlNative") {
            groupId = "org.ohmyloader"
            artifactId = "oml-native"
            version = omlVersion
            artifact(emptyMainJar)
            artifact(nativesJar)
        }
    }
}

tasks.withType<PublishToMavenLocal>().configureEach { dependsOn(buildOmlNative, nativesJar, emptyMainJar) }
