package oml.convention

// The baseline every Kotlin module shares: Kotlin JVM plugin, Java 27 toolchain (OML runs on 27
// only), one test-reporting shape. oml-installer's Java 8 bootstrap source set does not apply this.

import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(27)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events(TestLogEvent.FAILED, TestLogEvent.PASSED, TestLogEvent.SKIPPED)
    }
}