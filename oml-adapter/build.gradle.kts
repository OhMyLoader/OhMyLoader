import oml.convention.omlGameVersion

// The one game version this tree tracks, from the root gradle.properties (no task parameter can
// move it).
val gameVersion = omlGameVersion()

// The one game adapter: it targets the newest Minecraft version and nothing else.
//
// OML's model is "zero-baggage rolling loader": the tree is always built, run and shipped against
// the newest game line, so there is no shared-versus-version split left here. Every rule names the
// live jar's own classes directly (26.x jars ship readable names), and [HookShapeTest] reads that
// jar with ASM: a rename or reshape fails the build instead of the hook silently missing at launch.
//
// Upgrading the game is therefore one edit — `versionId` below — plus whatever the shape test and
// the compiler report.
plugins {
    id("oml.convention.kotlin-jvm")
    id("oml.convention.oml-adapter")
    // Published so a mod project gets the adapter by Maven coordinate (oml-gradle's
    // `adapterArtifact` -> org.ohmyloader:oml-adapter), the same path an external mod takes.
    id("oml.convention.publishing")
}

// MinecraftHookTransformer / ServerHookTransformer operate directly on ASM instructions
// (node types are needed when reusing the core injection executor)
dependencies {
    implementation(libs.bundles.asm)

    compileOnly(libs.nettyCodecBase)
    compileOnly(libs.nettyTransport)
    testImplementation(libs.nettyCodecBase)
    testImplementation(libs.nettyTransport)

    // The game-facing mod API: the event payloads this bridge fires are its types, and it is defined
    // by the same loader as the game classes, which is the only arrangement that keeps one game type.
    implementation(project(":oml-api"))

    // The window lease is supervisor state; the handlers reference it, the launch supplies it.
    compileOnly(project(":oml-launcher"))
    testImplementation(project(":oml-launcher"))

    // The one game jar this module hooks: it compiles against it and the shape tests read it with ASM.
    compileOnly(files("libs/$gameVersion-client.jar"))
    testImplementation(files("libs/$gameVersion-client.jar"))

    // EventLoopGroupHolder's group type: the game jar only carries the holder, Netty ships in the
    // installer's libraries layer.
    compileOnly(
        files(
            "libs/$gameVersion-libraries/io/netty/netty-common/4.2.19.Final/netty-common-4.2.19.Final.jar",
            "libs/$gameVersion-libraries/io/netty/netty-transport/4.2.19.Final/netty-transport-4.2.19.Final.jar",
        ),
    )
    // The discovery service owns a static ScheduledThreadPoolExecutor whose worker survives the
    // game close; teardown calls it directly instead of creating a reflective field accessor.
    compileOnly(files("libs/$gameVersion-libraries/com/mojang/authlib/12.0.82/authlib-12.0.82.jar"))
    // ShaderSpvCache rebuilds a cached SPIR-V module: SPIRVModule frees its buffer natively in
    // close(), so the buffer must be an LWJGL allocation at position 0. LWJGL itself lives on the
    // supervisor loader at run time; this is compile-only.
    compileOnly(files("libs/$gameVersion-libraries/org/lwjgl/lwjgl/3.4.3/lwjgl-3.4.3.jar"))

    // The biome merge for declared ores parses the vanilla biome JSON (Gson): compile-time only,
    // the game's own libraries supply the same artifact at run time.
    compileOnly(libs.gson)

    // The command bridge walks the game's Brigadier tree; the game supplies brigadier at run time
    // (the client jar does not embed it).
    compileOnly(libs.brigadier)

    // Registry and holder reads walk `BuiltInRegistries`; every registry interface extends
    // `com.mojang.serialization.Keyable`, so that class must resolve at compile time even though the
    // members called are the game's own. The game supplies DataFixerUpper at run time.
    compileOnly(libs.datafixerupper)

    testImplementation(kotlin("test"))
    // The codec tests drive the real oml-native library through the SAME test wiring oml-core's
    // own compression tests use — shared as test fixtures instead of a per-project copy.
    testImplementation(testFixtures(project(":oml-core")))
}

tasks.withType<Test>().configureEach {
    // The codec tests call FFM restricted methods (oml-native downcalls via NativeManager); JDK 25+
    // wants the native-access grant, the same one OmlJvmContract carries for a real launch.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
