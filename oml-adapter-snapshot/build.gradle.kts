// The SNAPSHOT adapter: a working clone of the current stable adapter, kept tracking the latest
// snapshot (26.4-snapshot-2) so the eventual official 26.4 adapter is a small diff instead of a
// rewrite. It IS bundled in the installer under the stable alias `snapshot` — the installer
// resolves the alias to the manifest's latest snapshot at install time — so a snapshot player
// installs and launches the same way a 26.3 one does; this module exists to absorb the churn
// early and to keep that install path exercised between stable releases.
plugins {
    id("oml.convention.kotlin-jvm")
    id("oml.convention.oml-adapter")
    // Published so a mod project gets the adapter by Maven coordinate (oml-gradle's
    // `adapterArtifact` -> org.ohmyloader:oml-adapter-26_3), the same path an external mod takes.
    id("oml.convention.publishing")
}

// MinecraftHookTransformer / ServerHookTransformer operate directly on ASM instructions
// (node types are needed when reusing the core injection executor)
dependencies {
    // The thin entry point references the shared implementation only; game classes are not
    // named here anymore, so no game jar is on the compile classpath.
    implementation(project(":oml-adapter-common"))

    // HookShapeTest walks the live rule sets and reads the real game jar with ASM.
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.asm)
}
tasks.withType<Test>().configureEach {
    // The codec tests call FFM restricted methods (oml-native downcalls via NativeManager); JDK 25+
    // wants the native-access grant, the same one OmlJvmContract carries for a real launch.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

omlAdapter {
    // The only per-version fact left to declare: which game line this driver targets. 26.x jars are not
    // obfuscated — Mojang stopped publishing the official mappings with this line (the version JSON has
    // no `downloads.client_mappings`) and does not need to, since class and member names ship readable.
    versionId = "26.4-snapshot-2"
}