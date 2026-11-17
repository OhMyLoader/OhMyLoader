package oml.convention

import java.util.Properties
import org.gradle.api.Project

/**
 * The Minecraft version this repository tracks, read straight from the root `gradle.properties`.
 *
 * Deliberately **not** a Gradle property lookup: `-Poml_game_version=…` must not be able to move
 * the game version out from under the hooks and shape tests that were built against one concrete
 * jar. One file, one value, no task parameter.
 */
fun Project.omlGameVersion(): String {
    val file = rootProject.file("gradle.properties")
    val properties = Properties()
    file.inputStream().use(properties::load)
    return properties.getProperty("oml_game_version")?.takeIf { it.isNotBlank() }
        ?: error("oml_game_version is missing from ${file.path} — it is the single source of truth for the tracked game version")
}
