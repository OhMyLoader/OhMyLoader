package oml.convention

import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("oml.convention.kotlin-jvm")
}

// Version-difference declaration entry point: `omlAdapter { ... }`
val oml = extensions.create<OmlAdapterExtension>("omlAdapter")

// Build-time only; never on the game runtime classpath.
val devtools = configurations.create("devtools")
val installer = configurations.create("installer")

dependencies {
    // The adapter implements oml-core's IAdapter SPI; the sources compile against it.
    implementation(project(":oml-core"))
    // MinecraftContentRegistry extends oml-content's AbstractContentRegistry directly.
    implementation(project(":oml-content"))
    runtimeOnly(project(":oml-launcher"))

    devtools(project(":oml-devtools"))
    installer(project(":oml-installer"))
}

// `omlAdapter { }` is set in the module scripts after this plugin is applied; read only at the end
// of the configuration phase to avoid reading defaults too early.
afterEvaluate {
    val versionId = oml.versionId.get()
    val clientJar = file("libs/$versionId-client.jar")
    val stdoutEnc = listOf("-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8")

    // This convention provides only the two build-time entry points an adapter module needs:
    // fetch the vanilla jar it hooks, and install the loader into a launcher game directory.
    // Everything that *runs* the game lives in the oml-gradle plugin.

    // The vanilla client jar, kept locally (gitignored): the adapter compiles against it (26.x jars
    // are unobfuscated, so names match exactly) and the shape tests read it with ASM. At run time
    // the game itself supplies these classes.
    val fetchClientJar = tasks.register<JavaExec>("fetchClientJar") {
        group = "ohmyloader"
        description = "Download the main Minecraft $versionId client jar into libs/ (only if missing)"
        classpath(devtools)
        mainClass.set("org.ohmyloader.devtools.AssetDownloader")
        workingDir = file("run")
        jvmArgs(stdoutEnc)
        args("--clientJar", clientJar.absolutePath, versionId)
        outputs.file(clientJar)
        inputs.property("gameVersion", versionId)
    }

    // The module script's file dependency on the jar cannot carry builtBy (it evaluates before this
    // afterEvaluate), so every compile task declares the dependency instead: a fresh checkout
    // fetches on first compile, and afterward the task is up-to-date and adds nothing.
    tasks.withType<JavaCompile>().configureEach {
        dependsOn(fetchClientJar)
    }
    tasks.withType<KotlinCompile>().configureEach {
        dependsOn(fetchClientJar)
    }

    // Build-time packaging tool: write a launcher version with inheritsFrom and copy the loader
    // layer into libraries/ — the same installer the GUI ships, exercised from the command line.
    // The game itself is not downloaded here; the launcher fills in runtimes / natives / assets.
    tasks.register<JavaExec>("installLauncher") {
        group = "ohmyloader"
        description =
            "Install OML into a launcher game directory (default run/install; override with -PomlInstallDir; " +
                "use -PomlInstallId to give a second instance of the same game version another id; use -PomlSide=server to install a dedicated-server instance)"
        classpath(installer)
        mainClass.set("org.ohmyloader.installer.Installer")
        workingDir = file("run")
        jvmArgs(stdoutEnc)

        val ourProjects = listOf(project(":oml-launcher"), project(":oml-core"), project(":oml-api"), project)
        dependsOn(ourProjects.map { it.tasks.named("jar") })
        val ourJars = ourProjects.map { it.tasks.named<Jar>("jar").flatMap { t -> t.archiveFile } }

        // Through a provider so the configuration cache can serialize this task.
        val dependencyJars = configurations.named("runtimeClasspath")
            .flatMap { it.incoming.artifacts.resolvedArtifacts }
            .map { artifacts ->
                artifacts.filter { it.id.componentIdentifier is ModuleComponentIdentifier }.map { it.file }
            }

        // hoisted out of the action: a captured java.io.File is config-cache-serializable, a Project is not
        val defaultInstallDir = file("run/install")

        doFirst {
            val installDir = providers.gradleProperty("omlInstallDir")
                .getOrElse(defaultInstallDir.absolutePath)
            val installId = providers.gradleProperty("omlInstallId").getOrElse("$versionId-OML")
            val taskArgs = mutableListOf(
                "--target", "standard",
                "--version", versionId,
                "--dir", installDir,
                "--id", installId,
            )

            // Decides which mods directory to create and is pinned into the version JSON.
            val isolation = providers.gradleProperty("omlIsolation").orNull?.lowercase()
                ?: throw GradleException("installLauncher requires -PomlIsolation=true|false (mandatory)")
            if (isolation != "true" && isolation != "false") throw GradleException("-PomlIsolation accepts only true / false")
            taskArgs += listOf("--isolation", isolation)

            val side = providers.gradleProperty("omlSide").orNull?.lowercase() ?: "client"
            if (side != "client" && side != "server") throw GradleException("-PomlSide accepts only client / server")
            taskArgs += listOf("--side", side)
            if (side == "server") {
                // The person running the Gradle task is the one deciding; a real deployment does this by hand.
                taskArgs += listOf("--accept-eula")
            }

            (ourJars.map { it.get().asFile } + dependencyJars.get())
                .forEach { taskArgs += listOf("--layer-jar", it.absolutePath) }

            args(taskArgs)
        }
    }

    // A fresh clone has no gitignored run/, and Gradle fails a task whose working directory does
    // not exist. Resolved at configuration time on purpose: project.file() inside a task action
    // would hold a Project reference, which the configuration cache refuses to serialize.
    val runDir = file("run")
    fetchClientJar.configure { doFirst { runDir.mkdirs() } }

    // HookShapeTest asserts the hook rules against the real client jar, so fetching it is part of
    // the test task graph; the tests locate the jar through this system property.
    tasks.withType<Test>().configureEach {
        dependsOn(fetchClientJar)
        systemProperty("oml.clientJar", clientJar.absolutePath)
    }
}
