package org.ohmyloader.adapter.common

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The custom-payload hooks. Mod packets share the game's two fixed payload packet ids and are routed by
 * channel name, so this transformer is registered on **both** sides in [OMLAdapterV26_3] like
 * [NetworkCompressionTransformer] — the same rules run in a client process and in a dedicated server.
 *
 * Anchor constraints in 26.3: `CustomPacketPayload.codec(FallbackProvider, List)` is the single static
 * factory both payload packet classes build their dispatch map from, and the list is still mutable at its
 * head (the map is snapshotted inside the method); the two inbound handlers differ in shape — the client
 * listener receives the payload itself, the server listener the packet wrapping it.
 */
class NetworkPayloadTransformer(private val idPrefix: String) : InjectingTransformer(
    injection {
        val bridge = "org/ohmyloader/adapter/common/OMLNetworkBridge"
        val payload = "net/minecraft/network/protocol/common/custom/CustomPacketPayload"

        classTarget(payload) {
            method(
                "codec",
                desc = $$"(L$$payload$FallbackProvider;Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;",
            ) {
                atHead {
                    call(bridge, "onPayloadRegistration", "(Ljava/lang/Object;)V", args = listOf(DslValue.Arg(1)))
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/multiplayer/ClientPacketListener") {
            method("handleCustomPayload", desc = "(L$payload;)V") {
                atHead {
                    cancellableCall(
                        bridge, "onClientPayload",
                        "(Lnet/minecraft/client/multiplayer/ClientPacketListener;L$payload;)Z",
                        args = listOf(DslValue.This, DslValue.Arg(0)),
                    )
                }
            }
        }
        classTarget("net/minecraft/server/network/ServerGamePacketListenerImpl") {
            method(
                "handleCustomPayload",
                desc = "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V",
            ) {
                atHead {
                    cancellableCall(
                        bridge, "onServerPayload",
                        "(Lnet/minecraft/server/network/ServerGamePacketListenerImpl;" +
                            "Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)Z",
                        args = listOf(DslValue.This, DslValue.Arg(0)),
                    )
                }
            }
        }
    },
    id = "$idPrefix:network-payload",
)
