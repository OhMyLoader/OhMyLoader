// The SNAPSHOT adapter: a working clone of the current stable adapter, kept tracking the latest
// snapshot (26.4-snapshot-2) so the eventual official 26.4 adapter is a small diff instead of a
// rewrite. It is NOT embedded in the installer and not offered for installs — nobody plays
// snapshots; this module only exists to absorb the churn early.
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
    implementation(libs.bundles.asm)

    // Zstd compression (region files id 127 + network packets) runs on our own zig-built
    // `oml-native` library through the NativeManager FFM contract; no third-party binding.
    // Packet-codec handlers extend Netty base classes: the game supplies netty at run time,
    // tests need the real classes for EmbeddedChannel round-trips.
    compileOnly(libs.nettyCodecBase)
    compileOnly(libs.nettyTransport)
    testImplementation(libs.nettyCodecBase)
    testImplementation(libs.nettyTransport)

    // Typed handlers and the RegionFileVersion.StreamWrapper implementations compile against the
    // real 26.3 jar (unobfuscated, so names match exactly). compileOnly — at run time the game
    // supplies these classes, and shipping them would be a second copy of Minecraft.
    compileOnly(files("libs/26.4-snapshot-2-client.jar"))
    // Codec round-trip tests instantiate our stream wrappers, whose supertypes live in the game jar.
    testImplementation(files("libs/26.4-snapshot-2-client.jar"))

    // HookShapeTest walks the live rule sets (kotlin.test) and reads the real client jar with ASM
    // (already on the implementation classpath).
    testImplementation(kotlin("test"))

    // The codec tests drive the real oml-native library through the SAME test wiring oml-core's
    // own compression tests use — shared as test fixtures instead of a per-project copy that
    // would drift.
    testImplementation(testFixtures(project(":oml-core")))
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