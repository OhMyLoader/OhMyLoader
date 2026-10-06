package org.ohmyloader.installer

import java.util.*

/**
 * Installer translations.
 *
 * Standard [ResourceBundle] resolution: `messages.properties` is the fallback bundle — **English**,
 * so an unshipped locale still reads English; `messages_zh.properties` covers every Chinese locale
 * (Java walks `zh_CN` → `zh` → default). Force a language with `-Doml.installer.lang=en|zh`.
 *
 * Placeholders `{0}`, `{1}` … are substituted by [t], not by MessageFormat — MessageFormat treats a
 * single quote as an escape, so an apostrophe would silently lose text. A missing key returns the
 * key and logs: a GUI is the wrong place to throw, and "" would show a blank dialog. The files are
 * UTF-8 (JEP 226); the Java 8 bootstrap never touches this class.
 */
object Messages {

    private const val BUNDLE = "messages"

    /** Resolved locale, after the `-Doml.installer.lang` override. */
    val locale: Locale = bundleLocaleFor(requestedLocale())

    private val bundle: ResourceBundle =
        ResourceBundle.getBundle(BUNDLE, locale, Messages::class.java.classLoader)

    /**
     * Which of the bundles we ship should answer for [requested].
     *
     * Deliberately **not** the raw locale: handing `en` (or `de`, or `ja`) to [ResourceBundle] when no
     * `messages_en.properties` exists does not fall back to the base bundle — it falls back to the
     * **default locale's** bundle, which on a Chinese machine is the Chinese one. Pinning "not Chinese
     * → English" here makes it a property of this function instead of a surprise of the JDK; publish a
     * third language by resolving to its real locale here.
     */
    internal fun bundleLocaleFor(requested: Locale): Locale =
        if (requested.language == "zh") Locale.SIMPLIFIED_CHINESE else Locale.ROOT

    private fun requestedLocale(): Locale {
        val forced = System.getProperty("oml.installer.lang")?.trim()?.lowercase().orEmpty()
        return if (forced.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(forced)
    }

    private val placeholder = Regex("""\{(\d+)}""")

    /** Looks [key] up and substitutes `{n}` placeholders. */
    fun t(key: String, vararg args: Any?): String {
        val raw = try {
            bundle.getString(key)
        } catch (_: MissingResourceException) {
            System.err.println("[i18n] missing translation: $key ($locale)")
            return key
        }
        if (args.isEmpty()) return raw
        return placeholder.replace(raw) { match ->
            val index = match.groupValues[1].toInt()
            args.getOrNull(index)?.toString() ?: match.value
        }
    }

    /** Every key of a locale's bundle, with its text. Used by the bundle-parity test. */
    fun stringsOf(locale: Locale): Map<String, String> {
        val b = ResourceBundle.getBundle(BUNDLE, locale, Messages::class.java.classLoader)
        return b.keySet().associateWith { b.getString(it) }
    }
}
