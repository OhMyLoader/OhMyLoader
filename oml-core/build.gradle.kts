plugins {
    id("oml.convention.kotlin-jvm")
    // Published because oml-core is what a build tool (and a launcher outside this repository) links
    // against; its `api(project(":oml-api"))` below must survive into the POM as a compile dependency.
    id("oml.convention.publishing")
    // Shared test wiring (NativeTestLibraries) travels as test fixtures instead of a drifting copy.
    `java-test-fixtures`
}

dependencies {
    api(project(":oml-api"))

    // The content track (declaration parsing, asset index) lives in its own module; core owns the
    // wiring (when packs are collected, where their namespaces land) but not the machinery.
    implementation(project(":oml-content"))

    implementation(libs.bundles.asm)

    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    // The compression tests call FFM restricted methods (oml-native downcalls via NativeManager);
    // JDK 25+ wants the native-access grant, the same one OmlJvmContract carries for a real launch.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Stamps `oml_version` into oml-core.properties — the runtime half of the single source of truth
// for OMLCore.VERSION; inputs.properties makes a version bump re-run processResources.
tasks.processResources {
    val stamped = mapOf("version" to providers.gradleProperty("oml_version").getOrElse("0.0.0-SNAPSHOT"))
    inputs.properties(stamped)
    filesMatching("oml-core.properties") {
        expand(stamped)
    }
}
