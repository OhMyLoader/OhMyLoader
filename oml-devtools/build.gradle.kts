plugins {
    id("oml.convention.kotlin-jvm")
    // Published for external consumers (oml-gradle resolves org.ohmyloader:oml-devtools from
    // mavenLocal before it can build), the same Maven surface as the sibling modules.
    id("oml.convention.publishing")
    alias(libs.plugins.kotlinPluginSerialization)
}

// Stamp this module's version into the jar: AssetDownloader.USER_AGENT reads it back, so the HTTP
// user agent reports the build that made the request instead of a literal that drifts from it.
tasks.processResources {
    val devtoolsVersion = project.version.toString()
    inputs.property("omlVersion", devtoolsVersion)
    filesMatching("oml-devtools.properties") {
        expand("version" to devtoolsVersion)
    }
}

dependencies {
    implementation(libs.kotlinxSerializationJson)

    testImplementation(kotlin("test"))
}
