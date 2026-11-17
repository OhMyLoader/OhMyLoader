package org.ohmyloader.adapter

import org.ohmyloader.core.OMLCore

/**
 * Version-typed access to the game's static registries and registry keys.
 *
 * <p>Reflecting on a static field through {@code Field.get(null)} makes JDK 27 (JEP 416) create a
 * {@code MethodHandleObjectFieldAccessorImpl} whose getter is a {@code DirectMethodHandle$StaticAccessor}.
 * That accessor is backed by a boot-generated {@code LambdaForm$MH} hidden class and JVM
 * {@code MemberName}/{@code ResolvedMethodName} metadata; when the field's type belongs to the
 * disposable session, that metadata chain keeps the old class loader reachable across a soft
 * reboot. The version adapter therefore resolves these fields with direct bytecode instead.
 */
interface RegistryAccessor {

    /** {@code BuiltInRegistries.<name>}. */
    fun builtinRegistry(name: String): Any

    /** {@code Registries.<name>} resource key. */
    fun registryKey(name: String): Any

    /** {@code Identifier.fromNamespaceAndPath(namespace, path)}. */
    fun identifier(namespace: String, path: String): Any

    fun identifierNamespace(identifier: Any): String

    fun identifierPath(identifier: Any): String

    /** {@code Registry.get(Identifier)} on a built-in registry. */
    fun registryGet(registryName: String, identifier: Any): Any?

    /** {@code Registry.keySet()} on a built-in registry. */
    fun registryKeySet(registryName: String): Set<*>

    /** {@code ResourceKey.create(<registry key>, identifier)}. */
    fun resourceKey(registryName: String, identifier: Any): Any

    /** {@code BlockBehaviour.Properties.lightLevel(ToIntFunction<BlockState>)}. */
    fun blockPropertiesLightLevel(properties: Any, level: Int): Any

    /** {@code new AttributeModifier(identifier, amount, operation)}. */
    fun attributeModifier(identifier: Any, amount: Double, operation: Any): Any

    /** A {@code RepositorySource} that contributes one pack. */
    fun repositorySource(pack: Any): Any

    /** An {@code IoSupplier<InputStream>} over bytes. */
    fun ioSupplier(bytes: ByteArray): Any

    /** The metadata/resources supplier of the one-pack mod repository. */
    fun resourcesSupplier(resources: Any): Any

    /** {@code Block.BLOCK_STATE_REGISTRY}. */
    fun blockStateRegistry(): Any

    /** {@code DataComponents.<name>}. */
    fun dataComponent(name: String): Any

    /** {@code Attributes.<name>}. */
    fun attribute(name: String): Any

    /** {@code AttributeModifier.Operation.<name>}. */
    fun attributeOperation(name: String): Any

    /** {@code EquipmentSlotGroup.<name>}. */
    fun equipmentSlotGroup(name: String): Any

    /** {@code Registry.register(registry, identifier, value)}. */
    fun register(registryName: String, identifier: Any, value: Any)
}

/**
 * Fallback for adapters that do not install a typed accessor. The 26.3 adapter installs
 * [org.ohmyloader.adapter.MinecraftRegistryAccessor], which avoids reflection entirely.
 */
object ReflectiveRegistryAccessor : RegistryAccessor {

    override fun builtinRegistry(name: String): Any = field(
        "net.minecraft.core.registries.BuiltInRegistries",
        name,
    )

    override fun registryKey(name: String): Any = field(
        "net.minecraft.core.registries.Registries",
        name,
    )

    override fun identifier(namespace: String, path: String): Any {
        val loader = OMLCore.gameClassLoader()
        val type = Class.forName("net.minecraft.resources.Identifier", true, loader)
        return type.getMethod("fromNamespaceAndPath", String::class.java, String::class.java)
            .invoke(null, namespace, path)
    }

    override fun identifierNamespace(identifier: Any): String =
        identifier.javaClass.getMethod("getNamespace").invoke(identifier) as String

    override fun identifierPath(identifier: Any): String =
        identifier.javaClass.getMethod("getPath").invoke(identifier) as String

    override fun registryGet(registryName: String, identifier: Any): Any? {
        val registry = builtinRegistry(registryName)
        return registry.javaClass.methods
            .first {
                it.name == "get" && it.parameterCount == 1 &&
                    it.parameterTypes[0] == identifier.javaClass
            }
            .invoke(registry, identifier)
    }

    override fun registryKeySet(registryName: String): Set<*> {
        val registry = builtinRegistry(registryName)
        return registry.javaClass.getMethod("keySet").invoke(registry) as Set<*>
    }

    override fun resourceKey(registryName: String, identifier: Any): Any {
        val loader = OMLCore.gameClassLoader()
        val keyType = Class.forName("net.minecraft.resources.ResourceKey", true, loader)
        val idType = Class.forName("net.minecraft.resources.Identifier", true, loader)
        return keyType.getMethod("create", keyType, idType)
            .invoke(null, registryKey(registryName), identifier)
    }

    override fun blockPropertiesLightLevel(properties: Any, level: Int): Any {
        val loader = OMLCore.gameClassLoader()
        val functional = Class.forName("java.util.function.ToIntFunction", true, loader)
        val constant = java.lang.reflect.Proxy.newProxyInstance(
            loader,
            arrayOf(functional),
        ) { _, _, _ -> level }
        properties.javaClass.methods
            .first { it.name == "lightLevel" && it.parameterCount == 1 }
            .invoke(properties, constant)
        return properties
    }

    override fun attributeModifier(identifier: Any, amount: Double, operation: Any): Any {
        val loader = OMLCore.gameClassLoader()
        val type = Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier", true, loader)
        return type.getConstructor(
            Class.forName("net.minecraft.resources.Identifier", true, loader),
            Double::class.javaPrimitiveType,
            Class.forName("net.minecraft.world.entity.ai.attributes.AttributeModifier\$Operation", true, loader),
        ).newInstance(identifier, amount, operation)
    }

    override fun repositorySource(pack: Any): Any {
        val loader = OMLCore.gameClassLoader()
        val sourceClass = Class.forName("net.minecraft.server.packs.repository.RepositorySource", true, loader)
        return java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(sourceClass)) { proxy, method, args ->
            when (method.name) {
                "loadPacks" -> {
                    val consumer = args[0]
                    java.util.function.Consumer::class.java
                        .getMethod("accept", Any::class.java)
                        .invoke(consumer, pack)
                }

                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args[0] === proxy
                "toString" -> "OMLRepositorySource@" + System.identityHashCode(proxy)
                else -> null
            }
        }
    }

    override fun ioSupplier(bytes: ByteArray): Any {
        val loader = OMLCore.gameClassLoader()
        val type = Class.forName("net.minecraft.server.packs.resources.IoSupplier", true, loader)
        return java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "get" -> java.io.ByteArrayInputStream(bytes)
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args[0] === proxy
                "toString" -> "OMLIoSupplier@" + System.identityHashCode(proxy)
                else -> null
            }
        }
    }

    override fun resourcesSupplier(resources: Any): Any {
        val loader = OMLCore.gameClassLoader()
        val type = Class.forName($$"net.minecraft.server.packs.repository.Pack$ResourcesSupplier", true, loader)
        return java.lang.reflect.Proxy.newProxyInstance(loader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "openMetadata" -> resources
                "openResources" -> java.util.stream.Stream.of(resources)
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args[0] === proxy
                "toString" -> "OMLResourcesSupplier@" + System.identityHashCode(proxy)
                else -> null
            }
        }
    }

    override fun blockStateRegistry(): Any = field(
        "net.minecraft.world.level.block.Block",
        "BLOCK_STATE_REGISTRY",
    )

    override fun dataComponent(name: String): Any = field(
        "net.minecraft.core.component.DataComponents",
        name,
    )

    override fun attribute(name: String): Any = field(
        "net.minecraft.world.entity.ai.attributes.Attributes",
        name,
    )

    override fun attributeOperation(name: String): Any = field(
        "net.minecraft.world.entity.ai.attributes.AttributeModifier\$Operation",
        name,
    )

    override fun equipmentSlotGroup(name: String): Any = field(
        "net.minecraft.world.entity.EquipmentSlotGroup",
        name,
    )

    override fun register(registryName: String, identifier: Any, value: Any) {
        val registry = builtinRegistry(registryName)
        val loader = OMLCore.gameClassLoader()
        // 26.3's MappedRegistry.register is private; the public entry point is the static
        // Registry.register(Registry, Identifier, T). Three 3-argument overloads exist
        // (String / Identifier / ResourceKey key types) — the Identifier one is selected by
        // matching the key's actual class rather than by position.
        val registryClassObj = Class.forName("net.minecraft.core.Registry", true, loader)
        registryClassObj.methods
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.name == "register" && it.parameterCount == 3 }
            .firstOrNull { it.parameterTypes[1] == identifier.javaClass }
            ?.invoke(null, registry, identifier, value)
            ?: throw IllegalStateException("registry $registryName lacks a static register entry point")
    }

    private fun field(className: String, fieldName: String): Any {
        val loader = OMLCore.gameClassLoader()
        return Class.forName(className, true, loader).getField(fieldName).get(null)
    }
}
