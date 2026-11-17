package org.ohmyloader.adapter

import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.metadata.MetadataSectionType
import net.minecraft.server.packs.resources.IoSupplier
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The version-neutral view of the mod resource pack: the injector fills this in from the asset
 * index, and [GameModResourcePack] is the one game-facing wrapper. Before the module split this was
 * a bridge plus a reflective `Proxy` fallback; with a single adapter the wrapper can simply
 * implement the game interface.
 */
interface ModResourcePack {
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

/** The game's own `PackResources` over a [ModResourcePack] — no proxy, no reflection. */
internal class GameModResourcePack(private val bridge: ModResourcePack) : PackResources {

    override fun getResource(type: PackType, id: Identifier): IoSupplier<InputStream>? =
        bridge.getResource(id)?.let { BytesSupplier(it) }

    override fun listResources(
        type: PackType,
        namespace: String,
        path: String,
        output: PackResources.ResourceOutput,
    ) {
        bridge.listResources(type, namespace, path) { id, bytes ->
            output.accept(id as Identifier, BytesSupplier(bytes))
        }
    }

    override fun getNamespaces(type: PackType): Set<String> = bridge.getNamespaces(type)

    override fun location(): PackLocationInfo = bridge.location() as PackLocationInfo

    override fun getRootResource(vararg path: String): IoSupplier<InputStream>? = null

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> getMetadataSection(type: MetadataSectionType<T>): T? =
        bridge.getMetadataSection(type) as T?

    override fun close() {}

    override fun packId(): String = bridge.packId()

    private class BytesSupplier(private val bytes: ByteArray) : IoSupplier<InputStream> {
        override fun get(): InputStream = ByteArrayInputStream(bytes)
    }
}
