package org.ohmyloader.content

/**
 * A deliberately minimal TOML parser for flat content packs. Supports exactly the subset a data-only
 * content pack needs and rejects everything else with a line-numbered error rather than silently
 * misreading it — every supported feature is a promise to keep parsing forever.
 * Supported: `[table.headers]` (no array-of-tables), bare and dotted keys, integers/floats/booleans,
 * basic and literal strings (escapes in basic), single-line arrays and inline tables (recursive, so
 * arrays of inline tables work), `#` comments, numeric underscores, trailing commas. Explicit parse
 * errors: multi-line values, dates/times, hex/octal/binary integers, quoted keys, nested dotted keys inside
 * inline tables — if the pack format ever needs them, the feature is added deliberately, not by accident.
 */
object MinimalToml {

    /** Parse failure carrying the 1-based line number, for pack authors to find their typo. */
    class TomlParseException(message: String, val line: Int) :
        RuntimeException("TOML parse error at line $line: $message")

    /**
     * Parses [text] into `section path -> (key -> value)`, in declaration order. The root table
     * (keys before any header) is keyed by the empty string. Values map to [Long], [Double],
     * [Boolean], [String], [List]<Any> or [Map]<String, Any>.
     */
    fun parse(text: String): Map<String, MutableMap<String, Any>> {
        val sections = LinkedHashMap<String, MutableMap<String, Any>>()
        sections[""] = LinkedHashMap()
        var current = ""
        text.lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed

            if (line.startsWith("[")) {
                if (!line.endsWith("]")) throw TomlParseException("table header must end with ']'", lineNo)
                val header = line.removeSurrounding("[", "]").trim()
                if (header.isEmpty()) throw TomlParseException("empty table header", lineNo)
                if (header.startsWith("[")) throw TomlParseException("[[array of tables]] is not supported", lineNo)
                for (part in header.split('.')) {
                    if (part.isEmpty() || !part.isBareKey()) {
                        throw TomlParseException(
                            "unsupported table key component '$part' (quoted keys are not supported)",
                            lineNo,
                        )
                    }
                }
                current = header
                sections.getOrPut(current) { LinkedHashMap() }
                return@forEachIndexed
            }

            // key = value
            val eq = line.indexOf('=')
            if (eq <= 0) throw TomlParseException("expected 'key = value'", lineNo)
            val key = line.substring(0, eq).trim()
            for (part in key.split('.')) {
                if (part.isEmpty() || !part.isBareKey()) {
                    throw TomlParseException("unsupported key '$key' (quoted keys are not supported)", lineNo)
                }
            }
            val cursor = Cursor(line, eq + 1, lineNo)
            val value = cursor.readValue()
            cursor.finish()
            sections.getValue(current)[key] = value
        }
        return sections
    }

    private fun String.isBareKey(): Boolean = all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-'
    }

    /** Single-line value scanner: positioned after the `=` and advanced by [readValue]. */
    private class Cursor(private val line: String, private var pos: Int, private val lineNo: Int) {

        fun finish() {
            skipWhitespace()
            if (pos < line.length && line[pos] != '#') fail("unexpected trailing content '${line.substring(pos)}'")
        }

        fun readValue(): Any {
            skipWhitespace()
            if (pos >= line.length) fail("missing value")
            return when (line[pos]) {
                '"' -> readBasicString()
                '\'' -> readLiteralString()
                '[' -> readArray()
                '{' -> readInlineTable()
                else -> readBare()
            }
        }

        private fun skipWhitespace() {
            while (pos < line.length && line[pos].isWhitespace()) pos++
        }

        private fun fail(message: String): Nothing = throw TomlParseException(message, lineNo)

        private fun readBasicString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= line.length) fail("unterminated string")
                when (val c = line[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= line.length) fail("unterminated escape")
                        when (val e = line[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > line.length) fail("invalid \\u escape")
                                val hex = line.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("invalid \\u escape '$hex'")
                                sb.append(code.toChar())
                                pos += 4
                            }

                            else -> fail("unsupported escape '\\$e'")
                        }
                    }

                    else -> sb.append(c)
                }
            }
        }

        private fun readLiteralString(): String {
            pos++
            val end = line.indexOf('\'', pos)
            if (end < 0) fail("unterminated literal string")
            val value = line.substring(pos, end)
            pos = end + 1
            return value
        }

        private fun readArray(): List<Any> {
            pos++ // [
            val out = mutableListOf<Any>()
            while (true) {
                skipWhitespace()
                if (pos >= line.length) fail("unterminated array (this parser reads single-line values)")
                if (line[pos] == ']') {
                    pos++
                    return out
                }
                out += readValue()
                skipWhitespace()
                when {
                    pos >= line.length -> fail("unterminated array (this parser reads single-line values)")
                    line[pos] == ',' -> pos++ // trailing comma allowed, TOML-style
                    line[pos] == ']' -> {}
                    else -> fail("expected ',' or ']' in array")
                }
            }
        }

        private fun readInlineTable(): Map<String, Any> {
            pos++ // {
            val out = LinkedHashMap<String, Any>()
            while (true) {
                skipWhitespace()
                if (pos >= line.length) fail("unterminated inline table")
                if (line[pos] == '}') {
                    pos++
                    return out
                }
                val start = pos
                while (pos < line.length && (line[pos].isLetterOrDigit() || line[pos] == '_' || line[pos] == '-')) pos++
                val key = line.substring(start, pos)
                if (key.isEmpty()) fail("expected a key in inline table")
                skipWhitespace()
                if (pos >= line.length || line[pos] != '=') fail("expected '=' after inline table key '$key'")
                pos++
                out[key] = readValue()
                skipWhitespace()
                when {
                    pos >= line.length -> fail("unterminated inline table")
                    line[pos] == ',' -> pos++
                    line[pos] == '}' -> {}
                    else -> fail("expected ',' or '}' in inline table")
                }
            }
        }

        private fun readBare(): Any {
            val start = pos
            while (pos < line.length && !line[pos].isWhitespace() && line[pos] !in ",]}#") pos++
            val token = line.substring(start, pos).trim()
            if (token.isEmpty()) fail("missing value")
            return when (token) {
                "true" -> true
                "false" -> false
                "inf", "+inf" -> Double.POSITIVE_INFINITY
                "-inf" -> Double.NEGATIVE_INFINITY
                else -> token.toNumber()
            }
        }

        private fun String.toNumber(): Any {
            val cleaned = replace("_", "")
            cleaned.toLongOrNull()?.let { return it }
            cleaned.toDoubleOrNull()?.let { return it } // covers "3.0", "-2.4", "1e3"
            fail("unsupported value '$this' (numbers, booleans and strings only)")
        }
    }
}
