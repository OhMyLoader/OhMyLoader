package org.ohmyloader.installer

import org.ohmyloader.devtools.AssetDownloader
import org.ohmyloader.installer.Installer.Options.Companion.BOOLEAN_FLAGS
import java.io.File

/**
 * Command line front end, and the facade both front ends go through. The install itself lives in
 * [InstallationTarget] (one implementation per shape: standard launcher / Prism instance / dedicated
 * server), because the three differ in almost every detail while sharing almost every step.
 *
 * The install path never calls `exitProcess`: failures are [InstallationException]s that travel up to
 * whichever front end can present them (a dialog for the GUI, stderr for here). The exit codes below
 * are this process's own boundary — the Gradle task and a shell script do need them.
 */
object Installer {

    // -------------------------------------------------------------------------------------------
    // CLI
    // -------------------------------------------------------------------------------------------

    @JvmStatic
    fun main(args: Array<String>) {
        pinStdoutToUtf8()
        if (args.isEmpty() || args.contains("--help") || args.contains("-h")) {
            printUsage()
            return
        }
        try {
            val options = Options.parse(args)
            if (options.proxy != null) {
                AssetDownloader.proxyOverride = AssetDownloader.parseProxy(options.proxy)
                    ?: throw InstallationException(Messages.t("cli.proxyInvalid", options.proxy))
            }
            if (options.uninstall) {
                val ctx = options.toUninstallContext()
                println("${Messages.t("log.prefix")} ${Messages.t("cli.banner.target", options.target.displayName)}")
                println("${Messages.t("log.prefix")} ${Messages.t("cli.banner.dir", ctx.dir)}")
                println("${Messages.t("log.prefix")} ${Messages.t("cli.banner.id", ctx.installId)}")
                val hint = Uninstaller.perform(ctx)
                println()
                println("${Messages.t("log.prefix")} $hint")
                return
            }
            options.artifacts().use { artifacts ->
                val ctx = options.toContext(artifacts)
                val sink = ConsoleProgressSink()
                println("${Messages.t("log.prefix")} ${Messages.t("cli.banner.target", options.target.displayName)}")
                println("${Messages.t("log.prefix")} ${Messages.t("cli.banner.dir", ctx.targetDir)}")
                println("${Messages.t("log.prefix")} ${Messages.t("cli.banner.id", ctx.installId)}")
                val hint = performInstall(ctx, options.target, sink)
                println()
                println("${Messages.t("log.prefix")} $hint")
            }
        } catch (e: InstallationException) {
            System.err.println()
            System.err.println(Messages.t("cli.failed", e.message ?: ""))
            exitWith(2)
        } catch (t: Throwable) {
            System.err.println()
            System.err.println(Messages.t("cli.failedUnexpected", t.message ?: ""))
            t.printStackTrace()
            exitWith(1)
        }
    }

    /**
     * Validates and then installs. Shared by the CLI and the GUI so the two can never disagree about
     * what counts as acceptable input.
     *
     * Warnings are logged and then ignored by design: they describe situations the user is allowed to
     * be in (an unusual directory layout). Errors abort.
     *
     * A failure mid-install is rolled back first: every write the install made is recorded
     * in the context's [InstallJournal], and the exception only travels up after the tree has been
     * restored to its pre-install state — the user retries into a clean directory, not a
     * "nearly installed" one.
     */
    fun performInstall(ctx: InstallContext, target: InstallationTarget, sink: ProgressSink): String {
        val validation = target.validate(ctx)
        validation.warnings.forEach { ctx.log("${Messages.t("log.prefix")} " + Messages.t("cli.warning", it)) }
        if (!validation.ok) {
            throw InstallationException(validation.errors.joinToString("\n\n"))
        }
        try {
            target.install(ctx, sink)
        } catch (t: Throwable) {
            ctx.journal.rollback().forEach { ctx.log("${Messages.t("log.prefix")} $it") }
            throw t
        }
        return target.successHint(ctx)
    }

    private fun printUsage() {
        // The help text lives in the bundle like everything else a user reads; its line breaks are
        // written as \n there.
        println(Messages.t("cli.help"))
        println()
        println(Messages.t("cli.eula", EULA_URL))
    }

    // -------------------------------------------------------------------------------------------
    // Argument model
    // -------------------------------------------------------------------------------------------

    internal class Options(
        val target: InstallationTarget,
        val version: String,
        val dir: File,
        val id: String,
        val isolation: Boolean,
        val acceptSharedMods: Boolean,
        val acceptEula: Boolean,
        val addPrismComponent: Boolean,
        val side: String,
        val layerJars: List<File>,
        val shellJar: File?,
        /** Packaged oml-native jars (main + natives-<classifier>), when the caller has them. */
        val nativeJars: List<File>,
        val proxy: String?,
        val modsDirName: String,
        val uninstall: Boolean,
    ) {

        /**
         * Resource source: explicit files when `--layer-jar` was given (the Gradle task), otherwise the
         * layer embedded in this installer jar — which is what makes
         * `java -jar oml-installer.jar --target server ...` work on a headless Linux box with no extra
         * arguments.
         */
        fun artifacts(): ArtifactSource =
            if (layerJars.isEmpty())
                FatJarArtifactSource(version, VersionCatalog.find(version)?.adapterArtifact.orEmpty())
            else DirectoryArtifactSource(version, layerJars, shellJar, nativeJarFiles = nativeJars)

        /** Context for [Uninstaller]: which shape, which directory, which install id. */
        fun toUninstallContext(): UninstallContext =
            UninstallContext(
                target = target,
                dir = dir.absoluteFile,
                installId = id.ifBlank { "$version-OML" },
                log = { println(it) },
            )

        fun toContext(artifacts: ArtifactSource): InstallContext {
            val supported = VersionCatalog.find(version)
                ?: throw InstallationException(
                    Messages.t(
                        "err.versionNotBundled",
                        version,
                        VersionCatalog.versions().joinToString(", ") { it.version },
                    ),
                )
            val resolved = resolveSnapshotAlias(supported)
            return InstallContext(
                target = resolved,
                targetDir = dir.absoluteFile,
                installId = id.ifBlank { "${resolved.version}-OML" },
                isolation = isolation,
                acceptEula = acceptEula,
                allowSharedMods = acceptSharedMods,
                artifacts = artifacts,
                side = side,
                modsDirName = modsDirName,
                addPrismComponent = addPrismComponent,
            )
        }

        companion object {
            /** Flags that take a value; everything else must be one of [BOOLEAN_FLAGS]. */
            private val VALUE_FLAGS = setOf(
                "--target", "--version", "--dir", "--id", "--isolation", "--side",
                "--layer-jar", "--shell-jar", "--native-jar", "--proxy", "--mods-dir-name",
            )
            private val BOOLEAN_FLAGS =
                setOf("--accept-eula", "--accept-shared-mods", "--add-prism-component", "--uninstall")

            fun parse(args: Array<String>): Options {
                val values = HashMap<String, MutableList<String>>()
                val flags = HashSet<String>()
                var i = 0
                while (i < args.size) {
                    val key = args[i]
                    if (key in BOOLEAN_FLAGS) {
                        flags += key
                        i += 1
                        continue
                    }
                    if (key !in VALUE_FLAGS) {
                        throw InstallationException(Messages.t("cli.unknownArg", key))
                    }
                    if (i + 1 >= args.size) throw InstallationException(Messages.t("cli.missingValue", key))
                    values.getOrPut(key) { mutableListOf() } += args[i + 1]
                    i += 2
                }

                fun one(key: String): String? = values[key]?.lastOrNull()
                fun required(key: String): String =
                    one(key) ?: throw InstallationException(Messages.t("cli.missingRequired", key))

                val uninstall = "--uninstall" in flags
                // Uninstalling removes what an install wrote; the game version is not part of that
                // record (the install id identifies it), so it is optional here — but then the id
                // cannot be derived from it either and must be given.
                val version = if (uninstall) one("--version") ?: "" else required("--version")
                val dir = File(required("--dir"))
                val side = one("--side")?.lowercase() ?: "client"
                if (side != "client" && side != "server") {
                    throw InstallationException(Messages.t("cli.sideValue", side))
                }
                val target = resolveTarget(one("--target")?.lowercase(), side)

                val isolation = when (val isolationRaw = one("--isolation")?.lowercase()) {
                    null if !uninstall && target === StandardLauncherTarget && side == "client" ->
                        throw InstallationException(Messages.t("cli.isolationRequired"))

                    null -> true
                    "true" -> true
                    "false" -> false
                    else -> throw InstallationException(Messages.t("cli.isolationValue", isolationRaw))
                }

                val layerJars = values["--layer-jar"].orEmpty().map(::File)

                val id = one("--id") ?: ""
                if (uninstall) {
                    // the default install id derives from the version; without either there is
                    // nothing that identifies what to remove
                    val installId = when {
                        id.isNotBlank() -> id
                        version.isNotBlank() -> "$version-OML"
                        else -> throw InstallationException(Messages.t("cli.missingRequired", "--id"))
                    }
                    validateInstallId(installId).firstOrNull()?.let { throw InstallationException(it) }
                }

                return Options(
                    target = target,
                    version = version,
                    dir = dir,
                    id = id,
                    isolation = isolation,
                    acceptSharedMods = "--accept-shared-mods" in flags,
                    acceptEula = "--accept-eula" in flags,
                    addPrismComponent = "--add-prism-component" in flags,
                    side = if (target === DedicatedServerTarget) "server" else side,
                    layerJars = layerJars,
                    shellJar = one("--shell-jar")?.let(::File),
                    nativeJars = values["--native-jar"].orEmpty().map(::File),
                    proxy = one("--proxy"),
                    modsDirName = one("--mods-dir-name") ?: "mods",
                    uninstall = uninstall,
                )
            }
        }
    }

    private fun exitWith(code: Int): Nothing {
        // This is the CLI process boundary — the only place an exit code is produced. The GUI never
        // reaches here: it calls performInstall() directly and shows a dialog instead.
        kotlin.system.exitProcess(code)
    }
}

/**
 * The catalogue key under which the snapshot-tracking adapter (`oml-adapter-snapshot`) is bundled.
 * The real snapshot id churns every week or two, so the catalogue pins the stable alias instead and
 * [resolveSnapshotAlias] resolves it to the manifest's latest snapshot at install time.
 */
const val SNAPSHOT_ALIAS = "snapshot"

/**
 * Resolves the `snapshot` catalogue entry to its real version id. Everything written downstream —
 * the launcher version JSON's `inheritsFrom`, the game jar path, the server directory layout — must
 * carry the resolved id: launchers look vanilla versions up by exact id, and a literal "snapshot"
 * there would inherit from nothing.
 *
 * Resolution needs the network (a server install already carries that dependency; a standard install
 * gains it only when this alias is chosen). Failure is an [InstallationException] naming the escape
 * hatch — pass the real version id directly. [fetchLatest] is the seam tests stub instead.
 */
internal fun resolveSnapshotAlias(
    supported: SupportedVersion,
    fetchLatest: () -> String = { AssetDownloader.resolveLatestSnapshotId() },
): SupportedVersion =
    if (supported.version != SNAPSHOT_ALIAS) {
        supported
    } else {
        try {
            supported.copy(version = fetchLatest())
        } catch (e: Exception) {
            throw InstallationException(Messages.t("err.snapshotResolve", e.message ?: e.toString()), e)
        }
    }

/**
 * Resolves the target implied by the arguments, honoring the `--side server` shorthand.
 *
 * File-level rather than a member of [Installer]: `Options` is a nested (not `inner`) class, so it has
 * no implicit receiver for the outer object and could not call a member function by simple name.
 */
private fun resolveTarget(targetId: String?, side: String): InstallationTarget {
    if (targetId != null) {
        return InstallationTarget.byId(targetId)
            ?: throw InstallationException(
                Messages.t("cli.targetUnknown", targetId, InstallationTarget.ALL.joinToString(" | ") { it.id }),
            )
    }
    return if (side == "server") DedicatedServerTarget else StandardLauncherTarget
}
