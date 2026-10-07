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
    testImplementation(kotlin("test"))
    testImplementation(files(gameJar))
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(":oml-adapter-26_3:fetchClientJar")
}
