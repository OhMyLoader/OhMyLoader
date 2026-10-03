package org.ohmyloader.content

import java.io.File
import java.util.zip.ZipFile

/**
 * Index of every file the loaded mod jars ship under `assets/` and `data/`, keyed as
 * `"namespace/relative/path"` (the root prefix stripped, so `assets/oml/x.json` and
 * `data/oml/x.json` both index as `oml/x.json` — the pack layer re-roots them per pack type).
 *
 * Read from the jars rather than the class loader: the loader can only answer "does this file
 * exist" and a pack must also answer "what is in this directory". The jars are also exactly what
 * sits on the primary loader's search path, so the index and the byte lookup agree by
 * construction. Built once — a mod's jars do not change during a session, and this path runs
 * inside the resource reload of every repository reload.
 *
 * Namespaces are the jars' first path segments plus [assetDomains] (mod ids and content
 * pack namespaces): a mod or pack can register blocks/items and let the version's asset pack
 * synthesize their assets while shipping no `assets/` entry at all, so the domain ids must be
 * reportable as namespaces even with no indexed file behind them.
 *
 * This is the version-independent half of asset injection; the version adapter owns the pack
 * wiring (which pack object serves it, which asset shapes it synthesizes on top).
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
                println("[ModAssets] cannot index ${jar.name}: $t")
            }
        }
        return keys
    }

}
