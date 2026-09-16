plugins {
    `java-library`
    // This module is pure Java on purpose (OMLBootstrap is the entry point a Gradle run task and a
    // launcher's version JSON name directly), so it takes the publishing convention without the
    // kotlin-jvm one. `from(components["java"])` is identical for both.
    id("oml.convention.publishing")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(27)
    }
}

// The manifest flags mirror OmlJvmContract (the oml-gradle plugin's command line) so that a bare
// `java -jar oml-launcher.jar` starts the dedicated server correctly even where no version JSON
// exists to hold those flags (the installer's bootstrap-server directory).
tasks.jar {
    manifest {
        attributes["Main-Class"] = "org.ohmyloader.launcher.OMLBootstrap"
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
        attributes["Add-Opens"] = listOf(
            "java.base/java.lang",
            "java.base/java.lang.reflect",
            "java.base/java.util",
            "java.base/java.nio",
        ).joinToString(" ")
    }
}

dependencies {
    compileOnly(project(":oml-core"))
}