package org.ohmyloader.api.config

/**
 * The mod's config file, declaratively defined. Entries are declared with defaults during mod
 * init ([define]); a value the file already carries wins over the default, and a missing file is
 * generated from the declarations once `onInitialize` returns. Backed by
 * `<gameDir>/config/<mod id>.toml`; an existing file is never rewritten, so user edits survive.
 */
interface OMLConfig {
    fun define(name: String, default: Int, comment: String = "")
    fun define(name: String, default: Boolean, comment: String = "")
    fun define(name: String, default: Double, comment: String = "")
    fun define(name: String, default: String, comment: String = "")

    /** The file value, or the declared default when absent. Reading an undefined name throws. */
    fun getInt(name: String): Int
    fun getBoolean(name: String): Boolean
    fun getDouble(name: String): Double
    fun getString(name: String): String
}
