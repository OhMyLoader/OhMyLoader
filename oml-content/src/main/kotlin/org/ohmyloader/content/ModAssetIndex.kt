package org.ohmyloader.content

import org.ohmyloader.api.OmlLog
import java.io.File
import java.util.zip.ZipFile

/**
 * Index of every file the loaded mod jars ship under `assets/` and `data/`, keyed as
 * `"namespace/relative/path"` — `assets/oml/x.json` and `data/oml/x.json` both index as
 * `oml/x.json`, re-rooted by the pack layer per pack type.
 *
 * Read from the jars, not the class loader: a pack must answer "what is in this directory", and the
 * jars are exactly the primary loader's search path, so index and byte lookup agree by construction.
 * Built once — jars do not change mid-session, while this runs inside every repository reload.
 * Namespaces are the jars' first segments plus [assetDomains], for content whose assets the adapter
 * synthesizes with no `assets/` entry shipped at all.
 */
class ModAssetIndex(modJars: List<File>, assetDomains: Collection<String>) {

    private val keys: Set<String> = scan(modJars)
    private val domains: Set<String> = assetDomains.toSet()

    /** Every indexed key, `"namespace/relative/path"`. */
    fun keys(): Set<String> = keys

    /**
     * The namespaces an injected pack answers under: everything the jars ship assets for, plus
     * every OML asset domain.
     */
    fun namespaces(): Set<String> {
        val result = HashSet<String>()
        for (key in keys) result += key.substringBefore('/')
        result += domains
        return result
    }

    /**
     * The paths (relative to [namespace], directory prefix included) indexed under
     * `<namespace>/<directory>/`. [directory] is matched as a prefix, not against a list of known
     * directories — whatever a mod shipped under the requested directory is returned, which keeps
     * sounds, lang, fonts, shaders, particles and custom atlas directories visible without this
     * class knowing they exist.
     */
    fun listUnder(namespace: String, directory: String): List<String> {
        val prefix = "$namespace/$directory/"
        return keys.filter { it.startsWith(prefix) }.map { it.removePrefix("$namespace/") }
    }

    private fun scan(modJars: List<File>): Set<String> {
        val keys = HashSet<String>()
        for (jar in modJars) {
            try {
                ZipFile(jar).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if ((name.startsWith("assets/") || name.startsWith("data/")) && !name.endsWith("/")) {
                            keys += name.removePrefix("assets/").removePrefix("data/")
                        }
                    }
                }
            } catch (t: Throwable) {
                OmlLog.error("ModAssets", "cannot index ${jar.name}", t)
            }
        }
        return keys
    }

}
