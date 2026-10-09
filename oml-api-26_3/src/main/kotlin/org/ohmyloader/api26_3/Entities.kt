package org.ohmyloader.api26_3

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.permissions.LevelBasedPermissionSet
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.ai.attributes.AttributeInstance
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

/** Registry id of this entity's type, e.g. `minecraft:cow`. */
val Entity.typeId: String get() = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()

/** Registry id of the dimension this entity is in. */
val Entity.dimensionId: String get() = level().dimensionId

/**
 * Takes the entity out of its level and reports whether the game did; false means it is still there. Not for
 * players: removing a player entity detaches it from the level while its connection keeps pointing at it.
 */
fun Entity.discard(): Boolean {
    remove(Entity.RemovalReason.DISCARDED)
    return isRemoved
}

/**
 * Registry id of this effect, e.g. `minecraft:regeneration`. The game's own `duration` is the remaining time
 * and its `amplifier` is zero-based, so 0 is level I.
 */
val MobEffectInstance.effectId: String
    get() = BuiltInRegistries.MOB_EFFECT.getKey(effect.value()).toString()

/** True when [effectId] (`minecraft:poison`) is on this entity; false for an unregistered id. */
fun LivingEntity.hasEffect(effectId: String): Boolean =
    OMLRegistries.mobEffect(effectId)?.let(::hasEffect) ?: false

/**
 * Applies [effectId] for [durationTicks] at [amplifier] (0 is level I); false when the id is not a registered
 * effect. Re-applying the same effect follows the game's own merge rule — it does not simply replace what is
 * there.
 */
fun LivingEntity.applyEffect(effectId: String, durationTicks: Int, amplifier: Int = 0): Boolean {
    val holder = OMLRegistries.mobEffect(effectId) ?: return false
    return addEffect(MobEffectInstance(holder, durationTicks, amplifier))
}

/** Removes [effectId]; false when the id is unknown or the entity does not have it. */
fun LivingEntity.removeEffect(effectId: String): Boolean =
    OMLRegistries.mobEffect(effectId)?.let(::removeEffect) ?: false

private fun LivingEntity.attributeInstance(attributeId: String): AttributeInstance? =
    OMLRegistries.attribute(attributeId)?.let(::getAttribute)

/**
 * The value the game computes for [attributeId] (`minecraft:max_health`) — base plus modifiers — or null for an
 * unregistered id. Health, speed and attack damage all live in these attributes.
 */
fun LivingEntity.attributeValue(attributeId: String): Double? =
    OMLRegistries.attribute(attributeId)?.let(::getAttributeValue)

/** The value before any modifier, which is the number a mod writes; null for an unregistered id. */
fun LivingEntity.attributeBase(attributeId: String): Double? = attributeInstance(attributeId)?.baseValue

/**
 * Sets the base value; false for an unregistered id. The game clamps the number into the attribute's own range
 * and applies its own consequence (a raised maximum does not refill health, a lowered one caps it), so read
 * [attributeValue] back rather than assuming the write is the answer.
 */
fun LivingEntity.setAttributeBase(attributeId: String, value: Double): Boolean {
    val instance = attributeInstance(attributeId) ?: return false
    instance.setBaseValue(value)
    return true
}

/**
 * Adds a permanent modifier under [modifierId] (`my_mod:sword_bonus`) — permanent in the game's own sense: it
 * stays until removed, unlike one the game drops when the attribute recomputes. A modifier already held under
 * that id is replaced. [operation] is one of the game's three, in lower case: `add_value`,
 * `add_multiplied_base`, `add_multiplied_total`. False for an unregistered attribute or unknown operation.
 */
fun LivingEntity.addAttributeModifier(
    attributeId: String,
    modifierId: String,
    amount: Double,
    operation: String,
): Boolean {
    val instance = attributeInstance(attributeId) ?: return false
    val op = OMLRegistries.attributeOperation(operation) ?: return false
    val location = OMLRegistries.location(modifierId) ?: return false
    instance.addOrReplacePermanentModifier(AttributeModifier(location, amount, op))
    return true
}

/** Removes the modifier held under [modifierId]; false when this attribute carries no such modifier. */
fun LivingEntity.removeAttributeModifier(attributeId: String, modifierId: String): Boolean {
    val instance = attributeInstance(attributeId) ?: return false
    val location = OMLRegistries.location(modifierId) ?: return false
    return instance.removeModifier(location)
}

/**
 * Deals [amount] damage of [damageTypeId] (`minecraft:cactus`, a mod's own type) through the game's own damage
 * path, crediting [attacker] as the entity behind it when given; false when the id is not a registered damage
 * type, the entity is not in a server-side level, or the game applied nothing.
 *
 * The damage arrives through the same hook an attack does, so a `LIVING_HURT` handler sees it, can cancel it
 * and can rewrite the number — this is a gameplay action, not a way around the game's rules.
 */
fun LivingEntity.hurt(damageTypeId: String, amount: Float, attacker: Entity? = null): Boolean {
    val serverLevel = level() as? ServerLevel ?: return false
    val holder = OMLRegistries.damageType(level(), damageTypeId) ?: return false
    val source = if (attacker == null) DamageSource(holder) else DamageSource(holder, attacker, attacker)
    return hurtServer(serverLevel, source, amount)
}

/**
 * Sets the entity alight for [ticks] (60 is three seconds); false when the game refused, which is what a
 * fire-immune entity answers. The verdict is the game's own burning flag after the request.
 */
fun LivingEntity.ignite(ticks: Int): Boolean {
    igniteForTicks(ticks)
    return isOnFire
}

/** Takes the fire off; false when the game already reported the entity as unlit. */
fun LivingEntity.extinguish(): Boolean {
    val burning = isOnFire
    clearFire()
    return burning && !isOnFire
}

/**
 * Registry id of the menu this player is using (`minecraft:generic_9x3`, `minecraft:furnace`), or null while
 * they hold only the inventory menu the game gives every player. This is the game's own screen state: an
 * interaction the player never completed has not changed it. The menu id is not the id of a block — a chest's
 * menu is `generic_9x3`, so a mod matching one against the other finds nothing.
 */
val Player.openedMenuId: String?
    get() = containerMenu
        ?.takeIf { it !== inventoryMenu }
        ?.let { BuiltInRegistries.MENU.getKey(it.type)?.toString() }

/**
 * Closes what the player has open through the game's own path, which drops the carried stack back into their
 * inventory and marks the block unused; false when they had no menu open to close. Only the server-side close
 * reaches the game's state — a client's copy is closed by the packet the server sends.
 */
fun ServerPlayer.closeMenu(): Boolean {
    if (containerMenu === inventoryMenu) return false
    closeContainer()
    return containerMenu === inventoryMenu
}

/**
 * `survival` / `creative` / `adventure` / `spectator`, or null when the game has not given this player a game
 * type — the accessor is nullable, and a client-side player can be read before the server has sent one.
 */
val Player.gameModeName: String? get() = gameMode()?.getName()

/** Vanilla's permission level: 0 player, 1 moderator, 2 gamemaster, 3 admin, 4 owner. A player the server
 * has no permission set for reports 0. */
val Player.permissionLevel: Int
    get() = ((this as? ServerPlayer)?.permissions() as? LevelBasedPermissionSet)?.level()?.id() ?: 0

/**
 * Shows [text] in this player's chat through its server-side connection. A player with no server counterpart
 * (a multiplayer client's own local player) is a no-op — there is no channel to write on.
 */
fun Player.sendSystemMessage(text: String) {
    val target: ServerPlayer? = (this as? ServerPlayer) ?: OMLGame.server?.getPlayerList()?.getPlayer(uuid)
    target?.connection?.send(ClientboundSystemChatPacket(Component.literal(text), false))
}

/** Puts `count` of [itemId] into the inventory; false when not everything fit. */
fun Player.giveItem(itemId: String, count: Int): Boolean {
    val item = OMLRegistries.item(itemId) ?: return false
    return inventory.add(ItemStack(item, count))
}

/**
 * Moves the player to [x], [y], [z] — and to [dimensionId] as well when one is given. Movement is
 * server-authoritative: a client-side player is served through the integrated server's counterpart of the same
 * uuid, so false means there was no counterpart to move, an unknown dimension, or the game refused the
 * destination.
 */
fun Player.teleport(x: Double, y: Double, z: Double, dimensionId: String? = null): Boolean {
    val server = OMLGame.server ?: return false
    val target = (this as? ServerPlayer) ?: server.getPlayerList().getPlayer(uuid) ?: return false
    val level = if (dimensionId != null) {
        OMLRegistries.dimension(dimensionId)?.let(server::getLevel) ?: return false
    } else {
        target.level()
    }
    return target.teleportTo(level, x, y, z, emptySet(), target.yRot, target.xRot, false)
}

/**
 * Plays [soundId] (`minecraft:entity.pig.ambient`) so this player hears it: a packet to that client for a
 * server-side player, local playback for a client-side one — which is the game's own split. False for an
 * unregistered id.
 */
fun Player.playSound(soundId: String, volume: Float = 1f, pitch: Float = 1f): Boolean {
    val event = OMLRegistries.soundEvent(soundId) ?: return false
    playSound(event, volume, pitch)
    return true
}
