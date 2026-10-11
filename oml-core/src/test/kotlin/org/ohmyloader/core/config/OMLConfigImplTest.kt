package org.ohmyloader.core.config

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The read/generate contract of the declarative config file (`<gameDir>/config/<mod>.toml`). */
class OMLConfigImplTest {

    private fun tempConfigDir(): File = createTempDirectory("oml-config").toFile()

    @Test
    fun `a missing file is generated with the declared defaults on first finalize`() {
        val dir = tempConfigDir()
        val config = OMLConfigImpl("mymod", dir)
        config.define("speed", 10, "how fast")
        config.define("label", "hello")

        config.generateIfMissing()

        val file = File(dir, "mymod.toml")
        assertTrue(file.isFile, "the config file must be generated")
        val text = file.readText()
        assertTrue("# how fast" in text && "speed = 10" in text, "comments and defaults must be emitted")
        assertEquals(10, config.getInt("speed"))
        assertEquals("hello", config.getString("label"))
    }

    @Test
    fun `file values win over the declared defaults`() {
        val dir = tempConfigDir()
        File(dir, "mymod.toml").writeText("speed = 42\nlabel = \"edited\"\n")
        val config = OMLConfigImpl("mymod", dir)
        config.define("speed", 10, "")
        config.define("label", "hello", "")

        assertEquals(42, config.getInt("speed"))
        assertEquals("edited", config.getString("label"))
    }

    @Test
    fun `a type mismatch in the file falls back to the default`() {
        val dir = tempConfigDir()
        File(dir, "mymod.toml").writeText("speed = \"forty-two\"\n")
        val config = OMLConfigImpl("mymod", dir)
        config.define("speed", 10, "")

        assertEquals(10, config.getInt("speed"), "the string value must not reach an int entry")
    }

    @Test
    fun `reading an entry that was never defined throws`() {
        val config = OMLConfigImpl("mymod", tempConfigDir())

        assertFailsWith<IllegalStateException> { config.getInt("ghost") }
    }

    @Test
    fun `toml integers parse as long and still reach int entries`() {
        val dir = tempConfigDir()
        File(dir, "mymod.toml").writeText("speed = 42\n")
        val config = OMLConfigImpl("mymod", dir)
        config.define("speed", 10, "")

        assertEquals(42, config.getInt("speed"))
    }

    @Test
    fun `string defaults with quotes, backslashes and windows paths round-trip through the file`() {
        // Without escaping, the quote terminates the basic string and the backslash reinterprets what
        // follows; the generated file would be unreadable (or read back a different value)
        val dir = tempConfigDir()
        val config = OMLConfigImpl("mymod", dir)
        config.define("quoted", "say \"hi\"", "")
        config.define("path", "C:\\Users\\fuban\\game\\mods", "")
        config.define("multiline", "line1\nline2", "")
        config.generateIfMissing()

        // A fresh instance reads the generated file through the same minimal TOML reader
        val reread = OMLConfigImpl("mymod", dir)
        reread.define("quoted", "untouched", "")
        reread.define("path", "untouched", "")
        reread.define("multiline", "untouched", "")

        assertEquals("say \"hi\"", reread.getString("quoted"))
        assertEquals("C:\\Users\\fuban\\game\\mods", reread.getString("path"))
        assertEquals("line1\nline2", reread.getString("multiline"))
    }
}
