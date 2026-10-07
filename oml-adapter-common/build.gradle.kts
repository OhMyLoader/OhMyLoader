plugins {
    id("oml.convention.kotlin-jvm")
    // The reference toolchain: this module compiles against the 26.3 game jar and its libraries.
    // A newer game version that renames a shape needs a difference file in ITS adapter module, not
    // a change here — that is the whole point of the split.
    id("oml.convention.oml-adapter")
    // Shared by both adapter artifacts: the version adapters are thin entry points over this code.
    id("oml.convention.publishing")
}

dependencies {
    implementation(libs.bundles.asm)

    compileOnly(libs.nettyCodecBase)
    compileOnly(libs.nettyTransport)
    testImplementation(libs.nettyCodecBase)
    testImplementation(libs.nettyTransport)

    // The 26.3-facing mod API: the event payloads this bridge fires are its types, and it is defined by
    // the same loader as the game classes, which is the only arrangement that keeps one game type.
    implementation(project(":oml-api-26_3"))

    compileOnly(files("libs/26.3-client.jar"))
    testImplementation(files("libs/26.3-client.jar"))

    // The biome merge for declared ores parses the vanilla biome JSON (Gson): compile-time only,
    // the game's own libraries supply the same artifact at run time.
    compileOnly(libs.gson)

    // The command bridge walks the game's Brigadier tree; the game supplies brigadier at run time
    // (the client jar does not embed it).
    compileOnly(libs.brigadier)

    // The projections read registry ids off `BuiltInRegistries`; every registry interface extends
    // `com.mojang.serialization.Keyable`, so that class must resolve at compile time even though the
    // members called are the game's own. The game supplies DataFixerUpper at run time.
    compileOnly(libs.datafixerupper)

    testImplementation(kotlin("test"))
    // The codec tests drive the real oml-native library through the SAME test wiring oml-core's
    // own compression tests use — shared as test fixtures instead of a per-project copy.
    testImplementation(testFixtures(project(":oml-core")))
}

tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

omlAdapter {
    // The reference version this module compiles against: the common code is built on the 26.3
    // shapes and a newer version that renames one needs a difference file in its own module.
    versionId = "26.3"
}
