package org.ohmyloader.api.network

/**
 * Converts a value of [T] to and from an [OMLPacketBuffer]. [write] and [read] run on the packet's
 * thread, so they must be pure field shuffling — take no locks and touch no game object.
 */
interface OMLPayloadCodec<T> {
    fun write(buffer: OMLPacketBuffer, value: T)
    fun read(buffer: OMLPacketBuffer): T
}

/**
 * One payload declaration: its channel name plus its codec. An id without a namespace is published as
 * `<mod id>:<id>`, so a mod's own channels cannot collide with another's.
 */
class OMLPayloadType<T>(val id: String, val codec: OMLPayloadCodec<T>)

/**
 * One inbound payload delivery. [senderName] is the authoritative remote end, taken from the connection
 * rather than from the payload: a client never decides who it is, and a server handler must not trust a
 * self-reported identity. Null when the connection carries no player (a modded peer or a console path).
 * [platform] is the raw version object behind the connection — the same escape hatch as
 * [org.ohmyloader.api.command.OMLCommandSource.platform].
 */
class OMLNetworkContext(
    val modId: String,
    val senderName: String?,
    platformSupplier: () -> Any,
) {
    val platform: Any by lazy(platformSupplier)
}

/** Registration entry point, bound to the declaring mod's id. */
interface OMLNetworkRegistry {
    /** Handles payloads this side receives from a client. Register on the server side. */
    fun <T> clientToServer(type: OMLPayloadType<T>, handler: (T, OMLNetworkContext) -> Unit)

    /** Handles payloads this side receives from the server. Register on the client side. */
    fun <T> serverToClient(type: OMLPayloadType<T>, handler: (T, OMLNetworkContext) -> Unit)
}

/** The version adapter's send path; the loader installs it before mods initialize. */
interface OMLNetworkSender {
    fun <T> toServer(type: OMLPayloadType<T>, value: T)
    fun <T> toPlayer(type: OMLPayloadType<T>, value: T, playerName: String)
    fun <T> toAllPlayers(type: OMLPayloadType<T>, value: T)
}

/**
 * Sending custom payloads. The three send calls dispatch to whichever side is running: [sendToServer]
 * needs a client connection, [sendToPlayer] and [sendToAllPlayers] need a server. Sending a type that
 * was not registered with its direction's handler throws.
 */
object OMLNetwork {

    @JvmField
    @Volatile
    var sender: OMLNetworkSender? = null

    fun <T> sendToServer(type: OMLPayloadType<T>, value: T) = requireSender().toServer(type, value)

    /** A name that matches no online player is reported, not thrown: a vanished player is normal race. */
    fun <T> sendToPlayer(type: OMLPayloadType<T>, value: T, playerName: String) =
        requireSender().toPlayer(type, value, playerName)

    fun <T> sendToAllPlayers(type: OMLPayloadType<T>, value: T) = requireSender().toAllPlayers(type, value)

    private fun requireSender(): OMLNetworkSender =
        sender ?: error("no version adapter provides a network send path")
}
