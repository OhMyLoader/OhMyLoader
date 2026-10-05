package org.ohmyloader.installer

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import org.ohmyloader.devtools.AssetDownloader
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.io.File
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.text.DefaultCaret

/**
 * Swing front end. The install runs off the EDT and every failure is caught and shown as a dialog plus
 * the untouched console log — a failure must never make the window disappear. The progress bar reports
 * real percentages (an indeterminate bar is indistinguishable from a hang, and users kill hangs), and
 * paths and versions come from data, never from guesses.
 */
object SwingInstaller {

    @JvmStatic
    fun main(args: Array<String>) {
        pinStdoutToUtf8()
        SwingUtilities.invokeLater {
            applyLookAndFeel()
            build()
        }
    }

    /**
     * FlatLaf, with the platform look and feel as a fallback.
     *
     * `FlatLightLaf.setup()` rather than `UIManager.setLookAndFeel(new FlatLightLaf())`: FlatLaf
     * installs a whole set of its own defaults inside `setup()` (fonts, control metrics, rounded
     * corners, window decorations); installing the LookAndFeel object alone leaves the UI half-styled.
     *
     * Theme: light by default; `-Doml.installer.theme=dark` switches to FlatDarkLaf. A failure here
     * must never cost the user the GUI: it falls through to the platform LAF, then to Metal.
     */
    private fun applyLookAndFeel() {
        val dark = System.getProperty("oml.installer.theme", "light").equals("dark", ignoreCase = true)
        try {
            if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
            return
        } catch (t: Throwable) {
            System.err.println("[installer] FlatLaf unavailable, falling back to the system look and feel: ${t.message}")
        }
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
        } catch (_: Exception) {
            // leave the default Metal LAF as the last resort
        }
    }

    private const val STANDARD = "standard"
    private const val PRISM = "prism"
    private const val SERVER = "server"

    /** Prism's per-user data directory — where `instances/<name>/` lives. */
    private fun defaultPrismRoot(): File {
        val home = System.getProperty("user.home") ?: "."
        val os = System.getProperty("os.name", "").lowercase()
        return when {
            os.contains("win") -> File(System.getenv("APPDATA") ?: "$home/AppData/Roaming", "PrismLauncher")
            os.contains("mac") -> File(home, "Library/Application Support/PrismLauncher")
            else -> File(home, ".local/share/PrismLauncher")
        }
    }

    /** Which game versions this installer can actually serve, by checking the embedded layer is present. */
    private fun availableVersions(): List<SupportedVersion> =
        VersionCatalog.versions().filter { v ->
            runCatching {
                FatJarArtifactSource(v.version, v.adapterArtifact).use {
                    it.layerJars().isNotEmpty()
                }
            }.getOrDefault(false)
        }

    private fun build() {
        val versions = runCatching { availableVersions() }.getOrElse {
            JOptionPane.showMessageDialog(
                null,
                Messages.t("dlg.catalogFailed", it.message),
                Messages.t("app.title"), JOptionPane.ERROR_MESSAGE,
            )
            return
        }
        if (versions.isEmpty()) {
            JOptionPane.showMessageDialog(
                null,
                Messages.t("dlg.noVersions"),
                Messages.t("app.title"), JOptionPane.ERROR_MESSAGE,
            )
            return
        }

        val supportedByLabel: Map<String, SupportedVersion> =
            versions.associateBy { Messages.t("ui.versionItem", it.version, it.javaMajor) }

        // ---- shared controls ------------------------------------------------------------------
        val sideLabels = linkedMapOf(
            StandardLauncherTarget.displayName to STANDARD,
            PrismComponentTarget.displayName to PRISM,
            DedicatedServerTarget.displayName to SERVER,
        )
        val targetBox = JComboBox(sideLabels.keys.toTypedArray())
        val versionBox = JComboBox(supportedByLabel.keys.toTypedArray())
        val dirField = JTextField(StandardLauncherTarget.defaultGameDir().absolutePath)
        val browseBtn = JButton(Messages.t("ui.browse"))
        val idField = JTextField()
        val isolationCheck = JCheckBox(Messages.t("ui.isolation.check"), true)
        val prismComponentCheck = JCheckBox(Messages.t("ui.prism.check"), false)
        val eulaCheck = JCheckBox(Messages.t("ui.eula.check"), false)
        val proxyField = JTextField()
        val installButton = JButton(Messages.t("ui.install")).apply { preferredSize = Dimension(120, 32) }
        val copyLogButton = JButton(Messages.t("ui.copyLog"))
        val openDirButton = JButton(Messages.t("ui.openDir"))

        val progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            string = Messages.t("ui.ready")
            isVisible = true
        }

        val log = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            margin = Insets(6, 6, 6, 6)
            (caret as? DefaultCaret)?.updatePolicy = DefaultCaret.ALWAYS_UPDATE
        }

        val formPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(Messages.t("ui.form.title")),
                EmptyBorder(8, 12, 10, 12),
            )
        }
        val gbc = GridBagConstraints().apply {
            insets = Insets(5, 5, 5, 5)
            anchor = GridBagConstraints.WEST
        }

        addFormRow(formPanel, gbc, 0, Messages.t("ui.label.target"), targetBox)
        addFormRow(formPanel, gbc, 1, Messages.t("ui.label.version"), versionBox)

        val dirPickerPanel = JPanel(BorderLayout(6, 0)).apply {
            add(dirField, BorderLayout.CENTER)
            add(browseBtn, BorderLayout.EAST)
        }
        addFormRow(formPanel, gbc, 2, Messages.t("ui.label.dir"), dirPickerPanel)
        addFormRow(formPanel, gbc, 3, Messages.t("ui.label.id"), idField)

        // per-target extra options
        val standardCard = JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5); anchor = GridBagConstraints.WEST }
            addFormRow(this, c, 0, Messages.t("ui.label.isolation"), isolationCheck)
        }
        val prismCard = JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5); anchor = GridBagConstraints.WEST }
            addFormRow(this, c, 0, Messages.t("ui.label.prismComponent"), prismComponentCheck)
        }
        val serverCard = JPanel(GridBagLayout()).apply {
            val c = GridBagConstraints().apply { insets = Insets(5, 5, 5, 5); anchor = GridBagConstraints.WEST }
            addFormRow(this, c, 0, Messages.t("ui.label.eula"), eulaCheck)
        }
        val cardsLayout = CardLayout()
        val cardsPanel = JPanel(cardsLayout).apply {
            add(standardCard, STANDARD)
            add(prismCard, PRISM)
            add(serverCard, SERVER)
        }
        formPanel.add(
            cardsPanel,
            GridBagConstraints().apply {
                insets = Insets(5, 5, 5, 5)
                anchor = GridBagConstraints.WEST
                gridx = 0
                gridy = 4
                gridwidth = 2
                weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
            },
        )
        addFormRow(formPanel, gbc, 5, Messages.t("ui.label.proxy"), proxyField)

        // ---- helpers ---------------------------------------------------------------------------
        fun selectedTargetId(): String = sideLabels[targetBox.selectedItem as String]!!

        fun selectedTarget(): InstallationTarget =
            when (selectedTargetId()) {
                PRISM -> PrismComponentTarget
                SERVER -> DedicatedServerTarget
                else -> StandardLauncherTarget
            }

        fun selectedVersion(): SupportedVersion = supportedByLabel[versionBox.selectedItem as String]!!

        /** Defaults per target: a real path the user recognizes, never "wherever the jar lives". */
        fun applyDefaultDir() {
            val version = selectedVersion().version
            dirField.text = when (selectedTargetId()) {
                // A Prism install goes into a concrete *instance*, not the `instances/` parent, so the
                // default names one. The parent is not a valid target — it has no `instance.cfg` — and
                // showing it reads as "this is the right folder", which is the wrong thing to believe.
                PRISM -> File(defaultPrismRoot(), "instances/$version").absolutePath
                SERVER -> File(System.getProperty("user.dir", "."), "oml-server-$version").absolutePath
                else -> StandardLauncherTarget.defaultGameDir().absolutePath
            }
        }

        fun applyDefaultId() {
            val version = selectedVersion().version
            idField.text = when (selectedTargetId()) {
                SERVER -> "server"
                else -> "$version-OML"
            }
        }

        var sharedModsAcknowledged = false
        isolationCheck.addActionListener {
            if (!isolationCheck.isSelected) {
                val choice = JOptionPane.showConfirmDialog(
                    null,
                    Messages.t("dlg.risk.message"),
                    Messages.t("dlg.risk.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                )
                if (choice == JOptionPane.YES_OPTION) {
                    sharedModsAcknowledged = true
                } else {
                    isolationCheck.isSelected = true
                }
            } else {
                sharedModsAcknowledged = false
            }
        }

        targetBox.addActionListener {
            cardsLayout.show(cardsPanel, selectedTargetId())
            applyDefaultDir()
            applyDefaultId()
        }
        versionBox.addActionListener {
            applyDefaultDir()
            applyDefaultId()
        }
        applyDefaultId()

        browseBtn.addActionListener {
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                currentDirectory = File(dirField.text.trim()).takeIf { it.isDirectory }
            }
            if (chooser.showOpenDialog(browseBtn) == JFileChooser.APPROVE_OPTION) {
                dirField.text = chooser.selectedFile.absolutePath
            }
        }

        copyLogButton.addActionListener {
            Toolkit.getDefaultToolkit().systemClipboard
                .setContents(StringSelection(log.text), null)
            log.append(Messages.t("log.copied") + "\n")
        }
        openDirButton.addActionListener {
            val dir = File(dirField.text.trim())
            runCatching { Desktop.getDesktop().open(dir) }.onFailure {
                log.append(Messages.t("log.openDirFailed", dir.absolutePath, it.message) + "\n")
            }
        }

        // ---- install ---------------------------------------------------------------------------
        installButton.addActionListener {
            val target = selectedTarget()
            val targetDir = File(dirField.text.trim())

            val proxy = proxyField.text.trim()
            if (proxy.isNotEmpty()) {
                val selector = AssetDownloader.parseProxy(proxy)
                if (selector == null) {
                    JOptionPane.showMessageDialog(
                        null, Messages.t("dlg.proxyInvalid", proxy),
                        Messages.t("app.title"), JOptionPane.ERROR_MESSAGE,
                    )
                    return@addActionListener
                }
                AssetDownloader.proxyOverride = selector
            }

            // The `snapshot` entry is an alias: resolve it (network) before anything else, so a
            // failure is an ordinary error dialog instead of a half-built install.
            val supported = try {
                resolveSnapshotAlias(selectedVersion())
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    null,
                    (e as? InstallationException)?.message ?: e.message,
                    Messages.t("app.title"), JOptionPane.ERROR_MESSAGE,
                )
                return@addActionListener
            }

            val artifacts = try {
                FatJarArtifactSource(supported.version, supported.adapterArtifact)
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    null,
                    Messages.t("dlg.resourcesFailed", e.message),
                    Messages.t("app.title"),
                    JOptionPane.ERROR_MESSAGE,
                )
                return@addActionListener
            }

            val ctx = InstallContext(
                target = supported,
                targetDir = targetDir.absoluteFile,
                installId = idField.text.trim().ifBlank { "${supported.version}-OML" },
                isolation = isolationCheck.isSelected,
                acceptEula = eulaCheck.isSelected,
                allowSharedMods = sharedModsAcknowledged,
                artifacts = artifacts,
                side = if (target === DedicatedServerTarget) "server" else "client",
                addPrismComponent = prismComponentCheck.isSelected,
                log = { line -> SwingUtilities.invokeLater { log.append("$line\n") } },
            )

            // Validate before anything is written: a refusal must leave no artifacts behind.
            val validation = target.validate(ctx)
            if (!validation.ok) {
                artifacts.close()
                JOptionPane.showMessageDialog(
                    null,
                    validation.errors.joinToString("\n\n"),
                    Messages.t("dlg.validate.title"), JOptionPane.ERROR_MESSAGE,
                )
                return@addActionListener
            }
            if (validation.warnings.isNotEmpty()) {
                val choice = JOptionPane.showConfirmDialog(
                    null,
                    validation.warnings.joinToString("\n\n") + Messages.t("dlg.warn.continue"),
                    Messages.t("dlg.warn.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE,
                )
                if (choice != JOptionPane.YES_OPTION) {
                    artifacts.close()
                    return@addActionListener
                }
            }

            installButton.isEnabled = false
            progressBar.value = 0
            progressBar.string = Messages.t("ui.preparing")

            runInstall(
                target, ctx, artifacts, log, progressBar,
                onDone = { hint, failure ->
                    installButton.isEnabled = true
                    if (failure != null) {
                        val detail = if (failure is InstallationException) {
                            failure.message
                        } else {
                            "${failure::class.java.simpleName}: ${failure.message}"
                        }
                        JOptionPane.showMessageDialog(
                            null,
                            Messages.t("dlg.fail.message", detail),
                            Messages.t("app.title"), JOptionPane.ERROR_MESSAGE,
                        )
                    } else if (hint != null) {
                        showSuccessDialog(hint, targetDir, log)
                    }
                },
            )
        }

        // ---- assembly --------------------------------------------------------------------------
        val rootPanel = JPanel(BorderLayout(0, 10)).apply {
            border = EmptyBorder(12, 14, 12, 14)
            add(formPanel, BorderLayout.NORTH)
            add(
                JScrollPane(log).apply {
                    border = BorderFactory.createTitledBorder(Messages.t("ui.log.title"))
                    preferredSize = Dimension(720, 260)
                },
                BorderLayout.CENTER,
            )

            val actionPanel = JPanel(BorderLayout(10, 0)).apply {
                add(progressBar, BorderLayout.CENTER)
                val buttons = JPanel(BorderLayout(6, 0)).apply {
                    add(openDirButton, BorderLayout.WEST)
                    add(copyLogButton, BorderLayout.CENTER)
                    add(installButton, BorderLayout.EAST)
                }
                add(buttons, BorderLayout.EAST)
            }
            add(actionPanel, BorderLayout.SOUTH)
        }

        JFrame(Messages.t("app.title")).apply {
            defaultCloseOperation = WindowConstants.EXIT_ON_CLOSE
            contentPane = rootPanel
            rootPane.defaultButton = installButton
            minimumSize = Dimension(720, 520)
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }

    private fun showSuccessDialog(hint: String, targetDir: File, log: JTextArea) {
        val options = arrayOf(
            Messages.t("dlg.success.open"),
            Messages.t("dlg.success.copy"),
            Messages.t("dlg.success.close"),
        )
        val choice = JOptionPane.showOptionDialog(
            null,
            hint + Messages.t("dlg.success.dir", targetDir.absolutePath),
            Messages.t("dlg.success.title"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
            null, options, options[2],
        )
        when (choice) {
            0 -> runCatching { Desktop.getDesktop().open(targetDir) }
                .onFailure { log.append(Messages.t("log.openDirFailed", targetDir.absolutePath, it.message) + "\n") }

            1 -> {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(log.text), null)
                log.append(Messages.t("log.copied") + "\n")
            }
        }
    }

    /**
     * Runs the install off the EDT and reports the outcome through [onDone].
     *
     * `done()` must never assume success: it is the only place that can turn a failure into something
     * the user can read, and the artifact source has to be closed on every path (it holds the installer
     * jar open).
     */
    private fun runInstall(
        target: InstallationTarget,
        ctx: InstallContext,
        artifacts: ArtifactSource,
        log: JTextArea,
        progressBar: JProgressBar,
        onDone: (hint: String?, failure: Throwable?) -> Unit,
    ) {
        val originalOut = System.out
        val originalErr = System.err
        val sink = SwingProgressSink(progressBar)

        object : SwingWorker<InstallOutcome, Unit>() {
            private var printStream: PrintStream? = null

            override fun doInBackground(): InstallOutcome {
                val stream = PrintStream(TeeOutputStream(originalOut, log), true, StandardCharsets.UTF_8)
                printStream = stream
                System.setOut(stream)
                System.setErr(stream)
                return try {
                    InstallOutcome(Installer.performInstall(ctx, target, sink), null)
                } catch (t: Throwable) {
                    InstallOutcome(null, t)
                } finally {
                    artifacts.close()
                }
            }

            override fun done() {
                System.setOut(originalOut)
                System.setErr(originalErr)
                printStream?.flush()

                val outcome = try {
                    get()
                } catch (t: Throwable) {
                    InstallOutcome(null, t)
                }
                val failure = outcome.failure
                if (failure == null) {
                    log.append("\n${Messages.t("log.prefix")} ${Messages.t("log.installed")}\n")
                    progressBar.isIndeterminate = false
                    progressBar.value = progressBar.maximum
                    progressBar.string = Messages.t("ui.progress.installed")
                } else {
                    log.append("\n${Messages.t("log.prefix")} ${Messages.t("log.failed", failure.message ?: "")}\n")
                    progressBar.isIndeterminate = false
                    progressBar.string = Messages.t("ui.progress.failed")
                }
                onDone(outcome.hint, failure)
            }
        }.execute()
    }

    /** `doInBackground` returns this; `done()` reads it back with `get()`. */
    private class InstallOutcome(val hint: String?, val failure: Throwable?)

    /** Turns installer progress into real percentages and item counts. */
    private class SwingProgressSink(private val bar: JProgressBar) : ProgressSink {

        override fun stage(label: String, total: Long) {
            SwingUtilities.invokeLater {
                bar.isIndeterminate = total <= 0L
                bar.value = 0
                bar.string = label
            }
        }

        override fun progress(done: Long, total: Long, label: String) {
            SwingUtilities.invokeLater {
                if (total > 0L) {
                    bar.isIndeterminate = false
                    bar.maximum = total.toInt().coerceAtLeast(1)
                    bar.value = done.toInt().coerceIn(0, bar.maximum)
                    bar.string = Messages.t("progress.item", label, done, total)
                } else {
                    // byte-level progress without a known total: show what we know in text form
                    bar.isIndeterminate = true
                    bar.string = if (label.isEmpty()) {
                        Messages.t("progress.bytesNoLabel", done / 1024)
                    } else {
                        Messages.t("progress.bytes", label, done / 1024)
                    }
                }
            }
        }

        override fun indeterminate(label: String) {
            SwingUtilities.invokeLater {
                bar.isIndeterminate = true
                bar.string = label
            }
        }
    }

    private fun addFormRow(panel: JPanel, gbc: GridBagConstraints, row: Int, labelText: String, comp: JComponent) {
        gbc.gridy = row

        gbc.gridx = 0
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        panel.add(JLabel(labelText), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(comp, gbc)
    }

    /** An [OutputStream] that writes to both the original stream and a Swing text area (EDT-safe). */
    private class TeeOutputStream(private val original: OutputStream, private val log: JTextArea) : OutputStream() {
        private val buffer = StringBuilder()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            original.write(b, off, len)
            buffer.append(String(b, off, len, StandardCharsets.UTF_8))
            if (buffer.contains('\n')) {
                flushBuffer()
            }
        }

        @Synchronized
        override fun flush() {
            original.flush()
            flushBuffer()
        }

        private fun flushBuffer() {
            if (buffer.isNotEmpty()) {
                val text = buffer.toString()
                buffer.setLength(0)
                SwingUtilities.invokeLater { log.append(text) }
            }
        }
    }
}
