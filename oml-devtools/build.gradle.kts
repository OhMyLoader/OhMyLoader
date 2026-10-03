// Evaluated by the root OhMyLoader build only; publishes `org.ohmyloader:oml-devtools` for
// external consumers (oml-gradle resolves it from mavenLocal). The oml.convention.* plugins are
// not visible to included builds, so everything they provide is inlined below.

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    `maven-publish`
    // Versionless: the root build already puts this plugin on the classpath (apply false), and a
    // versioned request would fail with "already on the classpath with an unknown version".
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

kotlin {
    // Java 27 is not a preference: the plugin reads these classes compiled to Java 27 bytecode.
    jvmToolchain(27)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
}

group = providers.gradleProperty("oml_group").getOrElse("org.ohmyloader")
version = providers.gradleProperty("oml_version").getOrElse("0.0.0-SNAPSHOT")

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

publishing {
    // GitHub Packages is the interim Maven server until a dedicated one exists. Attached only
    // inside GitHub Actions (tag builds publish there); locally, publishToMavenLocal is the
    // whole story and nothing else is configured.
    if (providers.environmentVariable("GITHUB_ACTIONS").isPresent) {
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/OhMyLoader/OhMyLoader")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set(project.name)
                description.set(
                    "OhMyLoader — a Minecraft mod loader that runs unmodified mods on the modern " +
                        "Minecraft line (26.3) through a single, version-neutral API on one Java runtime."
                )
                url.set("https://github.com/OhMyLoader/OhMyLoader")

                licenses {
                    license {
                        name.set("GNU Affero General Public License v3.0")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                    }
                }
            }
        }
    }
}
