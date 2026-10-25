import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.gradle.ext.Gradle
import org.jetbrains.gradle.ext.runConfigurations
import org.jetbrains.gradle.ext.settings

plugins {
    id("org.jetbrains.gradle.plugin.idea-ext") version "1.4.1"
    // apply false: only puts the plugin on the build classpath — oml-devtools requests it
    // versionless, and a second versioned request would fail with
    // "already on the classpath with an unknown version".
    alias(libs.plugins.kotlinJvm) apply false
}

idea {
    project {
        settings {
            runConfigurations {
                register("Publish Installer", Gradle::class.java) {
                    taskNames = listOf(":oml-installer:shadowJar")
                }
            }
        }
    }
}

// The release gate compares rebuilt Maven artifacts with the staged ones by SHA-256. The archive
// timestamp and entry order must therefore be inputs, not properties of the machine or the clock.
subprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}
