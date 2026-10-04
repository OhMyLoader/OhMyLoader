package org.ohmyloader.api.content

/**
 * A [ContentRegistry.declareBlock] behavior event: built by the version adapter from the vanilla
 * block callback, synchronously on the thread the game called it on. [level] and [entity] are the
 * raw version objects — the same escape hatch as [OMLBlock.platform]; mods reaching into them
 * write version-specific code.
 */
class OMLStepOnEvent(
    val x: Int,
    val y: Int,
    val z: Int,
    /** True on the client's copy of the level; gameplay effects (damage, drops) belong on the server side. */
    val isClient: Boolean,
    levelSupplier: () -> Any,
    entitySupplier: () -> Any,
) {
    val level: Any by lazy(levelSupplier)

    /** The entity standing on the block. */
    val entity: Any by lazy(entitySupplier)
}

/** The [ContentRegistry.declareBlock] hit variant of [OMLStepOnEvent]; [player] is the one punching. */
class OMLBlockHitEvent(
    val x: Int,
    val y: Int,
    val z: Int,
    val isClient: Boolean,
    levelSupplier: () -> Any,
    playerSupplier: () -> Any,
) {
    val level: Any by lazy(levelSupplier)

    val player: Any by lazy(playerSupplier)
}
