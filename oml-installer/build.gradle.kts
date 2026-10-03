plugins {
    id("oml.convention.kotlin-jvm")
    alias(libs.plugins.shadow)
    // The bundled version catalogue and Prism's mmc-pack.json are handled with kotlinx.serialization.
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
// library layout. Adding a version = one embed configuration + one bundledAdapters entry.
val embedShared = configurations.create("embedShared")

// Adapter only: its transitive closure IS the shared layer — embedding it transitively again
// would duplicate every shared jar.
val embed263 = configurations.create("embed263") { isTransitive = false }
val embedSnapshot = configurations.create("embedSnapshot") { isTransitive = false }

dependencies {
    // The installer still reuses devtools' downloader (game jar / libraries).
    implementation(project(":oml-devtools"))
    implementation(libs.kotlinxSerializationJson)
    // FlatLaf has no transitive dependencies and is only ever loaded on Java 27 (never by the
    // Java 8 bootstrap).
    implementation(libs.flatlaf)
    embedShared(project(":oml-core"))
    embedShared(project(":oml-launcher"))
    embed263(project(":oml-adapter-26_3"))
    embedSnapshot(project(":oml-adapter-snapshot"))

    testImplementation(kotlin("test"))
}

// Sync, not Copy: a Copy task never removes files that dropped out of its inputs, so a removed
// layer dependency would keep shipping in the fat jar forever.
val embedBundled = tasks.register<Sync>("embedBundled") {
    from(embedShared)
    from(embed263)
    from(embedSnapshot)
    into(layout.buildDirectory.dir("resources/main/lib"))
}

// Our zig-built C library, embedded under natives/<os>-<arch>/ (the tokens NativeManager resolves).
// Copying whatever exists instead of failing on a missing platform keeps a single-platform zig
// checkout installable; targets skip the download when the current platform is absent.
val omlNativeProject = project(":oml-native")

val embedOmlNative = tasks.register<Sync>("embedOmlNative") {
    group = "ohmyloader"
    description = "Embed built oml-native libraries (build output) into the installer's resources"
    dependsOn(omlNativeProject.tasks.named("buildOmlNative"))
    // Locals of this configuration lambda, not script-level vals: a captured script object breaks
    // the configuration cache.
    val osTokens = mapOf(
        "windows" to "windows", "linux" to "linux", "macosx" to "osx", "mingw" to "windows",
    )
    val archTokens = mapOf(
        "x64" to "x86_64", "x86_64" to "x86_64", "amd64" to "x86_64",
        "arm64" to "arm64", "aarch64" to "arm64",
    )
    from(rootProject.file("oml-native/build")) {
        include("*/*/release/oml-native.dll", "*/*/release/oml-native.so", "*/*/release/oml-native.dylib")
        eachFile {
            // relative path inside oml-native/build: <plat>/<arch>/release/oml-native.<ext>
            val segments = relativePath.pathString.split("/", "\\")
            val os = osTokens[segments[0].lowercase()]
            val arch = archTokens[segments[1].lowercase()]
            if (os == null || arch == null) {
                exclude()
            } else {
                relativePath = RelativePath(true, "$os-$arch", segments[3])
            }
        }
    }
    into(layout.buildDirectory.dir("resources/main/natives"))
}

// PCL2 / HMCL populate ${natives_directory} exclusively by extracting `natives-<classifier>` jars
// from the version JSON's libraries, so the compliant distribution form is a library entry with a
// natives block backed by such a jar (produced and published by :oml-native; embedded here for
// install time).
val embedNativesJars = tasks.register<Sync>("embedNativesJars") {
    group = "ohmyloader"
    description = "Embed :oml-native's packaged natives-<classifier> jars into the installer's resources"
    dependsOn(omlNativeProject.tasks.named("packageOmlNativeJars"))
    from(omlNativeProject.layout.buildDirectory.dir("natives-jars"))
    into(layout.buildDirectory.dir("resources/main/natives-jars"))
}

// The version catalogue (oml-versions.json) is generated from this map: game version -> adapter
// artifact id. The id is load-bearing twice — it names the embed configuration's dependency and
// it is what the installer matches the user's version choice against (the only per-version
// artifact under lib/). Adding a version = one entry here + one embed configuration below.
// `snapshot` is an alias: the installer resolves it to the manifest's latest snapshot at install
// time (AssetDownloader.resolveLatestSnapshotId) and installs under the real id.
val bundledAdapters = mapOf(
    "26.3" to "oml-adapter-26_3",
    "snapshot" to "oml-adapter-snapshot",
)

/** Java major version OML requires. Written into the launcher version JSON and into every user-facing message. */
val requiredJavaMajor = 27

val generateVersionCatalog = tasks.register("generateVersionCatalog") {
    group = "ohmyloader"
    description = "Write the version catalogue (oml-versions.json) the installer reads at runtime"

    // Locals of this configuration lambda, not script-level vals: the configuration cache cannot
    // serialize a Kotlin DSL script, so a task action may only close over plain values.
    val versions: List<String> = bundledAdapters.keys.toList()
    // Locals of this configuration lambda, not script-level vals: the configuration cache cannot
    // serialize a Kotlin DSL script, so a task action may only close over plain values.
    val adapters: Map<String, String> = bundledAdapters
    val resourcesDir: File = layout.buildDirectory.dir("resources/main").get().asFile
    val javaMajor: Int = requiredJavaMajor

    outputs.dir(resourcesDir)
    // the catalogue content is an input: a changed version list must re-run this task
    inputs.property("bundledVersions", versions.joinToString(","))
    inputs.property("javaMajor", javaMajor.toString())

    doLast {
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

tasks.processResources {
    dependsOn(embedBundled, embedOmlNative, embedNativesJars, generateVersionCatalog)
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
