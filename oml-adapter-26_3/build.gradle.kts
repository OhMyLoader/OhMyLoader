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
    // The thin entry point references the shared implementation only; the one game class named
    // here is the command handover: V26_3EventBridge unwraps the dispatcher from the Commands
    // instance (26.3 keeps it on `getDispatcher`), a member the shared layer must not name
    // because its bytecode ships into snapshot installs too — the same layering the snapshot
    // module's SnapshotEventBridge uses.
    implementation(project(":oml-adapter-common"))
    compileOnly(files("libs/26.3-client.jar"))
    // EventLoopGroupHolder's group type: the game jar only carries the holder, Netty ships in
    // the installer's libraries layer.
    compileOnly(
        files(
            "libs/26.3-libraries/io/netty/netty-common/4.2.16.Final/netty-common-4.2.16.Final.jar",
            "libs/26.3-libraries/io/netty/netty-transport/4.2.16.Final/netty-transport-4.2.16.Final.jar",
        ),
    )
    // The discovery service owns a static ScheduledThreadPoolExecutor whose worker survives the
    // game close; teardown calls it directly instead of creating a reflective field accessor.
    compileOnly(files("libs/26.3-libraries/com/mojang/authlib/10.0.77/authlib-10.0.77.jar"))
    // The pagination consults the tab manager's resolved order; the manager jar ships in the
    // same installer layer, so this only needs to be on the compile path.
    compileOnly(project(":oml-api-26_3"))
    // The pagination rules rewrite method bodies directly (raw ASM)
    compileOnly(libs.bundles.asm)
    // `Commands.dispatcher` hands back a Brigadier dispatcher; the game supplies brigadier at run time.
    compileOnly(libs.brigadier)
    // Registry extends com.mojang.serialization.Keyable - its supertypes must resolve on the compile path.
    compileOnly(libs.datafixerupper)

    // HookShapeTest walks the live rule sets and reads the real game jar with ASM.
    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.asm)
    testImplementation(files("libs/26.3-client.jar"))
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
    versionId = "26.3"
}