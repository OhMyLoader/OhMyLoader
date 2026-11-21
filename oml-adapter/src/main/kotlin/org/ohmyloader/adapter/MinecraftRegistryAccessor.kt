package org.ohmyloader.adapter

import net.minecraft.core.Registry
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.packs.PackLocationInfo
import net.minecraft.server.packs.PackMetadataResources
import net.minecraft.server.packs.PackResources
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.metadata.MetadataSectionType
import net.minecraft.server.packs.repository.Pack
import net.minecraft.server.packs.repository.RepositorySource
import net.minecraft.server.packs.resources.IoSupplier
import net.minecraft.world.entity.EquipmentSlotGroup
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import org.ohmyloader.adapter.ReflectiveRegistryAccessor
import org.ohmyloader.adapter.RegistryAccessor
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.function.Consumer
import java.util.stream.Stream

/**
 * Typed registry access against the tracked game. Every field here is a direct getstatic: reflecting on these static
 * fields through Field.get(null) creates JEP 416 method-handle metadata backed by a boot
 * LambdaForm$MH hidden class, which keeps the disposable session loader reachable across a soft
 * reboot. Keeping the accessor version-specific also keeps the shared content registry free of
 * direct game-type references.
 */
object MinecraftRegistryAccessor : RegistryAccessor {

    override fun builtinRegistry(name: String): Any = when (name) {
        "BLOCK" -> BuiltInRegistries.BLOCK
        "ITEM" -> BuiltInRegistries.ITEM
        "BLOCK_ENTITY_TYPE" -> BuiltInRegistries.BLOCK_ENTITY_TYPE
        "CREATIVE_MODE_TAB" -> BuiltInRegistries.CREATIVE_MODE_TAB
        else -> ReflectiveRegistryAccessor.builtinRegistry(name)
    }

    override fun registryKey(name: String): Any = when (name) {
        "BLOCK" -> Registries.BLOCK
        "ITEM" -> Registries.ITEM
        "CREATIVE_MODE_TAB" -> Registries.CREATIVE_MODE_TAB
        else -> ReflectiveRegistryAccessor.registryKey(name)
    }

    override fun identifier(namespace: String, path: String): Any =
        Identifier.fromNamespaceAndPath(namespace, path)

    override fun identifierNamespace(identifier: Any): String = (identifier as Identifier).namespace

    override fun identifierPath(identifier: Any): String = (identifier as Identifier).path

    override fun registryGet(registryName: String, identifier: Any): Any? = when (registryName) {
        "BLOCK" -> BuiltInRegistries.BLOCK.get(identifier as Identifier)
        "ITEM" -> BuiltInRegistries.ITEM.get(identifier as Identifier)
        "BLOCK_ENTITY_TYPE" -> BuiltInRegistries.BLOCK_ENTITY_TYPE.get(identifier as Identifier)
        "CREATIVE_MODE_TAB" -> BuiltInRegistries.CREATIVE_MODE_TAB.get(identifier as Identifier)
        else -> ReflectiveRegistryAccessor.registryGet(registryName, identifier)
    }

    override fun registryKeySet(registryName: String): Set<*> = when (registryName) {
        "BLOCK" -> BuiltInRegistries.BLOCK.keySet()
        "ITEM" -> BuiltInRegistries.ITEM.keySet()
        "BLOCK_ENTITY_TYPE" -> BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet()
        "CREATIVE_MODE_TAB" -> BuiltInRegistries.CREATIVE_MODE_TAB.keySet()
        else -> ReflectiveRegistryAccessor.registryKeySet(registryName)
    }

    override fun resourceKey(registryName: String, identifier: Any): Any = when (registryName) {
        "BLOCK" -> ResourceKey.create(Registries.BLOCK, identifier as Identifier)
        "ITEM" -> ResourceKey.create(Registries.ITEM, identifier as Identifier)
        "CREATIVE_MODE_TAB" -> ResourceKey.create(Registries.CREATIVE_MODE_TAB, identifier as Identifier)
        else -> ReflectiveRegistryAccessor.resourceKey(registryName, identifier)
    }

    override fun blockPropertiesLightLevel(properties: Any, level: Int): Any {
        (properties as BlockBehaviour.Properties).lightLevel { level }
        return properties
    }

    override fun attributeModifier(identifier: Any, amount: Double, operation: Any): Any =
        AttributeModifier(identifier as Identifier, amount, operation as AttributeModifier.Operation)

    private class OMLRepositorySource(private val pack: Pack) : RepositorySource {
        override fun loadPacks(consumer: Consumer<Pack>) {
            consumer.accept(pack)
        }
    }

    override fun repositorySource(pack: Any): Any = OMLRepositorySource(pack as Pack)

    private class OMLIoSupplier(private val bytes: ByteArray) : IoSupplier<InputStream> {
        override fun get(): InputStream = ByteArrayInputStream(bytes)
    }

    override fun ioSupplier(bytes: ByteArray): Any = OMLIoSupplier(bytes)

    private class OMLResourcesSupplier(private val resources: PackResources) : Pack.ResourcesSupplier {
        override fun openMetadata(location: PackLocationInfo): PackMetadataResources = resources

        override fun openResources(location: PackLocationInfo, metadata: Pack.Metadata): Stream<PackResources> =
            Stream.of(resources)
    }

    override fun resourcesSupplier(resources: Any): Any = OMLResourcesSupplier(resources as PackResources)

    override fun blockStateRegistry(): Any = Block.BLOCK_STATE_REGISTRY

    override fun dataComponent(name: String): Any = when (name) {
        "MAX_DAMAGE" -> DataComponents.MAX_DAMAGE
        "MAX_STACK_SIZE" -> DataComponents.MAX_STACK_SIZE
        "DAMAGE" -> DataComponents.DAMAGE
        "ATTRIBUTE_MODIFIERS" -> DataComponents.ATTRIBUTE_MODIFIERS
        "TOOL" -> DataComponents.TOOL
        else -> ReflectiveRegistryAccessor.dataComponent(name)
    }

    override fun attribute(name: String): Any = when (name) {
        "ATTACK_DAMAGE" -> Attributes.ATTACK_DAMAGE
        "ATTACK_SPEED" -> Attributes.ATTACK_SPEED
        else -> ReflectiveRegistryAccessor.attribute(name)
    }

    override fun attributeOperation(name: String): Any = when (name) {
        "ADD_VALUE" -> AttributeModifier.Operation.ADD_VALUE
        else -> ReflectiveRegistryAccessor.attributeOperation(name)
    }

    override fun equipmentSlotGroup(name: String): Any = when (name) {
        "MAINHAND" -> EquipmentSlotGroup.MAINHAND
        else -> ReflectiveRegistryAccessor.equipmentSlotGroup(name)
    }

    @Suppress("UNCHECKED_CAST")
    override fun register(registryName: String, identifier: Any, value: Any) {
        val registry = when (registryName) {
            "BLOCK" -> BuiltInRegistries.BLOCK
            "ITEM" -> BuiltInRegistries.ITEM
            "BLOCK_ENTITY_TYPE" -> BuiltInRegistries.BLOCK_ENTITY_TYPE
            "CREATIVE_MODE_TAB" -> BuiltInRegistries.CREATIVE_MODE_TAB
            else -> {
                ReflectiveRegistryAccessor.register(registryName, identifier, value)
                return
            }
        } as Registry<Any>
        when (identifier) {
            is Identifier -> Registry.register(registry, identifier, value)
            is ResourceKey<*> -> Registry.register(registry, identifier as ResourceKey<Any>, value)
            else -> ReflectiveRegistryAccessor.register(registryName, identifier, value)
        }
    }
}
