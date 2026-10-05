package org.ohmyloader.installer

import java.io.File
import java.nio.file.Files
import java.util.*
import kotlin.test.*

/**
 * Guards the translation files.
 *
 * This is the part of i18n that actually rots: nobody notices a key that exists in one language but not
 * the other until a user's dialog comes up in English on a Chinese machine (or, worse, shows a raw key).
 * The code cannot check itself at compile time, so the bundles are checked against each other here.
 */
class MessagesTest {

    // Locale.ROOT, not Locale.ENGLISH: the base bundle is what we ship as English, and asking for `en`
    // resolves through the *default locale* — on a Chinese machine that is the Chinese bundle, which made
    // the first version of this test compare Chinese against Chinese and pass.
    private val english = Messages.stringsOf(Locale.ROOT)
    private val chinese = Messages.stringsOf(Locale.SIMPLIFIED_CHINESE)

    @Test
    fun `the two bundles really are two languages`() {
        // Cheap insurance against the trap above: if both lookups ever return the same bundle, every other
        // test in this class becomes vacuous, so fail loudly here instead.
        assertEquals("OhMyLoader Installer", english["app.title"])
        assertEquals("OhMyLoader 安装器", chinese["app.title"])
        assertTrue(english.values.none { it.contains("安装") }, "the base bundle must be English")
    }

    @Test
    fun `a non-Chinese request resolves to the English base bundle`() {
        assertEquals(Locale.ROOT, Messages.bundleLocaleFor(Locale.ENGLISH))
        assertEquals(Locale.ROOT, Messages.bundleLocaleFor(Locale.GERMAN))
        assertEquals(Locale.ROOT, Messages.bundleLocaleFor(Locale.forLanguageTag("ja-JP")))
        assertEquals(Locale.SIMPLIFIED_CHINESE, Messages.bundleLocaleFor(Locale.SIMPLIFIED_CHINESE))
        assertEquals(Locale.SIMPLIFIED_CHINESE, Messages.bundleLocaleFor(Locale.CHINESE))
        assertEquals(Locale.SIMPLIFIED_CHINESE, Messages.bundleLocaleFor(Locale.forLanguageTag("zh-TW")))
    }

    @Test
    fun `both bundles declare exactly the same keys`() {
        val missingInChinese = english.keys - chinese.keys
        val missingInEnglish = chinese.keys - english.keys
        assertEquals(emptySet(), missingInChinese, "keys missing from messages_zh.properties")
        assertEquals(emptySet(), missingInEnglish, "keys missing from messages.properties (the fallback)")
    }

    @Test
    fun `no translation is empty`() {
        (english + chinese).forEach { (key, value) ->
            assertTrue(value.isNotBlank(), "$key is blank")
        }
    }

    @Test
    fun `a message that needs two values substitutes both`() {
        // hint.server named the directory and the Java requirement — and used `{0}` for both, so the
        // Java requirement was printed as the directory name ("Java server or newer"). Only a real
        // install's output showed it; two distinguishable arguments make the mistake a test failure.
        val text = Messages.t("hint.server", "server-dir", 27)
        assertContains(text, "server-dir")
        assertContains(text, "27")
        assertFalse(text.contains("{0}") || text.contains("{1}"), "placeholder left unsubstituted: $text")
    }

    @Test
    fun `placeholders line up between the two languages`() {
        val placeholder = Regex("""\{(\d+)}""")
        english.forEach { [key, en] ->
            val zh = chinese[key] ?: return@forEach
            val enSlots = placeholder.findAll(en).map { it.groupValues[1] }.toSortedSet()
            val zhSlots = placeholder.findAll(zh).map { it.groupValues[1] }.toSortedSet()
            assertEquals(enSlots, zhSlots, "$key uses different placeholders in the two languages")
        }
    }

    @Test
    fun `every key the code shows to a user resolves`() {
        // A deliberately broad sweep: a typo in a key shows up as the raw key on screen, so anything a
        // user can see must resolve in the active locale.
        val critical = listOf(
            "app.title", "ui.browse", "ui.install", "ui.copyLog", "ui.openDir", "ui.ready", "ui.preparing",
            "ui.form.title", "ui.log.title", "ui.label.target", "ui.label.version", "ui.label.dir",
            "ui.label.id", "ui.label.isolation", "ui.label.prismComponent", "ui.label.eula", "ui.label.proxy",
            "ui.versionItem", "ui.isolation.check", "ui.prism.check", "ui.eula.check", "ui.progress.installed",
            "ui.progress.failed", "log.prefix", "log.copied", "log.installed", "log.failed",
            "dlg.catalogFailed", "dlg.noVersions", "dlg.proxyInvalid", "dlg.resourcesFailed",
            "dlg.validate.title", "dlg.warn.title", "dlg.warn.continue", "dlg.risk.title", "dlg.risk.message",
            "dlg.fail.message", "dlg.success.title", "dlg.success.dir", "dlg.success.open", "dlg.success.close",
            "progress.item", "progress.bytes", "progress.bytesNoLabel", "progress.consolePrefix",
            "progress.console", "target.standard", "target.prism", "target.server",
            "stage.installLayer", "stage.writeVersionJson",
            "stage.prepareServer", "stage.downloadGame", "stage.downloadLibraries",
            "stage.writeConfig", "stage.writeScripts", "stage.prismPatch", "label.layerFiles",
            "validate.gameDir", "validate.instanceDir", "validate.serverDir", "validate.dirMissing",
            "validate.dirNotDir", "validate.dirNotWritable", "validate.mcLooksOdd", "validate.sharedMods",
            "validate.prismNotInstance", "validate.prismNoGameDir",
            "validate.serverParentNotWritable", "validate.serverEula", "installId.empty", "installId.chars",
            "installId.dots", "hint.standard", "hint.standard.eula", "hint.prism", "hint.prism.registered",
            "hint.server", "err.download", "err.writeZeroBytes", "err.fatjar.noLayer",
            "err.fatjar.noShell", "err.fatjar.noJar", "err.dir.noLayerJars",
            "err.dir.layerMissing", "err.dir.noShell", "err.dir.shellMissing",
            "err.catalog.missing", "err.catalog.unparsable", "err.catalog.empty", "err.versionNotBundled",
            "cli.banner.target", "cli.banner.dir", "cli.banner.id", "cli.unknownArg", "cli.missingValue",
            "cli.missingRequired", "cli.sideValue", "cli.targetUnknown", "cli.isolationRequired",
            "cli.isolationValue", "cli.proxyInvalid",
            "cli.failed", "cli.failedUnexpected", "cli.eula", "cli.help", "cli.warning",
            "script.header", "script.javaMissing", "script.javaTooOld", "script.javaDownload", "script.memory",
            "log.chmodFailed", "log.versionJsonWritten", "log.librariesInstalled",
            "log.eulaWritten", "log.eulaSkipped", "log.gameJarMissing", "log.bundlerClientJar", "log.shellJar",
            "log.layerJar", "log.launchProperties", "log.scripts", "log.prismPatch", "log.prismRegistered",
            "log.prismAlreadyRegistered", "log.prismNoPack", "log.prismNoComponents", "log.openDirFailed",
        )
        val unresolved = critical.filter { Messages.t(it) == it }
        assertEquals(emptyList(), unresolved, "these keys do not resolve in ${Messages.locale}")
    }

    @Test
    fun `start scripts are localized and still valid shell`() {
        val dir = Files.createTempDirectory("oml-scripts").toFile()

        writeServerScripts(dir, javaMajor = 27, log = {})

        val sh = File(dir, "run.sh").readText()
        val bat = File(dir, "run.bat").readText()

        // localized prose
        assertContains(sh, Messages.t("script.header"))
        assertContains(sh, Messages.t("script.javaMissing", 27))
        assertContains(sh, Messages.t("script.javaTooOld", "JAVA_MAJOR", 27).replace("JAVA_MAJOR", $$"$JAVA_MAJOR"))
        assertContains(bat, Messages.t("script.header"))

        // shell semantics that must survive translation
        assertContains(
            sh,
            """exec java -XX:MaxRAMPercentage=75.0 -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -jar oml-launcher.jar "$@"""",
        )
        assertTrue(sh.startsWith("#!/usr/bin/env sh"), "run.sh needs a shebang")
        assertContains(bat, "chcp 65001")
        assertContains(bat, "%%g", message = "a batch for-loop variable must stay escaped as %%g")
        assertContains(
            bat,
            """java -XX:MaxRAMPercentage=75.0 -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError -XX:+UseStringDeduplication -jar oml-launcher.jar %*""",
        )

        // the batch version check must compare against the required major, and neither script may keep an
        // unsubstituted placeholder
        assertContains(bat, "LSS 27")
        assertFalse(sh.contains("{0}"), "run.sh kept an unsubstituted placeholder")
        assertFalse(bat.contains("{0}"), "run.bat kept an unsubstituted placeholder")

        // CRLF throughout. cmd.exe advances a batch file line by seeking CRLF, so on an LF-only file
        // its byte position drifts once a line holds multi-byte characters — and every prose line here
        // is localized. It then eats the head of the following line, which is how a zh_CN install
        // turned `for /f ... ('java -version ...')` into a command named `-version`. run.sh must stay
        // LF: sh wants it, and cmd never reads it.
        assertTrue(bat.contains("\r\n"), "run.bat must use CRLF line endings")
        assertFalse(bat.contains(Regex("(?<!\r)\n")), "every run.bat line break must be CRLF, not LF")
    }
}
