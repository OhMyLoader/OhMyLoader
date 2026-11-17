package org.ohmyloader.api.network

/**
 * Optional declaration entry point for custom payloads: when a [Mod][org.ohmyloader.api.Mod]-annotated
 * class implements this, OML calls it once after [org.ohmyloader.api.OMLModInitializer.onInitialize],
 * alongside the other declaration providers. Register each direction this side receives on; the loader
 * publishes every declared channel on both sides, so the same mod can run against an integrated or a
 * dedicated server without re-declaring.
 */
interface OMLNetworkProvider {
    fun declareNetwork(network: OMLNetworkRegistry)
}
