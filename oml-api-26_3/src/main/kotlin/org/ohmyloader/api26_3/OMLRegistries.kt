package org.ohmyloader.api26_3

import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundSource
import net.minecraft.tags.TagKey
import net.minecraft.world.clock.ClockTimeMarker
import net.minecraft.world.clock.ClockTimeMarkers
import net.minecraft.world.clock.WorldClock
import net.minecraft.world.damagesource.DamageType
import net.minecraft.world.effect.MobEffect
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gamerules.GameRule
import net.minecraft.world.level.gamerules.GameRules
import net.minecraft.core.Holder
import net.minecraft.core.particles.ParticleType

/**
 * Registry ids (`minecraft:stone`) addressed to the game's own objects. A lookup answers null for an
 * unregistered id rather than the registry's default value: a typo in a mod's id must not silently
 * place air or play silence.
 *
 * The built-in tables are filled by the game's bootstrap, which mod initialization runs ahead of, so
 * reads belong to a moment that has a running game behind them. Datapack tables (damage types, clocks)
 * only exist behind a server at all — those lookups take it as an argument instead of guessing.
 */
object OMLRegistries {

    /** A parsed id; null when the string is not a `namespace:path` at all. */
    fun location(id: String): Identifier? = runCatching { Identifier.parse(id) }.getOrNull()

    fun block(id: String): Block? = BuiltInRegistries.BLOCK.byId(id)

    fun item(id: String): Item? = BuiltInRegistries.ITEM.byId(id)

    fun soundEvent(id: String): SoundEvent? = BuiltInRegistries.SOUND_EVENT.byId(id)

    fun entityType(id: String): EntityType<*>? = BuiltInRegistries.ENTITY_TYPE.byId(id)

    fun particleType(id: String): ParticleType<*>? = BuiltInRegistries.PARTICLE_TYPE.byId(id)

    fun attribute(id: String): Holder<Attribute>? = BuiltInRegistries.ATTRIBUTE.holderById(id)

    fun mobEffect(id: String): Holder<MobEffect>? = BuiltInRegistries.MOB_EFFECT.holderById(id)

    fun gameRule(id: String): GameRule<*>? = BuiltInRegistries.GAME_RULE.byId(id)

    /** The dimension key [id] names; resolving one is the only way to ask the server for a level. */
    fun dimension(id: String): ResourceKey<Level>? =
        location(id)?.let { ResourceKey.create(Registries.DIMENSION, it) }

    /** One of the game's sound categories by its own lower-case name (`master`, `blocks`, …). */
    fun soundSource(category: String): SoundSource? =
        SoundSource.values().firstOrNull { it.name.lowercase() == category.lowercase() }

    /** One of the game's three attribute operations, by its own lower-case name. */
    fun attributeOperation(name: String): AttributeModifier.Operation? =
        AttributeModifier.Operation.values().firstOrNull { it.name.lowercase() == name.lowercase() }

    /** Damage types are a datapack registry, so the table comes from the level the damage happens in. */
    fun damageType(level: Level, id: String): Holder<DamageType>? =
        level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).holderById(id)

    /** The clock [id] names in the server's own table; a holder built from anything else throws when the manager is asked for it. */
    fun clock(server: MinecraftServer, id: String): Holder<WorldClock>? =
        server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).holderById(id)

    /** A clock-time-marker key from an id — the shape the server's marker methods take. */
    fun clockMarker(id: String): ResourceKey<ClockTimeMarker>? =
        location(id)?.let { ResourceKey.create(ClockTimeMarkers.ROOT_ID, it) }

    /**
     * Assembles `id[facing=east,powered=false]` and hands it to the game's own block-state argument: an
     * unregistered block, an unknown property or a value the property does not allow all fail here, which
     * is the same rejection `/setblock` gives a player.
     */
    fun blockState(blockId: String, properties: Map<String, String> = emptyMap()): BlockState? {
        val spec = buildString {
            append(blockId)
            if (properties.isNotEmpty()) {
                append('[')
                properties.entries.joinTo(this, separator = ",") { "${it.key}=${it.value}" }
                append(']')
            }
        }
        return runCatching {
            // `Registry` is itself a `HolderLookup.RegistryLookup`, so the built-in table is what the
            // parser wants — no registry-access round trip.
            BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, spec, false).blockState()
        }.getOrNull()
    }

    /** The rule's value spelled the way the game spells it, or null for an unregistered id. */
    fun gameRuleValue(rules: GameRules, id: String): String? = gameRule(id)?.let { rules.getAsString(it) }

    /**
     * Parses [raw] with the rule's own deserializer — so a `Boolean` rule asked for `"3"` is refused rather
     * than guessed at — and writes it through the game, which owns propagating the change to every loaded
     * dimension. The registry hands back a star-projected `GameRule<*>` while the setter is generic in the
     * value type; the erased setter parameter is `Object`, so the cast checks nothing the JVM would.
     */
    @Suppress("UNCHECKED_CAST")
    fun setGameRuleValue(rules: GameRules, server: MinecraftServer, id: String, raw: String): Boolean {
        val rule = gameRule(id) ?: return false
        val value = rule.deserialize(raw).result().orElse(null) ?: return false
        rules.set(rule as GameRule<Any>, value, server)
        return true
    }
}

/** The value registered under [id], or null when this table has no such id. */
fun <T : Any> Registry<T>.byId(id: String): T? =
    OMLRegistries.location(id)?.takeIf(::containsKey)?.let { getValue(it) }

/** The id [value] is registered under; the game's own answer, which is its default id for an unregistered value. */
fun <T : Any> Registry<T>.idOf(value: T): String = getKey(value).toString()

/** Every id this table holds, in the game's own order. */
fun <T : Any> Registry<T>.ids(): List<String> = keySet().map { it.toString() }

fun <T : Any> Registry<T>.contains(id: String): Boolean = OMLRegistries.location(id)?.let(::containsKey) ?: false

/** The holder for [id]; null when unregistered. Holders are what the game's own effect, attribute and damage methods take. */
fun <T : Any> Registry<T>.holderById(id: String): Holder<T>? =
    OMLRegistries.location(id)?.let { getOptional(it).orElse(null) }?.let(::wrapAsHolder)

/**
 * Ids of everything carrying [tagId] in this table. The registry key is an argument because `Registry`
 * does not name itself: the game's own tag lookup needs `TagKey`, which is built from that key.
 */
fun <T : Any> Registry<T>.membersOf(registryKey: ResourceKey<Registry<T>>, tagId: String): List<String> {
    val location = OMLRegistries.location(tagId) ?: return emptyList()
    return getTagOrEmpty(TagKey.create(registryKey, location))
        .mapNotNull { getKey(it.value())?.toString() }
}

/** Every tag id [id] carries; empty for an unregistered value, which is also the game's answer. */
fun <T : Any> Registry<T>.tagsOf(id: String): List<String> =
    holderById(id)?.tags()?.map { it.location().toString() }?.toList() ?: emptyList()
