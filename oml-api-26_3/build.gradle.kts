// The 26.3-facing mod API: every public type here names game classes directly, which is why it is a
// module of its own rather than more packages in `oml-api` — a mod that wants version-neutral code
// keeps compiling against `oml-api` alone, and one that embraces 26.3 adds this artifact.
//
// Its classes MUST be defined by the OML main loader (see OMLClassLoader.CHILD_FIRST_PREFIXES): a
// parent-first module naming `net.minecraft.*` would resolve a second, untransformed copy of the game
// classes and every hand-off between mod and game would fail to cast.
plugins {
    id("oml.convention.kotlin-jvm")
    id("oml.convention.publishing")
}

// The same jar the 26.3 adapter compiles against, fetched by that module's task — one local copy of a
// 40 MB jar instead of two. compileOnly: at run time the game supplies these classes, and a mod gets
// them from its own launcher-provided game jar (the oml-gradle plugin wires that side).
val gameJar = file("$rootDir/oml-adapter-26_3/libs/26.3-client.jar")

dependencies {
    // The version-neutral base this builds on: Event / EventDefinition, the injection and content DSLs.
    api(project(":oml-api"))

    compileOnly(files(gameJar))
    // The game supplies these at run time; they are on the compile path only because game types reach
    // into them — `Component` is a `com.mojang.brigadier.Message`, and every registry interface extends
    // `com.mojang.serialization.Keyable`. Without them the game types here do not resolve their own
    // supertypes.
    compileOnly(libs.brigadier)
    compileOnly(libs.datafixerupper)
    testImplementation(kotlin("test"))
    testImplementation(files(gameJar))
    testImplementation(libs.brigadier)
    testImplementation(libs.datafixerupper)
    // Building a packet instance runs its static STREAM_CODEC, whose types come from netty — the game
    // supplies them at run time, and the test JVM has to as well.
    testImplementation(libs.nettyCodecBase)
    testImplementation(libs.nettyTransport)
    testRuntimeOnly(libs.joml)
}

// `Identifier`'s static init links brigadier, DataFixerUpper, JOML and Mojang's util, so a test that
// parses a real id needs the game's own library tree. It is the copy the 26.3 adapter already fetches —
// one download per version, and the version JSON is the authoritative list rather than a hand-pinned one.
val gameLibraries = file("$rootDir/oml-adapter-26_3/libs/26.3-libraries")
dependencies.add(
    "testRuntimeOnly",
    fileTree(gameLibraries) {
        include("**/*.jar")
        exclude("**/io/netty/**")
    },
)

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(":oml-adapter-26_3:fetchClientJar")
}

tasks.withType<Test>().configureEach {
    dependsOn(":oml-adapter-26_3:fetchGameLibraries")
}
