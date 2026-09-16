// The repository's build logic, mounted by the root build via
// `pluginManagement { includeBuild("build-logic") }`; not automatically visible to included builds.
// The Kotlin Gradle plugin cannot come from the kotlin-dsl machinery itself: a convention that calls
// `kotlin("jvm")` needs the plugin on this build's compile classpath. kotlin-dsl compiles with the
// compiler embedded in the Gradle distribution, whose target lags the repository's own — hence the
// JVM_26 fallback below (safe: Gradle runs this on the 27 toolchain; modules compile to 27).
plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(27)

    // The loader side of the oml-native build declaration; the oml-gradle plugin repository
    // carries its own verbatim copy (the two repositories cannot share classes — sync by hand).
    sourceSets.getByName("main").kotlin.srcDir(file("oml-native-build"))
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
}
