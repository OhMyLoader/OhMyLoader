plugins {
    id("oml.convention.kotlin-jvm")
    // Published because oml-core's POM declares it as a runtime dependency: the runtime layer
    // (installer embed / installLauncher / Gradle dev runs) resolves it transitively by coordinate.
    id("oml.convention.publishing")
}

dependencies {
    implementation(project(":oml-api"))

    testImplementation(kotlin("test"))
}
