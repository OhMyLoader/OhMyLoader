package org.ohmyloader.devtools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.ohmyloader.devtools.AssetDownloader.parseToPlain
import org.ohmyloader.devtools.AssetDownloader.resolveVersionDetails
import java.io.File
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

/**
 * Dev-time tooling: game resource verification/download and main jar download.
 * Build-time only; not part of the runtime loader.
 *
 * Two rules: (1) **never call `exitProcess`** — this runs in-process inside a Swing GUI (the
 * installer) and in build tools, where a `System.exit` killed the whole window without a dialog;
 * every failure is an exception, and `main` is the single place that turns one into an exit code.
 * (2) **every file lands atomically, after being verified** — write `<name>.tmp`, check size, move
 * into place; otherwise a killed process leaves a truncated jar the next run accepts as present.
 * This object is the CLI: everything callable is also a typed method on [GameEnvironment], and
 * `run` is a thin argument-parsing wrapper over it.
 */
object AssetDownloader {

    /**
     * Marker file written under the assets directory by [fetchAssets], holding the asset index id the
     * fetch resolved from the version metadata. Whoever launches the game needs this id: the game
     * declares `--assetIndex` with a required argument and no default, and the version.json embedded
     * in its own jar carries no assetIndex field. A launch without it silently misses every resource
     * that lives only in the asset index — in 26.3 that is every sound definition in the game.
     */
    const val ASSET_INDEX_MARKER = "oml-asset-index.txt"

    /** Mojang's version manifest — the one document that knows both the id list and `latest.snapshot`. */
    private const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    /**
     * The parser for third-party JSON. `ignoreUnknownKeys` because Piston's documents carry fields we
     * do not model and never will model — failing a download because Mojang added a field would be
     * absurd.
     *
     * For the *dynamic* documents (the version manifest and the per-version JSON, whose shape we walk
     * by hand) [parseToPlain] turns a parse result into plain Kotlin maps and lists, which is what the
     * rest of this file traverses; only the asset index uses a [Serializable] class.
     */
    internal val json = Json { ignoreUnknownKeys = true }

    /** Converts a parsed [JsonElement] into the plain Map/List/String/Number/Boolean tree the hand-walking code consumes. */
    private fun JsonElement.toPlainValue(): Any? = when (this) {
        is JsonObject -> entries.associate { it.key to it.value.toPlainValue() }
        is JsonArray -> map { it.toPlainValue() }
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: longOrNull ?: doubleOrNull
        is JsonNull -> null
    }

    /** Parses a JSON document into the plain tree the hand-walking code expects. */
    private fun parseToPlain(text: String): Map<*, *>? =
        (json.parseToJsonElement(text) as? JsonObject)?.toPlainValue() as? Map<*, *>

    /**
     * The HTTP user agent, carrying the version this build actually is: stamped into
     * `oml-devtools.properties` from `project.version` at build time. A literal here would report a
     * version the request did not come from, which is worse than reporting none.
     */
    internal val USER_AGENT: String = "OhMyLoader/${stampedVersion()}-devtools"

    private fun stampedVersion(): String =
        AssetDownloader::class.java.getResourceAsStream("/oml-devtools.properties")?.use { stream ->
            java.util.Properties().apply { load(stream) }
                .getProperty("version")?.takeIf { it.isNotBlank() }
        } ?: "unknown"

    /** Concurrency cap for downloads: a sudden burst of thousands of connections triggers CDN /
     * intermediate network throttling that cuts them off (EOF / handshake reset). */
    internal const val MAX_PARALLEL_DOWNLOADS = 16

    /** Progress callback: bytes or item counts, depending on the caller. */
    fun interface DownloadProgress {
        fun onProgress(done: Long, total: Long, label: String)
    }

    /**
     * Optional proxy, set by the installer/GUI from user input.
     *
     * Without this there is no way to configure a proxy at all: JDK's HttpClient defaults to
     * `ProxySelector.getDefault()`, which only reads `-Dhttp(s).proxyHost` system properties, and
     * "use the OS settings" additionally needs `-Djava.net.useSystemProxies=true`. None of that is
     * reachable for a player who just double-clicked a jar — and for the mainland-China audience this
     * is the number one cause of "下载失败".
     */
    @Volatile
    var proxyOverride: ProxySelector? = null

    /**
     * Where this tool's running commentary goes. stdout by default, which is right for the CLI; a
     * build tool or the installer swaps in a sink so its own progress model stays the only thing the
     * user sees. Every line this file emits must go through [log] / [error] — a stray `println` would
     * bypass that.
     */
    @Volatile
    var logSink: (String) -> Unit = { println(it) }

    /**
     * Where errors go. Separate from [logSink] because the two are not the same stream in the CLI
     * (stdout vs stderr), and a caller that filters "normal output" must not lose the failures.
     */
    @Volatile
    var errorSink: (String) -> Unit = { System.err.println(it) }

    enum class Level { INFO, WARN, ERROR }

    /** A single line of output, already formatted and already localized. */
    data class LogLine(val level: Level, val message: String)

    /**
     * Replaces both sinks with one that collects every line: a *library* must not write to the
     * process's stdout behind the caller's back. A build tool with its own progress reporting, and
     * the installer's Swing log pane (which believes it is the only writer), need the output
     * delivered to them instead.
     */
    fun sink(lines: MutableList<LogLine>) {
        logSink = { lines += LogLine(Level.INFO, it) }
        errorSink = { lines += LogLine(Level.ERROR, it) }
    }

    /** Emits one informational line to [logSink]. */
    internal fun log(message: String) = logSink(message)

    /** Emits one error line to [errorSink]. */
    internal fun error(message: String) = errorSink(message)

    /** Parses `host:port` (or `http://host:port`) into a single-proxy selector. */
    fun parseProxy(spec: String): ProxySelector? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return null
        val hostPort = trimmed.removePrefix("http://").removePrefix("https://").trimEnd('/')
        val host = hostPort.substringBeforeLast(':', hostPort)
        val port = hostPort.substringAfterLast(':', "").toIntOrNull() ?: return null
        if (host.isEmpty()) return null
        val address = InetSocketAddress.createUnresolved(host, port)
        return ProxySelector.of(address)
    }

    // -----------------------------------------------------------------------------------------------
    // The engine — one HttpClient and one download-permit semaphore per GameEnvironment
    // -----------------------------------------------------------------------------------------------

    @Serializable
    private data class IndexObject(val hash: String, val size: Long)

    /** An asset object's hash, and therefore its file name and URL path — nothing else may be admitted. */
    private val SHA1_HEX = Regex("[0-9a-fA-F]{40}")

    @Serializable
    private data class AssetIndex(val objects: Map<String, IndexObject>)
    private data class VersionArtifact(
        val path: String,
        val url: String,
        val size: Long?,
        val sha1: String?,
        val isNatives: Boolean,
    )

    /**
     * CLI entry point: the only place in this file that terminates the process.
     *
     * The exit code is all a Gradle task or a shell script needs; the stack trace gives the maintainer
     * what the message does not.
     */
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            run(args, null)
        } catch (t: Throwable) {
            error("[AssetDownloader] ${t.message}")
            t.printStackTrace()
            kotlin.system.exitProcess(1)
        }
    }

    /**
     * Same as [main], but throws instead of exiting — this is what in-process callers (the installer)
     * use, so a failure becomes a dialog rather than a dead JVM.
     *
     * Deliberately an argument parser: every branch is one line that names a typed method, so the CLI
     * cannot drift away from the library.
     */
    fun run(args: Array<String>, progress: DownloadProgress? = null) {
        if (args.isEmpty()) throw IllegalArgumentException(usage())
        val rest = args.drop(1)
        when (args[0]) {
            "--clientJar" -> {
                requireArgs(rest, 2, "--clientJar <targetJarPath> <version> [--force]")
                open(rest[1]).use { env ->
                    downloadGameJar(env.state(), File(rest[0]), rest.contains("--force"), progress)
                }
            }

            "--libraries" -> {
                requireArgs(rest, 2, "--libraries <version> <destDir>")
                open(rest[0]).use { env -> downloadLibraries(env.state(), File(rest[1]), progress) }
            }

            else -> {
                // asset fetch: <assetsDir> <assetIndex|--version> [version] [--baseUrl=…] [--force]
                fetchAssetsFromCli(args, progress)
            }
        }
    }

    private fun usage(): String = """
        usage:
          --clientJar      <targetJarPath> <version> [--force]
          --libraries      <version> <destDir>
          <assetsDir> <assetIndex|--version> [version] [--baseUrl=<mirror>] [--force]
    """.trimIndent()

    private fun requireArgs(args: List<String>, min: Int, usage: String) {
        if (args.size < min) throw IllegalArgumentException("usage: $usage")
    }

    /** Opens an engine for [version]. */
    private fun open(version: String) = GameEnvironment.open(version)

    /**
     * The one remaining CLI-shaped path: assets. It has no typed counterpart taking a pre-resolved
     * index id ([GameEnvironment.downloadAssets] resolves it from the version JSON); this form is kept
     * for scripts that pass an explicit index — on the `--version` form the id is resolved, on the
     * positional form it is used as given.
     */
    private fun fetchAssetsFromCli(args: Array<String>, progress: DownloadProgress?) {
        if (args.size < 2) throw IllegalArgumentException("usage: ${usage()}")
        val assetsDir = File(args[0])
        var baseUrl = "https://resources.download.minecraft.net"
        var force = false
        var version: String? = null
        var assetIndex: String? = null

        if (args[1] == "--version") {
            if (args.size < 3) throw IllegalArgumentException("usage: fetchAssets <assetsDir> --version <version>")
            version = args[2]
        } else {
            assetIndex = args[1]
        }
        args.drop(2).forEach {
            when {
                it.startsWith("--baseUrl=") -> baseUrl = it.removePrefix("--baseUrl=")
                it == "--force" -> force = true
            }
        }

        val engine = GameEnvironment.open(version ?: assetIndex!!)
        engine.use {
            fetchAssets(
                engine = it.state(),
                assetsDir = assetsDir,
                version = engine.version,
                force = force,
                progress = progress,
                baseUrl = baseUrl,
                explicitIndex = assetIndex,
            )
        }
    }

    // -----------------------------------------------------------------------------------------------
    // GameEnvironment implementation
    // -----------------------------------------------------------------------------------------------

    internal fun downloadGameJar(
        engine: GameEnvironment.Engine,
        target: File,
        force: Boolean,
        progress: DownloadProgress?,
    ): File = withEngine(engine) {
        // The client jar is the game jar for both sides: the modern server download is a bundler
        // wrapper, while the client jar carries the flat, unified classes.
        val kind = "client"
        val label = "game main jar"
        val vParsed = resolveVersionDetails(client, engine.version)
        val downloads = vParsed["downloads"] as? Map<*, *>
            ?: throw IllegalStateException("downloads missing in the version details")
        val meta = downloads[kind] as? Map<*, *>
            ?: throw IllegalStateException("downloads.$kind missing in the version details")
        val url = meta["url"] as? String
            ?: throw IllegalStateException("downloads.$kind.url missing in the version details")
        val expected = (meta["size"] as? Number)?.toLong()
        val sha1 = meta["sha1"] as? String

        // An existing file is only trusted when it passes the *same* checks a fresh download would:
        // size when the JSON gives one, digest when it gives one. Otherwise, a corrupted jar is
        // reused forever.
        if (!force && target.isFile && expected != null && target.length() == expected &&
            digestMatches(target, sha1)
        ) {
            log("[AssetDownloader] $label already exists: ${target.absolutePath} (${target.length() / 1024} KB)")
            return@withEngine target
        }
        if (!force && target.isFile && expected != null && target.length() != expected) {
            log("[AssetDownloader] $label 大小不符（期望 $expected，实际 ${target.length()}），重新下载")
        }

        log("[AssetDownloader] downloading Minecraft ${engine.version} $label, approx ${(expected ?: 0L) / 1024} KB ...")
        val written = downloadToFile(client, permits, url, target, expected, label, progress, sha1)
        log("[AssetDownloader] $label ready: ${target.absolutePath} (${written / 1024} KB)")
        target
    }

    internal fun downloadLibraries(
        engine: GameEnvironment.Engine,
        librariesDir: File,
        progress: DownloadProgress?,
    ): List<File> = withEngine(engine) {
        librariesDir.mkdirs()
        val vParsed = resolveVersionDetails(client, engine.version)
        val libs = vParsed["libraries"] as? List<*>
            ?: throw IllegalStateException("libraries missing in the version details")

        val artifacts = mutableListOf<VersionArtifact>()
        val nativesClassifier =
            PlatformRules.nativesClassifier(PlatformRules.currentOsName(), PlatformRules.currentArch())
        for (libRaw in libs) {
            val lib = libRaw as? Map<*, *> ?: continue
            if (!allowsOnThisPlatform(lib)) continue
            val downloads = lib["downloads"] as? Map<*, *> ?: continue
            (downloads["artifact"] as? Map<*, *>)?.let { artifacts += asArtifact(it, false) }
            (downloads["classifiers"] as? Map<*, *>)?.get(nativesClassifier)?.let {
                (it as? Map<*, *>)?.let { natives -> artifacts += asArtifact(natives, true) }
            }
        }
        // A library entry with no `path` cannot be filed under a Maven layout at all. Dropping it
        // silently is how a "boots fine on my machine" install happens, so it is reported.
        val declared = libs.count { it is Map<*, *> }
        if (artifacts.size < declared) {
            log("[AssetDownloader] ${declared - artifacts.size} of $declared libraries declare no downloadable artifact for this platform")
        }

        val done = AtomicLong()
        val added = java.util.Collections.synchronizedList(mutableListOf<File>())
        val skipped = AtomicInteger()
        progress?.onProgress(0, artifacts.size.toLong(), "游戏运行库")

        val pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            for (artifact in artifacts) {
                pool.submit {
                    try {
                        downloadArtifactSingle(client, permits, artifact, librariesDir, added, skipped)
                    } finally {
                        val n = done.incrementAndGet()
                        progress?.onProgress(n, artifacts.size.toLong(), "游戏运行库")
                    }
                }
            }
        } finally {
            pool.shutdown()
            while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                // wait for all downloads to finish
            }
        }

        log("[AssetDownloader] library download complete: added ${added.size}, already present ${skipped.get()}")
        added.toList()
    }

    internal fun extractNatives(
        engine: GameEnvironment.Engine,
        librariesDir: File,
        nativesDir: File,
        progress: DownloadProgress?,
    ): List<File> = withEngine(engine) {
        val osName = PlatformRules.currentOsName()
        val arch = PlatformRules.currentArch()
        val classifier = PlatformRules.nativesClassifier(osName, arch)
        val vParsed = resolveVersionDetails(client, engine.version)
        val libs = vParsed["libraries"] as? List<*> ?: return@withEngine emptyList()

        // Locate the natives jars by asking the version JSON which ones they are, rather than by
        // walking the libraries tree for names ending in the classifier: the walk also matched
        // jars belonging to a *different* version installed side by side in the same directory.
        // PlatformRules.nativesJarPath understands both layouts (legacy `classifiers` entry and the
        // modern artifact-is-the-natives-jar form), and its suffix check excludes the neighboring
        // arch-variant jars (`…-natives-windows-arm64.jar` / `…-natives-windows-x86.jar`), which a
        // prefix match would unpack over the x64 files.
        val jars = libs.mapNotNull { libRaw ->
            val lib = libRaw as? Map<*, *> ?: return@mapNotNull null
            if (!allowsOnThisPlatform(lib)) return@mapNotNull null
            val path = PlatformRules.nativesJarPath(lib, classifier)
                ?: return@mapNotNull null
            File(librariesDir, path).takeIf { it.isFile }
        }

        if (jars.isEmpty()) {
            // "nothing to extract" and "declared but not downloaded" are different situations; the
            // message below names both so an empty natives directory does not go unnoticed.
            log(
                "[AssetDownloader] no $classifier natives could be extracted for ${engine.version}: " +
                    "either the version declares none, or ${librariesDir.absolutePath} does not have them " +
                    "yet (run the library download first)",
            )
            return@withEngine emptyList()
        }

        nativesDir.mkdirs()
        val extracted = mutableListOf<File>()
        jars.forEachIndexed { index, jar ->
            progress?.onProgress(index.toLong(), jars.size.toLong(), "游戏原生库")
            extracted += extractNativeBinaries(jar, nativesDir, osName)
        }
        progress?.onProgress(jars.size.toLong(), jars.size.toLong(), "游戏原生库")
        log("[AssetDownloader] extracted ${extracted.size} native library file(s) ($classifier) -> ${nativesDir.absolutePath}")
        extracted
    }

    /**
     * Validates and downloads the assets named by an asset index.
     *
     * [explicitIndex] exists only for the legacy CLI form that takes an index id on the command line;
     * the typed API always resolves it from the version JSON.
     */
    internal fun fetchAssets(
        engine: GameEnvironment.Engine,
        assetsDir: File,
        version: String,
        force: Boolean,
        progress: DownloadProgress?,
        baseUrl: String = "https://resources.download.minecraft.net",
        explicitIndex: String? = null,
    ): Int = withEngine(engine) {
        val indexRef: IndexRef?
        val indexId: String
        if (explicitIndex != null) {
            // Legacy CLI form: the caller named the index. Its url can still come from the version JSON
            // when the id happens to be a version id, so try that first and fall back to the manifest
            // lookup inside ensureIndex.
            indexId = explicitIndex
            indexRef = runCatching { indexRefFor(client, explicitIndex) }.getOrNull()
        } else {
            val resolved = indexRefFor(client, version)
                ?: throw IllegalStateException("version $version is missing assetIndex")
            indexId = resolved.id
            indexRef = resolved
            log("[AssetDownloader] assetIndex of version $version: $indexId")
        }

        val indexFile = ensureIndex(client, assetsDir, indexId, indexRef)
        // Record the index id actually used — see ASSET_INDEX_MARKER: the game cannot derive it from
        // its own jar, and this marker, straight from the version metadata above, is authoritative.
        File(assetsDir, ASSET_INDEX_MARKER).writeText(indexId)
        val index = json.decodeFromString<AssetIndex>(indexFile.readText())
        // The hash is simultaneously the file name and the URL path, so it is a path component too:
        // a malformed (or tampered) index must never be able to point the downloader outside
        // assets/objects, where the removal of a "corrupt" file would otherwise delete user data.
        index.objects.values.firstOrNull { !SHA1_HEX.matches(it.hash) }?.let {
            throw IllegalStateException(
                "asset index ${indexFile.name} carries a malformed object hash '${it.hash}' (expected 40 hex digits)",
            )
        }

        val hashes = index.objects.values.distinctBy { it.hash }
        val downloaded = AtomicInteger()
        val skipped = AtomicInteger()
        val done = AtomicLong()

        progress?.onProgress(0, hashes.size.toLong(), "游戏资源")

        val pool = Executors.newVirtualThreadPerTaskExecutor()
        try {
            hashes.forEach { obj ->
                pool.submit {
                    verifyOrDownload(client, permits, assetsDir, obj, baseUrl, force, downloaded, skipped)
                    val n = done.incrementAndGet()
                    if (n % 64 == 0L || n == hashes.size.toLong()) {
                        progress?.onProgress(n, hashes.size.toLong(), "游戏资源")
                    }
                }
            }
        } finally {
            pool.shutdown()
            while (!pool.awaitTermination(1, TimeUnit.SECONDS)) {
                // wait for all downloads to finish
            }
        }

        log("[AssetDownloader] done: skipped ${skipped.get()}, downloaded ${downloaded.get()}, total ${hashes.size} resources")
        downloaded.get()
    }

    /** Whether a version-JSON library entry is allowed on the platform this build is running on. */
    private fun allowsOnThisPlatform(lib: Map<*, *>): Boolean =
        PlatformRules.allows(
            lib["rules"] as? List<*>,
            PlatformRules.currentOsName(),
            PlatformRules.currentArch(),
        )

    /**
     * Runs [body] with the engine's shared state in scope. Internal entry points take the
     * [GameEnvironment.Engine] (the state itself) rather than the public [GameEnvironment] wrapper.
     */
    private inline fun <T> withEngine(engine: GameEnvironment.Engine, body: GameEnvironment.Engine.() -> T): T =
        engine.body()

    /**
     * The version JSON's `assetIndex` block — the id the game is launched with, where to fetch the
     * index, and the digest to verify it against. The digest is the only handle on the index's
     * integrity: unlike the game jar it is fetched from a URL the version JSON merely names.
     */
    private data class IndexRef(val id: String, val url: String?, val sha1: String?, val size: Long?)

    private fun indexRefFor(client: HttpClient, version: String): IndexRef? {
        val ai = resolveVersionDetails(client, version)["assetIndex"] as? Map<*, *> ?: return null
        val id = ai["id"] as? String ?: return null
        return IndexRef(
            id = id,
            url = ai["url"] as? String,
            sha1 = ai["sha1"] as? String,
            size = (ai["size"] as? Number)?.toLong(),
        )
    }

    private fun ensureIndex(client: HttpClient, assetsDir: File, index: String, ref: IndexRef?): File {
        val indexFile = File(File(assetsDir, "indexes").apply { mkdirs() }, "$index.json")
        // The version JSON carries the index's size and SHA-1, so an existing file is re-verified
        // instead of trusted for being non-empty: a truncated index would otherwise stay in place
        // forever and surface only as "some assets are silently missing".
        if (indexFile.exists() && indexFile.length() > 0) {
            if (ref == null || indexMatches(indexFile, ref)) {
                log("[AssetDownloader] index already exists: ${indexFile.absolutePath}")
                return indexFile
            }
            log("[AssetDownloader] index does not match the version metadata, re-downloading: ${indexFile.absolutePath}")
            indexFile.delete()
        }

        // The resolved ref's url takes priority (provided by the version JSON); otherwise fall back
        // to resolving the index by name from the manifest.
        val url = ref?.url ?: run {
            log("[AssetDownloader] index missing; attempting to auto-download $index from the Mojang version manifest ...")
            indexRefFor(client, index)?.url
                ?: throw IllegalStateException("assetIndex.url missing in the version details")
        }

        log("[AssetDownloader] downloading asset index: $url")
        val bytes = httpGetBytes(client, url) ?: throw IllegalStateException("index download failed")
        if (ref != null && !indexMatches(bytes, ref)) {
            throw IllegalStateException(
                "asset index $index failed verification against the version metadata " +
                    "(expected size ${ref.size ?: "?"} / sha1 ${ref.sha1 ?: "?"}, got ${bytes.size} bytes)",
            )
        }
        writeAtomically(indexFile, bytes)
        log("[AssetDownloader] index ready: ${indexFile.absolutePath}")
        return indexFile
    }

    private fun indexMatches(file: File, ref: IndexRef): Boolean =
        (ref.size == null || file.length() == ref.size) && digestMatches(file, ref.sha1)

    private fun indexMatches(bytes: ByteArray, ref: IndexRef): Boolean {
        if (ref.size != null && bytes.size.toLong() != ref.size) return false
        if (ref.sha1.isNullOrBlank()) return true
        val actual = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
        return actual.equals(ref.sha1, ignoreCase = true)
    }

    private fun verifyOrDownload(
        client: HttpClient,
        permits: java.util.concurrent.Semaphore,
        assetsDir: File,
        obj: IndexObject,
        baseUrl: String,
        force: Boolean,
        downloaded: AtomicInteger,
        skipped: AtomicInteger,
    ) {
        val target = File(File(assetsDir, "objects/${obj.hash.substring(0, 2)}"), obj.hash)
        // For assets the digest is not merely available — it *is* the file name and the URL path. An
        // object that fails it is unusable by construction (the game looks itself up by hash), so the
        // existing file is re-verified rather than trusted on size alone.
        if (!force && target.exists() && target.length() == obj.size && digestMatches(target, obj.hash)) {
            skipped.incrementAndGet()
            return
        }
        if (!force && target.exists()) {
            log("[AssetDownloader] corrupt file removed: ${obj.hash}")
            target.delete()
        }

        val url = "$baseUrl/${obj.hash.substring(0, 2)}/${obj.hash}"
        // occasional EOF / handshake resets are transient errors; simply retry
        repeat(3) { attempt ->
            try {
                permits.acquire()
                val bytes = try {
                    httpGetBytes(client, url)
                } finally {
                    permits.release()
                } ?: throw IllegalStateException("HTTP $url returned non-200")
                if (bytes.size != obj.size.toInt()) {
                    throw IllegalStateException("download size mismatch: expected ${obj.size}, got ${bytes.size}")
                }
                val actual = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
                if (!actual.equals(obj.hash, ignoreCase = true)) {
                    throw IllegalStateException("asset hash mismatch: expected ${obj.hash}, got $actual")
                }
                writeAtomically(target, bytes)
                downloaded.incrementAndGet()
                return
            } catch (e: Exception) {
                if (attempt == 2) error("[AssetDownloader] download failed $url -> ${e.message}")
                else Thread.sleep(200L * (attempt + 1))
            }
        }
    }

    private fun asArtifact(artifact: Map<*, *>, isNatives: Boolean): VersionArtifact {
        val path = artifact["path"] as? String
            ?: throw IllegalStateException("a library artifact declares no path; cannot be laid out")
        val url = artifact["url"] as? String
            ?: throw IllegalStateException("library $path declares no url")
        return VersionArtifact(
            path = path,
            url = url,
            size = (artifact["size"] as? Number)?.toLong(),
            sha1 = artifact["sha1"] as? String,
            isNatives = isNatives,
        )
    }

    private fun downloadArtifactSingle(
        client: HttpClient,
        permits: java.util.concurrent.Semaphore,
        artifact: VersionArtifact,
        destDir: File,
        added: MutableList<File>,
        skipped: AtomicInteger,
    ) {
        val target = File(destDir, artifact.path)
        // same rule as the game jar: an existing file must pass the digest too, or a library that
        // was corrupted by an interrupted earlier run stays corrupt forever
        if (target.isFile && (artifact.size == null || target.length() == artifact.size) &&
            digestMatches(target, artifact.sha1)
        ) {
            skipped.incrementAndGet()
            return
        }
        // No permit is taken here: downloadToFile owns it. Acquiring one here as well deadlocks — the
        // pool offers every library at once on virtual threads, and a virtual thread that blocks on
        // acquire() unmounts and frees its carrier, so the pool happily fills all sixteen permits with
        // threads that then all block waiting for a second one. Nothing reports an error; the build
        // simply stops.
        downloadToFile(
            client, permits, artifact.url, target, artifact.size,
            artifact.path.substringAfterLast('/'), null, artifact.sha1,
        )
        added += target
    }

    /**
     * The shared-library file extensions the natives jars of [osName] carry. Windows unpacks `.dll`,
     * macOS `.dylib` (plus the legacy `.jnilib` some older macOS natives jars ship), Linux `.so`.
     * Filtering by extension — rather than extracting everything — keeps the natives directory to
     * exactly what the JVM can link against on this platform.
     */
    private fun nativeExtensions(osName: String): Set<String> = when (osName) {
        PlatformRules.WINDOWS -> setOf(".dll")
        PlatformRules.MACOS -> setOf(".dylib", ".jnilib")
        else -> setOf(".so")
    }

    private fun extractNativeBinaries(jar: File, nativesDir: File, osName: String): List<File> {
        val extensions = nativeExtensions(osName)
        nativesDir.mkdirs()
        val written = mutableListOf<File>()
        ZipInputStream(jar.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (extensions.any { entry.name.endsWith(it) }) {
                    val out = File(nativesDir, entry.name.substringAfterLast('/'))
                    out.outputStream().use { zip.copyTo(it) }
                    written += out
                }
            }
        }
        return written
    }


    // -----------------------------------------------------------------------------------------------
    // Shared plumbing
    // -----------------------------------------------------------------------------------------------

    /**
     * Streams a URL into [target] through a `.tmp` file: reports progress as bytes arrive, enforces
     * [expectedSize] and [expectedSha1] when known, retries transient failures, and only then moves the
     * file into place.
     *
     * Streaming (rather than `BodyHandlers.ofByteArray`) keeps the progress bar meaningful on the big
     * downloads. The digest is computed **while the bytes stream to the `.tmp` file** rather than by
     * re-reading it: an extra pass over a ~26 MB jar is a visible pause right at the end of an install.
     */
    private fun downloadToFile(
        client: HttpClient,
        permits: java.util.concurrent.Semaphore,
        url: String,
        target: File,
        expectedSize: Long?,
        label: String,
        progress: DownloadProgress?,
        expectedSha1: String? = null,
    ): Long {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        var lastError: Exception? = null
        repeat(3) { attempt ->
            tmp.delete()
            var acquired = false
            try {
                // The permit covers the *whole* transfer — handshake and body. Holding it only around
                // client.send() would make the cap decorative: the body is where the bytes and the
                // wall time are. Released in the finally below, so a failed attempt cannot leak one.
                permits.acquire()
                acquired = true
                val request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .timeout(Duration.ofMinutes(30)).GET().build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() != 200) {
                    throw IllegalStateException("HTTP ${response.statusCode()}：$url")
                }
                val total = expectedSize
                    ?: response.headers().firstValueAsLong("content-length").orElse(-1L).takeIf { it > 0 }
                    ?: -1L
                var done = 0L
                var lastReported = 0L
                val digest = MessageDigest.getInstance("SHA-1")
                response.body().use { input ->
                    DigestInputStream(input, digest).use { digested ->
                        tmp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(1 shl 16)
                            while (true) {
                                val read = digested.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                done += read
                                if (done - lastReported >= (512 shl 10)) {
                                    lastReported = done
                                    progress?.onProgress(done, if (total > 0) total else 0L, label)
                                }
                            }
                        }
                    }
                }
                if (expectedSize != null && done != expectedSize) {
                    throw IllegalStateException("下载大小不符：期望 $expectedSize，实际 $done（$url）")
                }
                if (done == 0L) throw IllegalStateException("下载得到 0 字节（$url）")
                // A digest mismatch means the bytes on the wire were not the bytes we asked for — a
                // truncated response that happens to hit the right size, a mirror serving a substitute,
                // or a proxy rewriting the body. Delete and retry; if all attempts fail the throw below
                // carries the reason, and nothing partial is ever moved into place.
                if (expectedSha1 != null && !sha1HexOfDigest(digest).equals(expectedSha1, ignoreCase = true)) {
                    throw IllegalStateException(
                        "下载内容校验失败（SHA-1 不符）：期望 $expectedSha1，实际 " +
                            "${sha1HexOfDigest(digest)}（$url）",
                    )
                }
                moveIntoPlace(tmp, target)
                progress?.onProgress(if (total > 0) total else done, if (total > 0) total else done, label)
                return done
            } catch (e: Exception) {
                lastError = e
                tmp.delete()
                if (attempt < 2) {
                    error("[AssetDownloader] 下载失败（第 ${attempt + 1} 次）：${e.message}，稍后重试")
                    Thread.sleep(300L * (attempt + 1))
                }
            } finally {
                if (acquired) permits.release()
            }
        }
        throw IllegalStateException(
            "下载失败：$label\n原因：${lastError?.message}\n请检查网络连接，或配置代理后重试（$url）",
            lastError,
        )
    }

    /** Lowercase hex of a completed digest. */
    private fun sha1HexOfDigest(digest: MessageDigest): String =
        digest.digest().joinToString("") { "%02x".format(it) }

    /** Writes [bytes] through a `.tmp` file, so an interrupted write cannot leave a half file behind. */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        moveIntoPlace(tmp, target)
    }

    /**
     * Moves a fully written `.tmp` file onto [target].
     *
     * `ATOMIC_MOVE` is the guarantee the caller wants: a reader sees the old file or the new one, never
     * a partial one. Across filesystems (a `.tmp` on a different mount, a network share, a container
     * bind mount) the JDK cannot rename — this is the one place the guarantee has to be **downgraded
     * rather than assumed**. Only the move itself is retried without it; the bytes were already
     * verified by the caller, so a non-atomic move still lands a complete file.
     */
    private fun moveIntoPlace(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // cross-device: REPORT the downgrade (never silently), then finish the move
            error("[AssetDownloader] 原子替换不可用（跨文件系统），退化为普通移动：${target.absolutePath}")
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * SHA-1 of a file, lowercase hex. Streamed rather than read into memory: the callers include a
     * ~26 MB game jar, and a checksum must be cheap enough that nobody is tempted to skip it.
     *
     * Public so the installer's contract tests can assert the *same* algorithm the download path uses,
     * instead of testing a reimplementation.
     */
    fun sha1Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        DigestInputStream(file.inputStream().buffered(), digest).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (input.read(buffer) >= 0) {
                // DigestInputStream does the work on read
            }
        }
        return sha1HexOfDigest(digest)
    }

    /**
     * True when [file] is an acceptable copy of [expectedSha1].
     *
     * A missing or blank [expectedSha1] means "the source does not publish a digest"; Piston gives
     * every game jar and library one, so in practice this falls back to size-only rarely. The
     * enforcement is deliberately **not** symmetric: a digest that disagrees always fails, whereas a
     * digest we simply do not have can only be skipped.
     *
     * Public for the same reason as [sha1Hex]: the installer's tests must exercise the real rule.
     */
    fun digestMatches(file: File, expectedSha1: String?): Boolean {
        return expectedSha1.isNullOrBlank() || sha1Hex(file).equals(expectedSha1, ignoreCase = true)
    }

    internal fun newClient(proxy: ProxySelector? = null): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
        // HttpClient's default executor is a cached thread pool, which under a virtual-thread fan-out
        // means platform threads are the real bottleneck. A virtual-thread executor keeps the two
        // limits in one place: our own Semaphore decides how many downloads run, and the carrier pool
        // is free to be small.
        builder.executor(Executors.newVirtualThreadPerTaskExecutor())
        (proxy ?: proxyOverride ?: ProxySelector.getDefault())?.let { builder.proxy(it) }
        return builder.build()
    }

    /** Releases the JDK client's resources when an engine goes away. */
    internal fun closeClient(client: HttpClient) {
        try {
            client.close()
        } catch (_: Exception) {
            // HttpClient.close() only throws when already closed; nothing useful to do about it
        }
    }

    private fun resolveVersionDetails(client: HttpClient, versionId: String): Map<*, *> {
        val manifest = httpGet(client, VERSION_MANIFEST_URL)
            ?: throw IllegalStateException("无法获取版本清单（version_manifest_v2.json）")
        val parsed = parseToPlain(manifest)
        val versionList = parsed?.get("versions") as? List<*> ?: throw IllegalStateException("版本清单格式异常")

        // The literal `snapshot` is an alias for the manifest's latest snapshot: the id churns every
        // week or two, and pinning it would turn every snapshot-tracking project into a version bump.
        // Real ids pass through unchanged. Every caller re-verifies downloaded artifacts against the
        // resolved version's own SHA-1, so a newer snapshot replaces the old files on the next run.
        val effectiveId = if (versionId == "snapshot") {
            val latest = (parsed["latest"] as? Map<*, *>)?.get("snapshot") as? String
                ?: throw IllegalStateException("版本清单没有 latest.snapshot 字段，无法解析别名 snapshot")
            log("[AssetDownloader] version 'snapshot' resolves to latest snapshot '$latest'")
            latest
        } else {
            versionId
        }

        val versionUrl = versionList.mapNotNull { v ->
            val m = v as? Map<*, *> ?: return@mapNotNull null
            if (m["id"] == effectiveId) m["url"] as? String else null
        }.firstOrNull() ?: throw IllegalStateException("版本清单里没有 id=$effectiveId")

        val versionJson = httpGet(client, versionUrl) ?: throw IllegalStateException("无法获取 $versionId 的版本详情")
        return parseToPlain(versionJson)
            ?: throw IllegalStateException("版本详情格式异常")
    }

    /**
     * Resolves the `snapshot` alias to the version manifest's latest snapshot id (network call).
     *
     * [resolveVersionDetails] already honors the alias for everything it downloads; this function
     * exists for callers that need the **resolved id itself** before any download — the installer
     * writes the real id into the launcher version JSON (`inheritsFrom`), the server directory name
     * and `launch.properties`, and none of those documents may carry a literal "snapshot": the
     * launcher looks up vanilla versions by exact id.
     *
     * Honors [proxyOverride]; throws [IllegalStateException] with a readable message when the
     * manifest is unreachable or has no snapshot entry — callers translate that into their own
     * user-facing error.
     */
    fun resolveLatestSnapshotId(): String {
        val client = newClient()
        try {
            val manifest = httpGet(client, VERSION_MANIFEST_URL)
                ?: throw IllegalStateException("无法获取版本清单（version_manifest_v2.json）")
            val parsed = parseToPlain(manifest)
                ?: throw IllegalStateException("版本清单格式异常")
            return (parsed["latest"] as? Map<*, *>)?.get("snapshot") as? String
                ?: throw IllegalStateException("版本清单没有 latest.snapshot 字段，无法解析别名 snapshot")
        } finally {
            closeClient(client)
        }
    }

    private fun httpGet(client: HttpClient, url: String): String? {
        val resp = client.send(
            HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5)).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        return if (resp.statusCode() == 200) resp.body() else null
    }

    private fun httpGetBytes(client: HttpClient, url: String): ByteArray? {
        val resp = client.send(
            HttpRequest.newBuilder(URI.create(url)).header("User-Agent", USER_AGENT)
                .timeout(Duration.ofMinutes(5)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray(),
        )
        return if (resp.statusCode() == 200) resp.body() else null
    }
}
