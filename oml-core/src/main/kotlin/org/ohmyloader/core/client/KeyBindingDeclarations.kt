package org.ohmyloader.core.client

/**
 * The collected key bindings of every mod, in declaration order. Populated during mod init (the
 * declarations are version-agnostic data) and applied by the version adapter lazily on the first
 * client tick — earlier there is no `Options` instance to append to.
 */
object KeyBindingDeclarations {

    data class Entry(val modId: String, val id: String, val defaultKey: String, val onPress: () -> Unit)

    /**
     * Thread contract: written only during mod initialization, read-only afterwards (the adapter
     * applies it lazily on the first client tick). No reader may mutate it.
     */
    val entries = mutableListOf<Entry>()
}
