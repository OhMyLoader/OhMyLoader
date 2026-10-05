package oml.convention

// Publishing convention for modules consumed from outside this build, by Maven coordinate.
// from(components["java"]) keeps oml-core's api(project(":oml-api")) edge in the POM as a
// compile-scope dependency. Build-time tools (oml-installer, oml-testmod) do not apply it.

plugins {
    `maven-publish`
}

// Read straight from the providers: both properties are defined once in the root gradle.properties.
val omlGroup = providers.gradleProperty("oml_group").getOrElse("org.ohmyloader")
val omlVersion = providers.gradleProperty("oml_version").getOrElse("0.0.0-SNAPSHOT")

group = omlGroup
version = omlVersion

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
                        "Minecraft line (26.3) through a single, version-neutral API on one Java runtime.",
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
