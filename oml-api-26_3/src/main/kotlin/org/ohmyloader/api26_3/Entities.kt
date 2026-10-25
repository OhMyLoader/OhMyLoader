package org.ohmyloader.api26_3

import net.minecraft.core.BlockPos
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
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.portal.TeleportTransition
import net.minecraft.world.phys.HitResult
import java.util.UUID
import net.minecraft.world.phys.Vec3

/** Registry id of this entity's type, e.g. `minecraft:cow`. */
val Entity.typeId: String get() = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString()

/** Registry id of the dimension this entity is in. */
val Entity.dimensionId: String get() = level().dimensionId

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

/**
 * The game's instance of [attributeId] on this entity. Null when the id is unregistered **or** when this kind of
 * entity carries no such attribute: `minecraft:attack_damage` belongs to attackers, so a cow has none, and the
 * game's own getter throws for that case rather than answering null — the check has to come first.
 */
private fun LivingEntity.attributeInstance(attributeId: String): AttributeInstance? {
    val holder = OMLRegistries.attribute(attributeId) ?: return null
    return holder.takeIf(attributes::hasAttribute)?.let(::getAttribute)
}

/**
 * The value the game computes for [attributeId] (`minecraft:max_health`) — base plus modifiers — or null when
 * this entity carries no such attribute. Health, speed and attack damage all live in these attributes.
 */
fun LivingEntity.attributeValue(attributeId: String): Double? = attributeInstance(attributeId)?.value

/** The value before any modifier, which is the number a mod writes; null when there is no such instance. */
fun LivingEntity.attributeBase(attributeId: String): Double? = attributeInstance(attributeId)?.baseValue

/**
 * Sets the base value; false when this entity carries no such attribute. The game clamps the number into the
 * attribute's own range
 * and applies its own consequence (a raised maximum does not refill health, a lowered one caps it), so read
 * [attributeValue] back rather than assuming the write is the answer.
 */
fun LivingEntity.setAttributeBase(attributeId: String, value: Double): Boolean {
    val instance = attributeInstance(attributeId) ?: return false
    instance.baseValue = value
    return true
}

/**
 * Adds a permanent modifier under [modifierId] (`my_mod:sword_bonus`) — permanent in the game's own sense: it
 * stays until removed, unlike one the game drops when the attribute recomputes. A modifier already held under
 * that id is replaced. [operation] is one of the game's three, in lower case: `add_value`,
 * `add_multiplied_base`, `add_multiplied_total`. False for an attribute this entity does not carry or an
 * unknown operation.
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
        .takeIf { it !== inventoryMenu }
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
    val target: ServerPlayer? = (this as? ServerPlayer) ?: OMLGame.server?.playerList?.getPlayer(uuid)
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
 * uuid, so false means there was no counterpart to move — including a handle the game has replaced, which
 * reads removed and is gone — an unknown dimension, or the game refused the destination. [yRot] / [xRot]
 * set the arrival facing (degrees, the game's own convention); null keeps the caller's current rotation.
 */
fun Player.teleport(
    x: Double,
    y: Double,
    z: Double,
    dimensionId: String? = null,
    yRot: Float? = null,
    xRot: Float? = null,
): Boolean {
    val server = OMLGame.server ?: return false
    val target = (this as? ServerPlayer) ?: server.playerList.getPlayer(uuid) ?: return false
    val level = if (dimensionId != null) {
        OMLRegistries.dimension(dimensionId)?.let(server::getLevel) ?: return false
    } else {
        target.level()
    }
    return target.teleportTo(level, x, y, z, emptySet(), yRot ?: target.yRot, xRot ?: target.xRot, false)
}

/**
 * Moves this entity into the dimension [dimensionId] names, through the same transition the portals and
 * `/teleport` run: motion comes along and anything riding it comes with it, while [yRot] / [xRot] set the
 * arrival facing (null keeps the entity's current rotation). Null when this server has no such level
 * loaded. The answer is the instance the game holds **after** the move, so a mod that keeps a handle
 * takes the answer rather than continuing to ask the one it had.
 */
fun Entity.teleportToLevel(
    dimensionId: String,
    x: Double,
    y: Double,
    z: Double,
    yRot: Float? = null,
    xRot: Float? = null,
): Entity? {
    val level = level() as? ServerLevel ?: return null
    val target = level.server.levelById(dimensionId) ?: return null
    return teleport(
        TeleportTransition(
            target, Vec3(x, y, z), deltaMovement, yRot ?: this.yRot, xRot ?: this.xRot,
            TeleportTransition.DO_NOTHING,
        ),
    )
}

/** The same transition for a player, which stays the same instance: the game moves the connection with it. */
fun ServerPlayer.teleportToLevel(
    dimensionId: String,
    x: Double,
    y: Double,
    z: Double,
    yRot: Float? = null,
    xRot: Float? = null,
): ServerPlayer? =
    (this as Entity).teleportToLevel(dimensionId, x, y, z, yRot, xRot) as? ServerPlayer

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

/**
 * What this player's eyes land on within [distance] blocks: the block id and position when the ray
 * stops against a block, or null when it reaches [distance] without hitting one. This is the
 * game's own block clip (`BlockGetter.clip`) over the eye position and view vector, so shapes,
 * not just full cubes, are what stops the ray.
 */
fun Player.raycast(distance: Double): OMLRayHit? {
    val from = getEyePosition()
    val to = from.add(getViewVector(1.0f).scale(distance))
    val hit = level().clip(
        ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this),
    )
    if (hit.type != HitResult.Type.BLOCK) return null
    return OMLRayHit(level().getBlockState(hit.blockPos).blockId, hit.blockPos)
}

/** One [Player.raycast] answer: the block id the ray stopped against, and where. */
class OMLRayHit(val blockId: String, val position: BlockPos)

/**
 * Gives [points] of experience (the game's own `giveExperiencePoints`, which handles level-ups);
 * negative points take away, stopping at zero total. The level/progress views update immediately.
 */
fun Player.giveXp(points: Int) {
    giveExperiencePoints(points)
}

/** The player's current experience level. */
val Player.xpLevel: Int get() = experienceLevel

/** How far into the current level the player is: 0.0 (just leveled) to (just under) 1.0. */
val Player.xpProgress: Float get() = experienceProgress

/**
 * The player's hunger: 0–20 (the game's own scale). [setFoodLevel] clamps to the game's window;
 * 20 with saturation is a fed player, 0 begins starvation on the game's own schedule.
 */
val Player.foodLevel: Int
    get() = foodData.foodLevel

/** Writes the hunger bar; the game clamps and applies its own starvation rules from the next tick. */
fun Player.setFoodLevel(level: Int) {
    foodData.setFoodLevel(level)
}

/** The player's saturation, 0 to the food level: what keeps the hunger bar from draining. */
val Player.saturation: Float
    get() = foodData.saturationLevel

/** Feeds the player [food] points of hunger and [saturation] to carry — the game's own `eat`. */
fun Player.feed(food: Int, saturation: Float) {
    foodData.eat(food, saturation)
}

// ---------------------------------------------------------------------------------------------------------------------
// Abilities and the recipe book — the two survival knobs a utility mod reaches for after the bars
// ---------------------------------------------------------------------------------------------------------------------

/** Whether the game lets this player start flying; writing it syncs to the client with the game's own packet. */
var Player.allowFlight: Boolean
    get() = abilities.mayfly
    set(value) {
        abilities.mayfly = value
        if (!value && abilities.flying) abilities.flying = false
        onUpdateAbilities()
    }

/** Whether the player is flying right now; write false to land them, true only sticks when [allowFlight] holds. */
var Player.isFlying: Boolean
    get() = abilities.flying
    set(value) {
        abilities.flying = if (value) abilities.mayfly else false
        onUpdateAbilities()
    }

/** Whether nothing may harm this player — the game's own flag, which its own damage checks read. */
var Player.invulnerable: Boolean
    get() = abilities.invulnerable
    set(value) {
        abilities.invulnerable = value
        onUpdateAbilities()
    }

/**
 * Unlocks [recipeIds] (`techmod:techmod_raw_copper`, bare ids resolve in the mod's own namespace is
 * NOT supported here — use full ids) in this player's recipe book. Answers how many were newly
 * unlocked; an unknown id counts as zero.
 */
fun Player.grantRecipes(recipeIds: List<String>): Int {
    val holders = recipeIds.mapNotNull { OMLRegistries.recipeHolder(it) }
    return awardRecipes(holders)
}

/** Locks [recipeIds] again; answers how many were actually removed from the book. */
fun ServerPlayer.revokeRecipes(recipeIds: List<String>): Int {
    val holders = recipeIds.mapNotNull { OMLRegistries.recipeHolder(it) }
    return resetRecipes(holders)
}

// ---------------------------------------------------------------------------------------------------------------------
// Boss bars — the game's own bar, addressed by name
// ---------------------------------------------------------------------------------------------------------------------

/**
 * One boss bar a mod shows to its players: the game's own `ServerBossEvent`, so the look, the
 * positioning and the per-player sync are vanilla's. Create one, `players +=` who should see it,
 * write [progress] as the fight goes, and [remove] it at the end.
 */
class OMLBossBar(title: String) {
    private val event = ServerBossEvent(
        UUID.randomUUID(), Component.literal(title), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS,
    )

    /** The bar's title, changeable mid-fight. */
    var title: String
        get() = event.name.string
        set(value) { event.name = Component.literal(value) }

    /** How much of the bar is filled: 0.0 to 1.0. */
    var progress: Float
        get() = event.progress
        set(value) { event.progress = value.coerceIn(0f, 1f) }

    /** Whether the bar is shown to its players at all — the game's own visibility toggle. */
    var visible: Boolean
        get() = event.isVisible
        set(value) { event.isVisible = value }

    /** Adds a player to the audience; the bar appears on that client through the game's own packet. */
    fun addPlayer(player: ServerPlayer) = event.addPlayer(player)

    /** Takes a player off the audience; the bar leaves that client. */
    fun removePlayer(player: ServerPlayer) = event.removePlayer(player)

    /** Takes everyone off the audience — the end-of-fight cleanup. */
    fun removeAllPlayers() = event.removeAllPlayers()

    /** The players currently seeing this bar, as the game counts them. */
    val players: Collection<ServerPlayer> get() = event.players
}
