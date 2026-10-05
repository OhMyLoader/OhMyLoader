package org.ohmyloader.installer

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// ---------------------------------------------------------------------------------------------------
// Every jar the installer writes out is (a) streamed exactly once, (b) hashed while it is written, and
// (c) moved into place atomically — an interrupted write must never leave a half file that the next
// run accepts as "already present".
// ---------------------------------------------------------------------------------------------------

class WrittenFile(val file: File, val sha1: String, val size: Long)

/** Streams [open] to [target] through `<name>.tmp`, hashing as it goes, then moves it into place. */
fun writeAtomic(target: File, journal: InstallJournal? = null, open: () -> InputStream): WrittenFile {
    journal?.recordWrite(target)
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    val digest = MessageDigest.getInstance("SHA-1")
    var size = 0L
    try {
        open().use { input ->
            tmp.outputStream().buffered().use { output ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    size += read
                }
            }
        }
        if (size == 0L) throw InstallationException(Messages.t("err.writeZeroBytes", target.name))
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    } catch (t: Throwable) {
        tmp.delete()
        throw t
    }
    return WrittenFile(target, digest.digest().joinToString("") { "%02x".format(it) }, size)
}

fun writeAtomicText(target: File, text: String, journal: InstallJournal? = null) {
    journal?.recordWrite(target)
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    tmp.writeText(text, Charsets.UTF_8)
    Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
}

fun sha1Of(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// ---------------------------------------------------------------------------------------------------
// Maven layout for the OML layer
//
// The embedded layer arrives as flat file names (`oml-core.jar`, `asm-9.10.1.jar`, …), so a real
// `group:artifact:version` triple cannot be reconstructed reliably. Everything is published under the
// single group `org.ohmyloader` with the **install id** as the version:
//
//   libraries/org/ohmyloader/<artifact>/<installId>/<artifact>-<installId>.jar
//
// Keyed by the install id (not the game version) so two OML instances of the same game version can
// coexist: with a shared path, the second install overwrites the first's jars and the first version
// JSON's recorded sha1 no longer matches the file on disk.
// ---------------------------------------------------------------------------------------------------
object LayerLayout {
    const val GROUP = "org.ohmyloader"

    fun mavenPath(coordinateBase: String, installId: String): String =
        "org/ohmyloader/$coordinateBase/$installId/$coordinateBase-$installId.jar"

    /**
     * The Maven path of a **classified** jar — `natives-<os>` for the oml-native packages. The
     * classifier goes into the file name only, exactly as Maven writes it:
     *
     *   libraries/org/ohmyloader/oml-native/<v>/oml-native-<v>-natives-windows.jar
     */
    fun classifiedMavenPath(coordinateBase: String, version: String, classifier: String): String =
        "org/ohmyloader/$coordinateBase/$version/$coordinateBase-$version-$classifier.jar"

    fun coordinate(coordinateBase: String, installId: String): String =
        "$GROUP:$coordinateBase:$installId"
}

/** Writes one layer jar into `<root>/libraries/...` and returns its version-JSON entry. */
class InstalledLibrary(val coordinate: String, val mavenPath: String, val sha1: String, val size: Long) {

    fun toJson(): String = """    {
      "name": "$coordinate",
      "downloads": {
        "artifact": {
          "path": "$mavenPath",
          "url": "",
          "sha1": "$sha1",
          "size": $size
        }
      }
    }"""
}

// ---------------------------------------------------------------------------------------------------
// oml-native as `natives-<classifier>` jars
//
// The library ships as one jar per (OS × architecture slot) — `oml-native-<version>-natives-<os>.jar`
// carries the x86_64 library, `oml-native-<version>-natives-<os>-arm64.jar` the arm64 one (library at
// the jar root; an arm64 JVM cannot load a foreign-arch library). The classifier doubles as the
// version JSON `natives` map key, Mojang's own 1.19+ spelling for per-architecture natives
// (`"natives": { "osx": "natives-osx", "osx-arm64": "natives-osx-arm64", … }`) — no `rules` block
// needed. Where the jars are written depends on the consumer, hence the `mavenTree` flag on
// [installOmlNativePackages]:
//
//  * **Standard launcher** (official / HMCL / PCL2) resolves every library through the Maven tree under
//    `libraries/` and extracts the classified jar it finds into its `${natives_directory}`. A flat file
//    name is not how a Maven resolver looks an artifact up, so a flat write leaves the jar on disk
//    unconsumed.
//  * **Prism** (MMC-hint: local) resolves by exactly the flat file name under `<instance>/libraries`,
//    so there the flat shape is the correct one.
// ---------------------------------------------------------------------------------------------------

/**
 * The oml-native entry for the two launcher formats this installer writes, built from what
 * [installOmlNativePackages] deployed. Two shapes, because the two consumers disagree on how a jar
 * is declared: a version JSON downloads, a Prism component patch points at a local file.
 */
class InstalledNativeLibrary(
    val coordinateVersion: String,
    /** natives map key (`windows`, `windows-arm64`, …) -> the flat natives jar's write, in bundle order. */
    val natives: List<Pair<String, WrittenFile>>,
) {
    /** Mojang version-JSON shape, for the standard target's `libraries` array. */
    fun toJson(): String {
        // `path` is relative to the target's `libraries/` and is the Maven location the launcher
        // resolves the classified artifact at — the same base `installOmlNativePackages` wrote to.
        val classifierEntries = natives.joinToString(",\n") { [os, written] ->
            """          "natives-$os": {
            "path": "${LayerLayout.classifiedMavenPath("oml-native", coordinateVersion, "natives-$os")}",
            "url": "",
            "sha1": "${written.sha1}",
            "size": ${written.size}
          }"""
        }
        // `downloads.classifiers` is the standard nesting a launcher reads (keyed by the classifier
        // the `natives` block maps an OS to); written as a sibling of `artifact` it is simply not
        // found and the jar stays on disk unconsumed.
        val nativesBlock = natives.joinToString(",") { [os, _] -> "\"$os\": \"natives-$os\"" }
        return """    {
      "name": "${LayerLayout.GROUP}:oml-native:$coordinateVersion",
      "downloads": {
        "classifiers": {
$classifierEntries
        }
      },
      "natives": {
        $nativesBlock
      }
    }"""
    }

    /**
     * Prism component-patch shape: a `local` library exactly like every other entry the patch
     * declares, because there is nothing to download — the jar was written flat into
     * `<instance>/libraries/`, and that is precisely the file Prism resolves for a local library
     * (`<artifactId>-<version>-<classifier>.jar`, with the classifier taken from the `natives` block).
     * A `downloads` block here would advertise a fetch Prism must never perform.
     */
    fun toPrismJson(): String {
        val nativesBlock = natives.joinToString(", ") { [os, _] -> "\"$os\": \"natives-$os\"" }
        return """    {
      "name": "${LayerLayout.GROUP}:oml-native:$coordinateVersion",
      "MMC-hint": "local",
      "natives": { $nativesBlock },
      "extract": {
        "exclude": [ "META-INF/" ]
      }
    }"""
    }
}

/**
 * Installs the packaged oml-native jars into [librariesRoot] (the target's `libraries/`) and returns
 * the JSON-facing summary, or `null` when this build bundles no packaged natives (a logged skip).
 * [mavenTree] chooses where a jar is written: `true` at its Maven location
 * (`org/ohmyloader/oml-native/<v>/…`, what a version-JSON launcher resolves); `false` flat (what
 * Prism's `MMC-hint: local` resolver looks up); either way single-copy — the other layout is swept.
 *
 * **Every** bundled platform is written, for both layouts: neither target directory is guaranteed to
 * stay on the installing machine (copied, restored from a backup, exported and imported on another
 * OS), and the `natives` block is what tells the launcher which classifier an OS needs — a missing
 * platform there degrades zstd in silence. The bare runtime-loaded library is [installOmlNative]'s.
 */
fun installOmlNativePackages(
    librariesRoot: File,
    artifacts: ArtifactSource,
    log: (String) -> Unit,
    mavenTree: Boolean = false,
    journal: InstallJournal? = null,
): InstalledNativeLibrary? {
    val packages = artifacts.omlNativePackages()
    if (packages.isEmpty()) {
        log("libraries/: no packaged oml-native jars bundled, skipping the natives library entry")
        return null
    }
    val natives = packages
        .filter { it.classifier != null }
        .map {
            val relative =
                if (mavenTree) LayerLayout.classifiedMavenPath(
                    "oml-native",
                    it.coordinateVersion,
                    "natives-${it.classifier}",
                )
                else it.fileName
            val written = writeAtomic(File(librariesRoot, relative), journal) { it.open() }
            log("libraries/$relative (${written.size / 1024} KB)")
            it.classifier!! to written
        }
    if (natives.isEmpty()) {
        log("libraries/: no natives-<classifier> jar bundled, skipping the natives library entry")
        return null
    }
    removePreviousLayout(librariesRoot, packages, mavenTree, log)
    return InstalledNativeLibrary(packages.first().coordinateVersion, natives)
}

/**
 * Deletes what an earlier or other-layout install left behind, so re-installing is idempotent and no
 * single jar is carried twice. Two sources:
 *
 *  * each package's jar in the **other** layout (a `mavenTree` install sweeps old flat jars, a flat
 *    install sweeps any Maven tree) — otherwise a launcher keeps loading the stale copy this run did
 *    not overwrite;
 *  * the target *root* — the JSON / patch path is relative to `libraries/`, so a jar sitting at the
 *    root is unreferenced.
 *
 * Best effort, and narrowly scoped to our own file names: a failure here must not fail the install.
 */
private fun removePreviousLayout(
    librariesRoot: File,
    packages: List<OmlNativePackage>,
    mavenTree: Boolean,
    log: (String) -> Unit,
) {
    val previousRoot = librariesRoot.parentFile ?: return

    /** Where an earlier / other-layout install could have left the package, minus where this run puts it. */
    fun stalePaths(pkg: OmlNativePackage): List<File> {
        val os = pkg.classifier ?: return emptyList()
        val flat = File(librariesRoot, pkg.fileName)
        val maven =
            File(librariesRoot, LayerLayout.classifiedMavenPath("oml-native", pkg.coordinateVersion, "natives-$os"))
        val written = if (mavenTree) maven else flat
        return listOf(flat, maven, File(previousRoot, pkg.fileName)).filter { it != written }
    }

    val stale = packages.flatMap { stalePaths(it) }
    val staleTree = File(previousRoot, "org/ohmyloader/oml-native")
    val present = stale.filter { it.isFile }
    if (present.isEmpty() && !staleTree.isDirectory) return
    present.forEach { if (it.delete()) log("removed stale ${it.name}") }
    if (staleTree.isDirectory && staleTree.deleteRecursively()) {
        log("removed stale org/ohmyloader/oml-native")
    }
}

/**
 * The bare library for the running platform, taken out of this build's packaged natives jar. The
 * classifier must match the running JVM's architecture — an arm64 JVM cannot load an x86_64
 * library, so the plain-jar fallback would deploy a file that fails to open and silently degrade
 * zstd; when no architecture-matched jar is bundled the answer is null (the logged skip) instead.
 */
internal fun nativeLibraryFromPackages(artifacts: ArtifactSource): NativeArtifact? {
    val token = ArtifactSource.currentPlatformToken() ?: return null
    val [os, arch] = token.split('-', limit = 2)
    val classifier = os + if (arch == "arm64") "-arm64" else ""
    val pkg = artifacts.omlNativePackages().firstOrNull { it.classifier == classifier } ?: return null
    val [fileName, bytes] = firstLibraryEntry(pkg) ?: return null
    return NativeArtifact(fileName, os) { ByteArrayInputStream(bytes) }
}

/**
 * The single library inside a packaged natives jar: its name (the name the file must have on disk —
 * `oml-native.dll` / `.so` / `.dylib`) and its bytes. The manifest, if the jar has one, is skipped.
 */
private fun firstLibraryEntry(pkg: OmlNativePackage): Pair<String, ByteArray>? =
    ZipInputStream(pkg.open()).use { zip ->
        generateSequence { zip.nextEntry }
            .firstOrNull { !it.name.endsWith("/") && !it.name.startsWith("META-INF/") }
            ?.let { it.name to zip.readBytes() }
    }

/** Streams a layer artifact into `libraries/`, hashing while copying. Returns `null` if [skip] says so. */
fun installLibrary(
    root: File,
    artifact: LayerArtifact,
    installId: String,
    log: (String) -> Unit,
    journal: InstallJournal? = null,
): InstalledLibrary {
    val base = artifact.coordinateBase
    val mavenPath = LayerLayout.mavenPath(base, installId)
    val target = File(root, "libraries/$mavenPath")
    val written = writeAtomic(target, journal) { artifact.open() }
    log("libraries/$mavenPath")
    return InstalledLibrary(LayerLayout.coordinate(base, installId), mavenPath, written.sha1, written.size)
}

// ---------------------------------------------------------------------------------------------------
// Launcher version JSON
// ---------------------------------------------------------------------------------------------------

private val JSON_TIME: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

/** The `-D` properties the runtime reads. Kept next to the Java requirement: both are contract. */
class RuntimeProperties(
    val gameJar: File,
    val modsDir: File,
    val side: String,
) {
    fun asJvmArgs(): List<String> = listOf(
        // the game side's console output does not go through our launcher; without these two the
        // Chinese in game logs comes out garbled
        // The GC-side pair from OmlJvmContract.BASE_JVM_ARGS. The rest of that contract (opens,
        // native access) arrives through the launcher jar's own manifest; `-XX` arguments have no
        // manifest channel, so they travel here or nowhere.
        "-XX:+ExitOnOutOfMemoryError",
        "-XX:+UseStringDeduplication",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-Doml.game.jar=${slash(gameJar)}",
        "-Doml.mods.dir=${slash(modsDir)}",
        // Always explicit, on both sides. A launcher-installed runtime has no launch.properties, and
        // the version JSON is the only channel through which it can learn its side — a default of
        // "server" would send a client launch into net.minecraft.server.Main, where the client's own
        // arguments are rejected ("username is not a recognized option").
        "-Doml.side=$side",
    )

    private fun slash(f: File) = f.absolutePath.replace('\\', '/')
}

/**
 * The two entries every *modern* Mojang version JSON carries in `arguments.jvm` — and the reason a
 * launcher-installed OML version cannot leave them out. A launcher picks between "old" and "new"
 * argument handling by whether the version JSON declares `arguments.jvm` at all: on the old path it
 * would add `-cp ${classpath}` by itself, but only while the JSON has no `arguments.jvm`. This
 * installer must contribute one (that is where `-Doml.*` and the Java requirement travel), and the
 * moment it does, the launcher copies nothing but what is in there — with neither entry present the
 * JVM starts with no classpath and cannot load the bootstrap. On a vanilla parent whose JSON already
 * carries both entries ours is an exact duplicate of the parent's, which launchers drop as a
 * duplicate pair; `${classpath}` and `${natives_directory}` are the standard launcher variables, so
 * the launcher substitutes them.
 */
fun launcherClasspathArgs(): List<String> = listOf(
    $$"-Djava.library.path=${natives_directory}",
    "-cp",
    $$"${classpath}",
)

/** Exactly the `arguments.jvm` list written for a launcher-installed (HMCL / PCL2 / official) version. */
fun launcherJvmArgs(props: RuntimeProperties): List<String> = props.asJvmArgs() + launcherClasspathArgs()

/**
 * Writes `<versionDir>/<id>.json`. Two fields are not optional, despite looking decorative:
 *
 * - **`javaVersion`** — without it the launcher inherits the *vanilla* Java requirement, which can be
 *   older than what OML's own bytecode needs: an instant `UnsupportedClassVersionError` with nothing
 *   in the UI to explain it. HMCL and PCL2 both read this field. The `component` name is a placeholder:
 *   Mojang's runtime catalogue has no component for 26 or 27, so `majorVersion` is the authoritative
 *   requirement and the launcher must resolve Java 27 itself.
 * - **`jar`** — points the classpath at the inherited vanilla jar explicitly. The installer also writes
 *   a manifest-only `<id>.jar` stub (launchers expect one next to the JSON), and leaving `jar` unset
 *   makes it launcher-dependent whether that empty stub or the real game jar is used.
 */
fun writeVersionJson(
    target: File,
    id: String,
    inherits: String,
    mainClass: String,
    javaMajor: Int,
    libraries: List<InstalledLibrary>,
    jvmArgs: List<String>,
    nativeLibrary: InstalledNativeLibrary? = null,
    journal: InstallJournal? = null,
) {
    val libs = (libraries.map { it.toJson() } + listOfNotNull(nativeLibrary?.toJson()))
        .joinToString(",\n")
    val args = jvmArgs.joinToString(",\n") { "      \"$it\"" }
    val now = JSON_TIME.format(Instant.now())
    val json = """{
  "id": "$id",
  "inheritsFrom": "$inherits",
  "jar": "$inherits",
  "type": "release",
  "time": "$now",
  "releaseTime": "$now",
  "mainClass": "$mainClass",
  "javaVersion": {
    "component": "java-runtime-delta",
    "majorVersion": $javaMajor
  },
  "libraries": [
$libs
  ],
  "arguments": {
    "jvm": [
$args
    ]
  }
}
"""
    writeAtomicText(target, json, journal)
}

/** The launcher expects a jar with the same name as the version directory; the real classes live in `libraries/`. */
fun writeVersionStubJar(target: File, journal: InstallJournal? = null) {
    journal?.recordWrite(target)
    ZipOutputStream(target.outputStream().buffered()).use { zip ->
        zip.putNextEntry(ZipEntry("META-INF/MANIFEST.MF"))
        zip.write("Manifest-Version: 1.0\r\n\r\n".toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}

// ---------------------------------------------------------------------------------------------------
// Dedicated-server start scripts
//
// The bootstrap server directory has no launcher to carry the JVM flags; the flags also live in
// oml-launcher.jar's MANIFEST (Add-Opens / Enable-Native-Access), so `java -jar` alone is already
// correct — the scripts mainly add a Java version check with an actionable message, and a memory knob
// the owner can edit. Both keep their *commands* ASCII: only the echo text is Chinese, so even a
// garbled console codepage cannot break the launch itself.
// ---------------------------------------------------------------------------------------------------

fun writeServerScripts(dir: File, javaMajor: Int, log: (String) -> Unit, journal: InstallJournal? = null) {
    // The prose comes from the resource bundle; what stays in code is the shell syntax around it. Note
    // the two "detected" values: they name each script's own detection result, so they must reach the
    // generated file **verbatim** — substituting a Kotlin value there would bake in the installer's
    // Java version instead of the one the server actually runs on.
    // Note the escapes: this is a plain string, so the dollar sign needs escaping to stay literal.
    val shDetected = $$"$JAVA_MAJOR"
    val batDetected = "%OML_MAJOR%"
    val sh = File(dir, "run.sh")
    val header = Messages.t("script.header")
    val memoryNote = Messages.t("script.memory")
    val javaMissing = Messages.t("script.javaMissing", javaMajor)
    val javaTooOldSh = Messages.t("script.javaTooOld", shDetected, javaMajor)
    val javaTooOldBat = Messages.t("script.javaTooOld", batDetected, javaMajor)
    val javaDownload = Messages.t("script.javaDownload", javaMajor)

    writeAtomicText(
        sh,
        $$"""#!/usr/bin/env sh
# $$header
set -e

JAVA_MAJOR="$(java -version 2>&1 | awk -F'"' '/version/ {print $2}' | awk -F. '{ if ($1=="1") print $2; else print $1 }')"
if [ -z "$JAVA_MAJOR" ]; then
  echo "$$javaMissing"
  exit 1
fi
if [ "$JAVA_MAJOR" -lt $$javaMajor ]; then
  echo "$$javaTooOldSh"
  echo "$$javaDownload"
  exit 1
fi

cd "$(dirname "$0")"
# $$memoryNote
exec java -XX:MaxRAMPercentage=75.0 -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -jar oml-launcher.jar "$@"
""",
        journal,
    )
    makeExecutable(sh, log)

    // CRLF, not the bare "\n" the raw string carries. cmd.exe walks a batch file in bytes and advances
    // a line by looking for CRLF; on an LF-only file that position drifts once a line holds multi-byte
    // characters — and every prose line here is localized. It then eats the head of a following line,
    // which is what turned `for /f ... ('java -version ...')` into a command named `-version` on a
    // zh_CN install. run.sh stays LF: sh wants it, and it is not what cmd reads.
    val bat = $$"""@echo off
chcp 65001 >nul
rem $$header
setlocal

for /f "tokens=3" %%g in ('java -version 2^>^&1 ^| findstr /i "version"') do set OML_JAVA_VER=%%g
set OML_JAVA_VER=%OML_JAVA_VER:"=%
for /f "delims=. tokens=1-2" %%a in ("%OML_JAVA_VER%") do (
    set OML_MAJOR=%%a
    set OML_MINOR=%%b
)
if "%OML_MAJOR%"=="1" set OML_MAJOR=%OML_MINOR%
if "%OML_MAJOR%"=="" (
    echo $$javaMissing
    pause
    exit /b 1
)
if %OML_MAJOR% LSS $$javaMajor (
    echo $$javaTooOldBat
    echo $$javaDownload
    pause
    exit /b 1
)

cd /d "%~dp0"
rem $$memoryNote
java -XX:MaxRAMPercentage=75.0 -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -jar oml-launcher.jar %*
pause
""".replace("\r\n", "\n").replace("\n", "\r\n")
    writeAtomicText(File(dir, "run.bat"), bat, journal)
    log("run.sh / run.bat")
}

/** Best-effort +x. Silently skipped on file systems without POSIX permissions (Windows). */
fun makeExecutable(file: File, log: (String) -> Unit = {}) {
    try {
        val perms = Files.getPosixFilePermissions(file.toPath())
        Files.setPosixFilePermissions(
            file.toPath(),
            perms + java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE +
                java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE +
                java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE,
        )
    } catch (e: UnsupportedOperationException) {
        // Windows: nothing to do, the file is launched by association or from a shell
    } catch (e: Exception) {
        log(Messages.t("log.chmodFailed", file.name, e.message ?: ""))
    }
}
