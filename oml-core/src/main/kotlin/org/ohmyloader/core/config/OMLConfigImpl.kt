package org.ohmyloader.core.config

import org.ohmyloader.api.OmlLog
import org.ohmyloader.api.config.OMLConfig
import org.ohmyloader.content.MinimalToml
import java.io.File

/**
 * The [OMLConfig] implementation over `<gameDir>/config/<modId>.toml`, parsed with the same
 * minimal TOML reader the content packs use. The file is read once at construction; a missing
 * file is generated from the declarations on the first value read, and an existing file is never
 * rewritten (user edits and comments survive). A value whose type does not match its declaration
 * is warned about and falls back to the default — a config typo must not crash the boot.
 */
class OMLConfigImpl(modId: String, configDir: File) : OMLConfig {

    private val file = File(configDir, "$modId.toml")
    private val entries = LinkedHashMap<String, Entry>()
    private var fileValues: Map<String, Any> = emptyMap()
    private var generated = false

    private data class Entry(val type: Class<*>, val default: Any, val comment: String)

    init {
        if (file.isFile) {
            fileValues = MinimalToml.parse(file.readText()).getValue("").mapValues { (_, v) -> unwrap(v) }
        }
    }

    // TOML integers arrive as Long; config entries are declared against the Kotlin types.
    private fun unwrap(v: Any): Any = if (v is Long && v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v

    override fun define(name: String, default: Int, comment: String) {
        entries[name] = Entry(Int::class.javaObjectType, default, comment)
    }

    override fun define(name: String, default: Boolean, comment: String) {
        entries[name] = Entry(Boolean::class.javaObjectType, default, comment)
    }

    override fun define(name: String, default: Double, comment: String) {
        entries[name] = Entry(Double::class.javaObjectType, default, comment)
    }

    override fun define(name: String, default: String, comment: String) {
        entries[name] = Entry(String::class.javaObjectType, default, comment)
    }

    override fun getInt(name: String): Int = read(name) as Int
    override fun getBoolean(name: String): Boolean = read(name) as Boolean
    override fun getDouble(name: String): Double = read(name) as Double
    override fun getString(name: String): String = read(name) as String

    private fun read(name: String): Any {
        val entry = entries[name] ?: error(
            "config entry '$name' was never defined by ${file.nameWithoutExtension} — define it before reading"
        )
        val value = fileValues[name]
        if (value != null && entry.type.isInstance(value)) return value
        if (value != null) {
            OmlLog.warn(
                "Config",
                "${file.name}: '$name' is ${value::class.simpleName} in the file but was declared " +
                    "${entry.type.simpleName} — using the default",
            )
        }
        return entry.default
    }

    fun generateIfMissing() {
        if (generated || file.isFile) return
        generated = true
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                entries.entries.joinToString("\n\n") { [name, entry] ->
                    buildString {
                        if (entry.comment.isNotEmpty()) append("# ${entry.comment}\n")
                        append("$name = ${literal(entry.default)}")
                    }
                } + "\n",
            )
            OmlLog.info("Config", "generated ${file.path} with the declared defaults")
        }.onFailure {
            OmlLog.error("Config", "could not generate ${file.path} — defaults stay in memory only", it)
        }
    }

    private fun literal(value: Any): String = when (value) {
        is String -> "\"$value\""
        else -> "$value"
    }
}
