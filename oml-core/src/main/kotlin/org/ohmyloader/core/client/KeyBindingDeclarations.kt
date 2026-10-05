package org.ohmyloader.core.client

/**
 * The collected key bindings of every mod, in declaration order. Populated during mod init (the
 * declarations are version-agnostic data) and applied by the version adapter lazily on the first
 * client tick — earlier there is no `Options` instance to append to.
 */
object KeyBindingDeclarations {

    data class Entry(val modId: String, val id: String, val defaultKey: String, val onPress: () -> Unit)

    val entries = mutableListOf<Entry>()
}
