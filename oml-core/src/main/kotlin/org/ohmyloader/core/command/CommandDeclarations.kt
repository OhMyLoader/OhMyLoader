package org.ohmyloader.core.command

import org.ohmyloader.api.command.OMLCommandDeclaration

/**
 * The collected command trees of every mod, in declaration order. Populated during mod init (the
 * declarations are version-agnostic data) and translated into the game's dispatcher by the version
 * adapter each time the game rebuilds its commands — the dispatcher is constructed per resource
 * load, so the translation re-runs for every reload and needs no re-collection.
 */
object CommandDeclarations {

    data class Entry(val modId: String, val root: OMLCommandDeclaration)

    val entries = mutableListOf<Entry>()
}
