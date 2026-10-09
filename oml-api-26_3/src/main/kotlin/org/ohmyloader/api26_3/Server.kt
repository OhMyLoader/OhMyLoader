package org.ohmyloader.api26_3

import net.minecraft.core.Registry
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey

/** Registry ids of the loaded dimensions. A dimension the server has not started is absent, not empty. */
val MinecraftServer.dimensionIds: List<String> get() = allLevels.map { it.dimensionId }

/** The players on the server now; on an integrated server that is the singleplayer player. */
val MinecraftServer.onlinePlayers: List<ServerPlayer> get() = playerList.players

fun MinecraftServer.playerByName(name: String): ServerPlayer? = getPlayerList().getPlayerByName(name)

/** The loaded level of [dimensionId] (`minecraft:overworld`); null when that dimension is not loaded. */
fun MinecraftServer.levelById(dimensionId: String): ServerLevel? =
    OMLRegistries.dimension(dimensionId)?.let(::getLevel)

/** Shows [text] in the chat of every connected player, through the game's own system-message path. */
fun MinecraftServer.broadcastMessage(text: String) =
    playerList.broadcastSystemMessage(Component.literal(text), false)

/**
 * The game's own value of the rule addressed by registry id (`minecraft:keep_inventory`), spelled the way the
 * game spells it (`"true"`, `"3"`); null for an unregistered id.
 */
fun MinecraftServer.gameRuleValue(ruleId: String): String? = OMLRegistries.gameRuleValue(gameRules, ruleId)

/**
 * Sets [value] on that rule, parsed by the game's own serializer: false when the id is unregistered or the
 * value does not parse for that rule — a boolean rule asked for `"3"`. Propagating the change to the loaded
 * dimensions is the game's own business; OML keeps no copy of it.
 */
fun MinecraftServer.setGameRule(ruleId: String, value: String): Boolean =
    OMLRegistries.setGameRuleValue(gameRules, this, ruleId, value)

/** The tag key [tagId] names in the registry [registryKey] — the shape the game's own tag reads take. */
fun <T : Any> tagKeyOf(registryKey: ResourceKey<Registry<T>>, tagId: String): TagKey<T>? =
    OMLRegistries.location(tagId)?.let { TagKey.create(registryKey, it) }

/** The datapack table [registryKey] names on this server; null when the server has not loaded it. */
fun <T : Any> MinecraftServer.registry(registryKey: ResourceKey<Registry<T>>): Registry<T>? =
    runCatching { registryAccess().lookupOrThrow(registryKey) }.getOrNull()

/** Damage types and clocks are datapack registries: they exist only behind a running server. */
val MinecraftServer.damageTypeIds: List<String>
    get() = registry(Registries.DAMAGE_TYPE)?.ids() ?: emptyList()

val MinecraftServer.clockIds: List<String>
    get() = registry(Registries.WORLD_CLOCK)?.ids() ?: emptyList()
