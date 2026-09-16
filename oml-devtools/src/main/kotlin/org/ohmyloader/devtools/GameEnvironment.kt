package org.ohmyloader.devtools

import java.io.File
import java.net.ProxySelector
import java.net.http.HttpClient
import java.util.concurrent.Semaphore

/**
 * Strongly-typed, in-process API over everything [AssetDownloader] can do: named methods with [File]
 * parameters and typed returns, no process boundary, failures as exceptions. [AssetDownloader] keeps
 * its CLI surface as a thin argument parser over this engine, so the two cannot drift apart. An
 * instance owns **one** [HttpClient] and **one** shared download-permit semaphore: the cap of
 * [AssetDownloader.MAX_PARALLEL_DOWNLOADS] exists because a burst of thousands of connections
 * triggers CDN throttling (EOF / handshake resets), so a whole batch of work must share one
 * environment. [AutoCloseable] is real: closing releases the client.
 *
 * @param version the version id every call resolves against, e.g. `26.3` — an `init` property, not a
 *   per-call argument: a version is what an *environment* is.
 */
class GameEnvironment private constructor(
    val version: String,
    proxy: ProxySelector?,
) : AutoCloseable {

    /**
     * The engine state [AssetDownloader]'s internals operate on. `internal` rather than private so
     * the CLI path can drive the *same* instance instead of a second engine type growing alongside.
     */
    internal class Engine(
        val version: String,
        val client: HttpClient,
        val permits: Semaphore,
    )

    private val engine = Engine(
        version = version,
        client = AssetDownloader.newClient(proxy),
        permits = Semaphore(AssetDownloader.MAX_PARALLEL_DOWNLOADS),
    )

    internal fun state(): Engine = engine

    /**
     * Ensures the **client** jar of this version is present and intact at [targetFile].
     *
     * Both the size and the SHA-1 from the version JSON are enforced, and an *existing* file has to
     * pass the same checks a fresh download would — otherwise a truncated jar left behind by a killed
     * process is accepted by every later run, an unrecoverable state.
     *
     * @param force re-download even when the existing file verifies.
     * @return [targetFile], having been created or confirmed.
     */
    fun downloadClientJar(targetFile: File, force: Boolean = false): File =
        AssetDownloader.downloadGameJar(engine, targetFile, force, null)

    /**
     * Downloads every library the version JSON declares for this platform into [librariesDir],
     * laid out at its Maven path (`librariesDir/<group-as-path>/<artifact>/<version>/<file>`).
     *
     * @return the files that were **newly written**. Files already present and verifying are *not* in
     *   the list; the normal steady state is an empty result, which should read as success rather than
     *   as "nothing happened".
     */
    fun downloadLibraries(
        librariesDir: File,
        progress: AssetDownloader.DownloadProgress? = null,
    ): List<File> = AssetDownloader.downloadLibraries(engine, librariesDir, progress)

    /**
     * Extracts the native libraries (`.dll` / `.dylib` / `.so`) of this version **for the platform the
     * JVM runs on** into [nativesDir]. A separate step from [downloadLibraries] on purpose: the jars
     * to unpack are located by reading the version JSON with the current OS/arch's natives classifier
     * (windows / linux / osx, arch variants included), not by walking the libraries directory — a walk
     * also matches jars belonging to a *different* version installed side by side. Precondition: the
     * libraries are already in place (call [downloadLibraries] first).
     *
     * @return the extracted native library files, or an empty list when this version declares none for
     *   this platform (a legitimate outcome, not an error).
     */
    fun extractNatives(
        librariesDir: File,
        nativesDir: File,
        progress: AssetDownloader.DownloadProgress? = null,
    ): List<File> = AssetDownloader.extractNatives(engine, librariesDir, nativesDir, progress)

    /**
     * Validates and downloads the game assets of this version into [assetsDir] (`objects/`,
     * `indexes/`).
     *
     * The asset index id is resolved **from the version JSON**, not asked for: it is a property of the
     * version, so leaving it out of this signature removes a way to get it wrong.
     *
     * @return how many object files were newly written.
     */
    fun downloadAssets(
        assetsDir: File,
        force: Boolean = false,
        progress: AssetDownloader.DownloadProgress? = null,
    ): Int = AssetDownloader.fetchAssets(engine, assetsDir, version, force, progress, explicitIndex = null)

    override fun close() {
        AssetDownloader.closeClient(engine.client)
    }

    companion object {

        /** Opens an environment for [version]. [proxy] overrides the process-wide default. */
        @JvmStatic
        @JvmOverloads
        fun open(version: String, proxy: ProxySelector? = null): GameEnvironment {
            require(version.isNotBlank()) { "a Minecraft version id is required, e.g. 26.3" }
            // AssetDownloader.newClient falls back to proxyOverride itself, so a GUI that assigns it
            // after startup still takes effect.
            return GameEnvironment(version, proxy)
        }
    }
}
