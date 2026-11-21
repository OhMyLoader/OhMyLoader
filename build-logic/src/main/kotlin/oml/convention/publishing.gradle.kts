package oml.convention

// Publishing convention for modules consumed from outside this build, by Maven coordinate.
// from(components["java"]) keeps oml-core's api(project(":oml-api-core")) edge in the POM as a
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
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set(project.name)
                description.set(
                    "OhMyLoader — a Minecraft mod loader that runs unmodified mods on the modern " +
                        "Minecraft line through a single, version-neutral API on one Java runtime.",
                )
                url.set("https://github.com/OhMyLoader/OhMyLoader")

                licenses {
                    license {
                        name.set("GNU Affero General Public License v3.0-or-later")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.txt")
                    }
                }
            }
        }
    }
}
