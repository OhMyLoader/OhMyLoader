// The oml-native module: the zig build, the launcher-facing `natives-<os>` jar packaging, and the
// Maven publication. The installer and the oml-gradle plugin are consumers of these outputs, not
// owners of the toolchain.

plugins {    // kotlin-jvm only to put the convention plugin's BuildOmlNativeTask type on the compile
    // classpath; the empty Kotlin compile is the price.
    id("oml.convention.kotlin-jvm")
    `maven-publish`
}

// `build/` belongs to the zig tree here (its layout IS the deploy contract: NativeTestLibraries and
// OmlNativeLayout resolve build/<plat>/<arch>/release/oml-native.<ext>), so this module's Gradle
// outputs move out of its way.
layout.buildDirectory.set(layout.projectDirectory.dir("gradle-build"))

// A fingerprinted task, not a manual prerequisite: editing a .zig file rebuilds before any consumer
// sees the output. zig's default is Debug, so ReleaseFast must be explicit.
val buildOmlNative = tasks.register<oml.build.BuildOmlNativeTask>("buildOmlNative") {
    group = "ohmyloader"
    description = "Rebuild the oml-native library with zig (skipped when sources are unchanged)"
    nativeProjectDir.set(layout.projectDirectory)
    buildOutputDir.set(layout.projectDirectory.dir("build"))
    sourceFiles.from(fileTree("src"))
    sourceFiles.from("build.zig")
    sourceFiles.from("build.zig.zon")
}

// One jar per OS in the standard Maven `natives-<classifier>` shape (library at the jar root), plus
// the minimal main jar — the form PCL2 / HMCL extract from a version JSON's `natives` block and the
// oml-gradle plugin resolves by coordinate. Standard Jar tasks: incrementality and caching for free.
// A slot whose library was not built is skipped (single-platform checkout installable); publishing
// that slot then fails explicitly at artifact resolution instead of shipping an empty jar.
val omlVersion = providers.gradleProperty("oml_version").get()
val nativesJarsDir = layout.buildDirectory.dir("natives-jars")

val packageOmlNativeJar = tasks.register<Jar>("packageOmlNativeJar") {
    group = "ohmyloader"
    description = "Package the minimal oml-native main jar"
    destinationDirectory.set(nativesJarsDir)
    archiveFileName.set("oml-native-$omlVersion.jar")
}

/** classifier -> (zig platform directory, library extension, candidate arch spellings, first wins). */
data class NativeSlot(val classifier: String, val plat: String, val ext: String, val archs: List<String>)

val nativeSlots = listOf(
    NativeSlot("windows", "windows", "dll", listOf("x64", "x86_64", "amd64")),
    NativeSlot("windows-arm64", "windows", "dll", listOf("arm64", "aarch64")),
    NativeSlot("linux", "linux", "so", listOf("x86_64", "amd64")),
    NativeSlot("linux-arm64", "linux", "so", listOf("arm64", "aarch64")),
    NativeSlot("osx", "macosx", "dylib", listOf("x86_64")),
    NativeSlot("osx-arm64", "macosx", "dylib", listOf("arm64", "aarch64")),
)

val nativesJarTasks = nativeSlots.associate { slot ->
    val taskName = "packageNatives" + slot.classifier.split("-").joinToString("") { it.replaceFirstChar { c -> c.uppercase() } } + "Jar"
    // A per-arch JVM cannot load a foreign-arch library; the slot carries exactly one arch.
    val candidates: List<File> = slot.archs.map { arch ->
        File(projectDir, "build/${slot.plat}/$arch/release/oml-native.${slot.ext}")
    }
    val task = tasks.register<Jar>(taskName) {
        group = "ohmyloader"
        description = "Package the built oml-native library as a natives-${slot.classifier} jar"
        dependsOn(buildOmlNative)
        destinationDirectory.set(nativesJarsDir)
        archiveFileName.set("oml-native-$omlVersion-natives-${slot.classifier}.jar")
        from(candidates.map { c -> c.parentFile }) { include("oml-native.${slot.ext}") }
        onlyIf { candidates.any { c -> c.isFile } }
    }
    slot.classifier to task
}

// Aggregate under the historical name: the installer's embedNativesJars depends on it.
val packageOmlNativeJars = tasks.register("packageOmlNativeJars") {
    group = "ohmyloader"
    description = "Package built oml-native libraries as standard natives-<classifier> jars"
    dependsOn(packageOmlNativeJar)
    dependsOn(nativesJarTasks.values)
}

// Every consumer resolves the library from a repository by coordinate; a platform that was not
// built fails the publish with a missing artifact rather than silently stranding that classifier.
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
        create<MavenPublication>("omlNative") {
            groupId = providers.gradleProperty("oml_group").getOrElse("org.ohmyloader")
            artifactId = "oml-native"
            version = omlVersion
            artifact(packageOmlNativeJar)
            for ((slotClassifier, jarTask) in nativesJarTasks) {
                artifact(jarTask) { classifier = "natives-$slotClassifier" }
            }
            pom {
                name.set("oml-native")
                description.set(
                    "OhMyLoader's own native library (zstd codecs for region files and packet " +
                        "compression), built by zig with zstd statically linked."
                )
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
