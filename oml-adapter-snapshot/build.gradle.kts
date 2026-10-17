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

// SnapshotLinkageTest parses the bytecode that ships into a snapshot launch instead of loading it
// (the game classes it references are not on this JVM's classpath), so those jars have to reach it as
// file paths. Two configurations, one per role: the version-neutral surface and the 26.3-built jars.
// Neither is published — they are read at configuration time, and the jars they name are already
// built by the test task's own classpath.
val linkageNeutral = configurations.create("linkageNeutral") {
    isCanBeConsumed = false
    isTransitive = false
}
val linkageVersionFacing = configurations.create("linkageVersionFacing") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    linkageNeutral(project(":oml-api"))
    linkageVersionFacing(project(":oml-api-26_3"))
    linkageVersionFacing(project(":oml-adapter-common"))
}

tasks.withType<Test>().configureEach {
    // The paths above are recorded at configuration time; the edge here is what makes the jars exist.
    dependsOn(linkageNeutral, linkageVersionFacing)
    systemProperty("oml.neutralJar", linkageNeutral.singleFile.absolutePath)
    systemProperty("oml.linkage.jars", linkageVersionFacing.asPath)
}

omlAdapter {
    // The only per-version fact left to declare: which game line this driver targets. 26.x jars are not
    // obfuscated — Mojang stopped publishing the official mappings with this line (the version JSON has
    // no `downloads.client_mappings`) and does not need to, since class and member names ship readable.
    versionId = "26.4-snapshot-2"
}