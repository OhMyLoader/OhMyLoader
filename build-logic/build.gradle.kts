// The repository's build logic, mounted by the root build via
// `pluginManagement { includeBuild("build-logic") }`; not automatically visible to included builds.
// The Kotlin Gradle plugin cannot come from the kotlin-dsl machinery itself: a convention that calls
// `kotlin("jvm")` needs the plugin on this build's compile classpath.
plugins {
    `kotlin-dsl`
}

kotlin {
    jvmToolchain(27)
}

dependencies {
    implementation(libs.kotlinGradlePlugin)
}
