package org.ohmyloader.core.network

import org.ohmyloader.api.network.OMLNetworkContext
import org.ohmyloader.api.network.OMLPayloadType

/**
 * The collected custom-payload channels of every mod, in declaration order. The declarations are
 * version-agnostic data (channel name, codec, handler); the version adapter publishes the names into the
 * game's payload registry and routes inbound packets back through [handlerFor].
 *
 * Publishing is process-wide and happens once per launch: a channel is registered on both flows, so a
 * client's integrated server and a dedicated server need no re-collection. [handlerFor] is what makes a
 * channel one-directional in use — an inbound packet whose direction nobody declared is left to vanilla.
 */
object PayloadDeclarations {

    enum class Direction { CLIENT_TO_SERVER, SERVER_TO_CLIENT }

    class Entry(
        val modId: String,
        val wireId: String,
        val direction: Direction,
        val type: OMLPayloadType<*>,
        val handler: (Any?, OMLNetworkContext) -> Unit,
    )

    val entries = mutableListOf<Entry>()

    /** An id without a namespace is published under the declaring mod's id. */
    fun wireIdOf(modId: String, declared: String): String =
        if (declared.contains(':')) declared else "$modId:$declared"

    /**
     * Records one declaration. The channel name is checked here rather than left to the game's
     * identifier parser: an illegal name discovered during the payload registry's class initialization
     * would crash game bootstrap with no mention of the mod that caused it.
     */
    fun <T> add(
        modId: String,
        type: OMLPayloadType<T>,
        direction: Direction,
        handler: (T, OMLNetworkContext) -> Unit,
    ) {
        val wireId = wireIdOf(modId, type.id)
        require(wireId.matches(NAMESPACE_PATH)) {
            "payload channel '$wireId' from mod [$modId] is not a valid channel name " +
                "(expected '<namespace>:<path>' with lowercase letters, digits, '.', '_', '-' and '/' in the path)"
        }
        val clash = entries.firstOrNull { it.wireId == wireId && it.direction == direction }
        if (clash != null) {
            error(
                "payload channel '$wireId' (${direction.name.lowercase()}) is already declared by " +
                    "mod [${clash.modId}]",
            )
        }
        @Suppress("UNCHECKED_CAST")
        entries += Entry(modId, wireId, direction, type) { value, context -> handler(value as T, context) }
    }

    /** The handler declared for [wireId] arriving in [direction], or null when nobody declared it. */
    fun handlerFor(wireId: String, direction: Direction): Entry? =
        entries.firstOrNull { it.wireId == wireId && it.direction == direction }

    /** The declaration behind a send call: a type is only sendable in the direction it was declared for. */
    fun entryFor(type: OMLPayloadType<*>, direction: Direction): Entry? =
        entries.firstOrNull { it.type === type && it.direction == direction }

    private val NAMESPACE_PATH = Regex("[a-z0-9_.-]+:[a-z0-9/_.-]+")
}
