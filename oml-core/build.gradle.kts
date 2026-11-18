plugins {
    id("oml.convention.kotlin-jvm")
    // Published because oml-core is what a build tool (and a launcher outside this repository) links
    // against; its `api(project(":oml-api-core"))` below must survive into the POM as a compile dependency.
    id("oml.convention.publishing")
    // Shared test wiring (NativeTestLibraries) travels as test fixtures instead of a drifting copy.
    `java-test-fixtures`
    // The expression language's parser is generated from the grammar at build time.
    antlr
}

dependencies {
    api(project(":oml-api-core"))

    // The content track (declaration parsing, asset index) lives in its own module; core owns the
    // wiring (when packs are collected, where their namespaces land) but not the machinery.
    implementation(project(":oml-content"))

    implementation(libs.bundles.asm)

    // Expression language: generated parser (build tool) + its runtime. Both come from the same
    // pinned version, and the runtime is embedded into the installed loader with oml-core.
    antlr(libs.antlrTool)
    implementation(libs.antlrRuntime)

    testImplementation(kotlin("test"))
}

tasks.generateGrammarSource {
    arguments.addAll(listOf("-package", "org.ohmyloader.core.expression.grammar", "-visitor"))
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

// The suite exercises the pure-Java path by default: it is always available, and it is the
// reference the native differential test compares against. The native walk is enabled explicitly
// by that test (and by the benchmarks) after the library has been built.
tasks.test {
    systemProperty("oml.scan.native", "off")
}

// Hot-path numbers are machine-relative, so this is on demand and never part of `build`/`check`.
// It still exits non-zero when a scenario's result or handler-hit check fails.
tasks.register<JavaExec>("injectionBenchmark") {
    group = "verification"
    description = "Measures per-call overhead and allocation of the injection bridge."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.ohmyloader.core.benchmark.InjectionBenchmark")
}

tasks.register<JavaExec>("transformBenchmark") {
    group = "verification"
    description = "Measures per-class transform cost: parse, rule application, write-back."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.ohmyloader.core.benchmark.TransformBenchmark")
}

tasks.register<JavaExec>("scanBenchmark") {
    group = "verification"
    description = "Measures cold/warm startup scan cost on a synthetic 300-jar pack."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.ohmyloader.core.benchmark.ScanBenchmark")
}

tasks.register<JavaExec>("nativeScanBenchmark") {
    group = "verification"
    description = "Measures the oml-native jar-scan prototype on the same synthetic pack."
    dependsOn(":oml-native:buildOmlNative")
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("org.ohmyloader.core.benchmark.NativeScanBenchmark")
}
