package org.ohmyloader.core.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The parser contract of the flat content pack format: what it reads, and what it refuses to guess. */
class MinimalTomlTest {

    private fun parse(text: String) = MinimalToml.parse(text)

    @Test
    fun `sections and basic scalar values`() {
        val doc = parse(
            """
            [block.ruby_ore]
            destroy_time = 3.0
            explosion_resistance = 6.0
            requires_correct_tool = true

            [item.ruby_sword]
            max_damage = 800
            name = "Ruby Sword"
            """.trimIndent(),
        )
        assertEquals(
            mapOf("destroy_time" to 3.0, "explosion_resistance" to 6.0, "requires_correct_tool" to true),
            doc["block.ruby_ore"] as Map<String, Any>,
        )
        assertEquals(mapOf("max_damage" to 800L, "name" to "Ruby Sword"), doc["item.ruby_sword"] as Map<String, Any>)
    }

    @Test
    fun `integers floats and underscores`() {
        val doc = parse("a = 1_000\nb = -2.4\nc = 1e3\nd = 0.5\ne = +7")
        val root = doc.getValue("")
        assertEquals(1000L, root["a"] as Long)
        assertEquals(-2.4, root["b"] as Double)
        assertEquals(1000.0, root["c"] as Double)
        assertEquals(0.5, root["d"] as Double)
        assertEquals(7L, root["e"] as Long)
    }

    @Test
    fun `strings escapes literal and comments`() {
        val root = parse(
            """
            # full-line comment
            a = "line\nbreak and \u00e9"  # trailing comment
            b = 'no \escapes'
            """.trimIndent(),
        ).getValue("")
        assertEquals("line\nbreak and é", root["a"])
        assertEquals("no \\escapes", root["b"])
    }

    @Test
    fun `array of inline tables with trailing comma`() {
        val root =
            parse("""mines_and_drops = [ { block = "minecraft:stone", speed = 8.0 }, { block = "x:y", speed = 2 }, ]""").getValue(
                ""
            )
        val list = root["mines_and_drops"] as List<*>
        assertEquals(2, list.size)
        assertEquals(mapOf("block" to "minecraft:stone", "speed" to 8.0), list[0])
        assertEquals(mapOf("block" to "x:y", "speed" to 2L), list[1])
    }

    @Test
    fun `empty section still exists`() {
        val doc = parse("[block.empty_block]\n")
        assertTrue(doc.containsKey("block.empty_block"))
        assertTrue(doc.getValue("block.empty_block").isEmpty())
    }

    @Test
    fun `root level keys land in the root table`() {
        val doc = parse("top = 1\n\n[block.x]\ny = 2")
        assertEquals(1L, doc.getValue("")["top"])
        assertEquals(2L, doc.getValue("block.x")["y"])
    }

    @Test
    fun `rejections carry the line number`() {
        val cases = mapOf(
            "a = \"unterminated" to "unterminated string",
            "a = [ { b = 1 }" to "unterminated array",
            "[[array.of.tables]]" to "array of tables",
            "a.\"quoted key\" = 1" to "quoted keys",
            "[header" to "must end with ']'",
            "a =" to "missing value",
            "a = 1 stray" to "trailing content",
            "a = tru" to "unsupported value",
            "= 5" to "expected 'key = value'",
        )
        for ([text, expectedFragment] in cases) {
            val e = assertFailsWith<MinimalToml.TomlParseException>("for input: $text") { parse(text) }
            assertTrue(e.message!!.contains(expectedFragment), "'$text' → ${e.message}")
            assertTrue(e.message!!.contains("line "), "'$text' must report a line: ${e.message}")
        }
    }

    @Test
    fun `line numbers are 1-based and accurate`() {
        val e = assertFailsWith<MinimalToml.TomlParseException> {
            parse("# comment\n\n[block.x]\na = \"oops")
        }
        assertEquals(4, e.line)
    }
}
