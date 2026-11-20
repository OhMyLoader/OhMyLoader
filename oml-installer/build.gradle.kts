import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkerExecutor
import oml.convention.omlGameVersion

plugins {
    id("oml.convention.kotlin-jvm")
    alias(libs.plugins.shadow)
    // The bundled version catalog and Prism's mmc-pack.json are handled with kotlinx.serialization.
    alias(libs.plugins.kotlinPluginSerialization)
}

// The entry class lives in its own source set, compiled with --release 8 and an EMPTY compile
// classpath: an old JRE must be able to run it and print "you need Java 27" before it dies on the
// real installer's UnsupportedClassVersionError, and the empty classpath turns an accidental
// dependency on the Java 27 code into a build error.
sourceSets {
    create("bootstrap") {
        java.setSrcDirs(listOf("src/bootstrap/java"))
        compileClasspath = files()
        runtimeClasspath = files()
    }
}

tasks.named<JavaCompile>("compileBootstrapJava") {
    options.release.set(8)
    // silences only javac's "--release 8 is obsolete" warning
    options.compilerArgs.addAll(listOf("-Xlint:-options"))
}

tasks.jar { from(sourceSets.getByName("bootstrap").output) }

// The runtime layer is embedded flat under lib/: the shared jars every version needs (core, api,
// launcher, asm, kotlin — the transitive closure of core) once, plus exactly one adapter jar per
// game version, the only per-version artifact. The adapter's file name (`oml-adapter-<version
// with _ for .>`) is what the installer matches the user's version choice against. A fat jar may
// not merge the layer: the jars must stay separate files to be extracted 1:1 into the launcher's
// library layout.
val embedShared = configurations.create("embedShared")

// The version catalog (oml-versions.json) and the embed wiring are BOTH generated from this
// map: game version -> adapter artifact id. The artifact id doubles as the project name and as the
// file-name segment (`oml-adapter-<version with _>`) the installer matches the user's version
// choice against. There is exactly one adapter now — the loader tracks the newest game line — so
// this map is a single entry; adding a second version would be a deliberate policy change, not an
// option.
val bundledAdapters = mapOf(
    omlGameVersion() to "oml-adapter",
)

// One embed configuration per bundled version, derived from `bundledAdapters` — the single
// data source for "which versions exist". Adapter-only (not transitive): its
// transitive closure IS the shared layer, embedding it transitively again would duplicate every
// shared jar.
fun embedConfigurationFor(versionKey: String) =
    configurations.create("embedAdapter_${versionKey.replace('-', '_')}") { isTransitive = false }

val adapterEmbeds: Map<String, Configuration> = bundledAdapters.keys
    .associateWith(::embedConfigurationFor)

dependencies {
    // The installer still reuses devtools' downloader (game jar / libraries).
    implementation(project(":oml-devtools"))
    implementation(libs.kotlinxSerializationJson)
    // FlatLaf has no transitive dependencies and is only ever loaded on Java 27 (never by the
    // Java 8 bootstrap).
    implementation(libs.flatlaf)
    embedShared(project(":oml-core"))
    embedShared(project(":oml-launcher"))
    // The game-facing API travels in the shared layer: a mod compiles against it and the adapter's
    // event payloads ARE its types, so it must be the one copy the session loader defines.
    embedShared(project(":oml-api"))
    for ((version, adapter) in bundledAdapters) {
        add(adapterEmbeds.getValue(version).name, project(":$adapter"))
    }

    testImplementation(kotlin("test"))
}

// Sync, not Copy: a Copy task never removes files that dropped out of its inputs, so a removed
// layer dependency would keep shipping in the fat jar forever.
val embedBundled = tasks.register<Sync>("embedBundled") {
    group = "ohmyloader"
    description = "Embed the shared loader layers and every bundled adapter into the installer's lib/ resources"
    from(embedShared)
    for (embed in adapterEmbeds.values) {
        from(embed)
    }
    into(layout.buildDirectory.dir("resources/main/lib"))
}

// The native library is built from the in-repo crate `oml-native` (Rust, cargo). PCL2 / HMCL
// populate ${natives_directory} by extracting the `natives-<classifier>` jars declared in the
// version JSON's libraries, so the installer embeds exactly the jars this build produces;
// InstallModel extracts the platform library from them at install time.
val omlNativeVersion = providers.gradleProperty("oml_native_version").get()
val omlNativeCrate = rootProject.projectDir.resolve("oml-native")
val omlNativeDist = omlNativeCrate.resolve("dist")

data class OmlNativeTarget(val classifier: String, val triple: String, val fileName: String) : java.io.Serializable

val omlNativeTargets = listOf(
    OmlNativeTarget("windows", "x86_64-pc-windows-msvc", "oml-native.dll"),
    OmlNativeTarget("windows-arm64", "aarch64-pc-windows-msvc", "oml-native.dll"),
    OmlNativeTarget("linux", "x86_64-unknown-linux-gnu", "oml-native.so"),
    OmlNativeTarget("linux-arm64", "aarch64-unknown-linux-gnu", "oml-native.so"),
    OmlNativeTarget("osx", "x86_64-apple-darwin", "oml-native.dylib"),
    OmlNativeTarget("osx-arm64", "aarch64-apple-darwin", "oml-native.dylib"),
)

// A config-cache-safe task class: the action reads only injected properties, never the script
// receiver. Builds one classifier per target: cargo with the target triple, then jars the library
// with the version stamp InstallModel parses back into the version JSON entry. A target whose
// toolchain is not installed is skipped with a note — the release gate installs every target,
// while a local host-only build produces its own classifier and the Sync below ships what exists.
abstract class PackageOmlNativeJarsTask : DefaultTask() {
    @get:Internal
    abstract val crateDir: RegularFileProperty

    @get:Internal
    abstract val distDir: RegularFileProperty

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val targets: ListProperty<OmlNativeTarget>

    @TaskAction
    fun buildAndPackage() {
        val crateDir = crateDir.get().asFile
        val distDir = distDir.get().asFile
        distDir.mkdirs()
        for (target in targets.get()) {
            val build = ProcessBuilder("cargo", "build", "--release", "--target", target.triple)
                .directory(crateDir)
                .redirectErrorStream(true)
                .start()
            val output = build.inputStream.buffered().readBytes().toString(Charsets.UTF_8)
            if (build.waitFor() != 0) {
                println("[oml] oml-native ${target.classifier}: cargo build failed (target not installed?) — skipped: $output")
                continue
            }
            // cargo names the cdylib after the crate identifier (oml_native.dll, underscore); the
            // packaged jar entry carries the hyphenated name the loader resolves.
            val lib = crateDir.resolve("target/${target.triple}/release/oml_native.${target.fileName.substringAfterLast('.')}")
            if (!lib.isFile) {
                println("[oml] oml-native ${target.classifier}: cargo produced no ${target.fileName} — skipped")
                continue
            }
            val out = distDir.resolve("oml-native-${version.get()}-natives-${target.classifier}.jar")
            JarOutputStream(out.outputStream()).use { jar ->
                jar.putNextEntry(JarEntry(target.fileName))
                jar.write(lib.readBytes())
                jar.closeEntry()
            }
            println("[oml] oml-native packaged: ${out.name} (${out.length() / 1024} KB)")
        }
    }
}

val packageOmlNativeJars = tasks.register<PackageOmlNativeJarsTask>("packageOmlNativeJars") {
    group = "ohmyloader"
    description = "Build the native crate for every installed target and package the natives-<classifier> jars"
    crateDir.set(omlNativeCrate)
    distDir.set(omlNativeDist)
    version.set(omlNativeVersion)
    targets.set(omlNativeTargets)
}

// Sync, not Copy: a classifier dropped from the list must stop shipping. The jar file names carry
// the publication version, which InstallModel parses back into the version JSON entry.
val embedNativesJars = tasks.register<Sync>("embedNativesJars") {
    dependsOn(packageOmlNativeJars)
    group = "ohmyloader"
    description = "Embed the built natives-<classifier> jars into the installer's resources"
    from(omlNativeDist) { include("oml-native-$omlNativeVersion-natives-*.jar") }
    into(layout.buildDirectory.dir("resources/main/natives-jars"))
}

// The installer used to embed a plain copy of every library under natives/<os>-<arch>/. The
// version JSON's natives jars are the only shape now, so a checkout that still carries the old
// build output would otherwise keep shipping a stale library. Sync with no sources empties it.
val clearLegacyNatives = tasks.register<Sync>("clearLegacyNatives") {
    group = "ohmyloader"
    description = "Remove the pre-publication plain natives copies from the installer's resources"
    into(layout.buildDirectory.dir("resources/main/natives"))
}

/** Java major version OML requires. Written into the launcher version JSON and into every user-facing message. */
val requiredJavaMajor = 27

val generateVersionCatalog = tasks.register("generateVersionCatalog") {
    group = "ohmyloader"
    description = "Write the version catalog (oml-versions.json) the installer reads at runtime"

    // Locals of this configuration lambda, not script-level vals: the configuration cache cannot
    // serialize a Kotlin DSL script, so a task action may only close over plain values.
    val versions: List<String> = bundledAdapters.keys.toList()
    // Locals of this configuration lambda, not script-level vals: the configuration cache cannot
    // serialize a Kotlin DSL script, so a task action may only close over plain values.
    val adapters: Map<String, String> = bundledAdapters
    val resourcesDir: File = layout.buildDirectory.dir("resources/main").get().asFile
    val javaMajor: Int = requiredJavaMajor
    // The Java 8 bootstrap cannot read this build's constants (empty compile classpath), so it
    // carries its own copy of the Java requirement; the build fails when the two drift.
    val bootstrapSource: File = layout.projectDirectory
        .file("src/bootstrap/java/org/ohmyloader/installer/InstallerBootstrap.java").asFile
    val javaMajorConst = Regex("""REQUIRED_JAVA_MAJOR\s*=\s*(\d+)""")

    outputs.dir(resourcesDir)
    // the catalog content is an input: a changed version list must re-run this task
    inputs.property("bundledVersions", versions.joinToString(","))
    inputs.property("javaMajor", javaMajor.toString())
    inputs.file(bootstrapSource)

    doLast {
        // single source of truth: the catalog value wins, the bootstrap copy must agree
        val declared = javaMajorConst.find(bootstrapSource.readText())
            ?.groupValues?.get(1)?.toIntOrNull()
        require(declared == javaMajor) {
            "InstallerBootstrap's REQUIRED_JAVA_MAJOR ($declared) disagrees with requiredJavaMajor ($javaMajor)"
        }

        // A file an older build produced but this one no longer does must not keep being packaged.
        File(resourcesDir, "names").deleteRecursively()

        val json = buildString {
            appendLine("{")
            appendLine("  \"format\": 1,")
            appendLine("  \"requiredJavaMajor\": $javaMajor,")
            appendLine("  \"versions\": [")
            versions.forEachIndexed { index, version ->
                appendLine("    {")
                appendLine("      \"version\": \"$version\",")
                appendLine("      \"javaMajor\": $javaMajor,")
                // no comma here: the separator belongs on the closing brace below, where the
                // object actually ends
                appendLine("      \"adapter\": \"${adapters[version]}\"")
                appendLine(if (index == versions.lastIndex) "    }" else "    },")
            }
            appendLine("  ]")
            appendLine("}")
        }
        File(resourcesDir, "oml-versions.json").writeText(json, Charsets.UTF_8)
        logger.lifecycle("[oml-installer] wrote oml-versions.json (${versions.size} versions)")
    }
}

// The generators write into subdirectories of this task's own output directory
// (resources/main/{lib,natives,natives-jars}); dependsOn schedules them and mustRunAfter states the
// direction explicitly, so a refactor that drops the dependency cannot silently let processResources
// run first. Task references only — configuration-cache safe.
val resourceGenerators = listOf(embedBundled, embedNativesJars, clearLegacyNatives, generateVersionCatalog)

tasks.processResources {
    dependsOn(resourceGenerators)
    mustRunAfter(resourceGenerators)
}

tasks.shadowJar {
    // The fat jar is the only artifact this repository ships; emit it straight into dist/ under
    // the shared oml_version (the "all" classifier disappears with it).
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("dist"))
    // no publishing convention here, so project.version is unset
    archiveFileName.set("oml-installer-${providers.gradleProperty("oml_version").get()}.jar")
    // shadowJar only takes main by default; the Java 8 bootstrap must reach the fat jar too
    from(sourceSets.getByName("bootstrap").output)
    manifest {
        // the Java 8 bootstrap, NOT the Kotlin GUI: it must be loadable by an old JRE
        attributes["Main-Class"] = "org.ohmyloader.installer.InstallerBootstrap"
        // FlatLaf loads a native library: a restricted method on Java 27, blocked in a future
        // release unless granted — this attribute covers a plain `java -jar` launch.
        attributes["Enable-Native-Access"] = "ALL-UNNAMED"
    }
    // keep the adapter / devtools ServiceLoader registrations (IAdapter SPI) in the fat jar
    mergeServiceFiles()
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
