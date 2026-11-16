package org.ohmyloader.adapter.common

/**
 * Version-neutral behavior of the mod resource pack. The 26.3 adapter implements the game's
 * PackResources interface directly over this bridge; the reflective fallback keeps a dynamic
 * Proxy for adapters that have not installed one.
 */
interface PackResourcesBridge {
    fun packId(): String

    fun location(): Any

    fun getNamespaces(packType: Any?): Set<String>

    fun getResource(identifier: Any): ByteArray?

    fun getMetadataSection(type: Any): Any?

    fun listResources(
        packType: Any?,
        namespace: String,
        directory: String,
        emit: (Any, ByteArray) -> Unit,
    )
}
