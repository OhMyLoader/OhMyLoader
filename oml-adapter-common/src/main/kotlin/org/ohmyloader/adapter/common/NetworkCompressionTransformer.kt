package org.ohmyloader.adapter.common

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The `oml:zstd` packet-compression hooks. Network code is shared between the client and the dedicated server (both run out of the same
 * unified 26.3 jar), so this transformer is registered on **both** sides in [OMLAdapterV26_3] — the rules are written once, in one place.
 * Anchor constraints in 26.3: `ServerLoginPacketListenerImpl.handleCustomQueryPacket` runs ahead of vanilla's answer handling (vanilla
 * discards unknown answers); `ServerboundCustomQueryAnswerPacket.readPayload` (private static) *skips* the answer bytes, so the only way
 * to see them is a return-value rewrite — at return the buffer's reader index sits at the payload end; `Connection.setupCompression(int,
 * boolean)` is the single point where vanilla installs or reconfigures `"compress"` / `"decompress"` — OML reconfigures ahead of it and
 * replaces after it.
 */
class NetworkCompressionTransformer(private val idPrefix: String) : InjectingTransformer(
    injection {
        val bridge = "org/ohmyloader/adapter/common/OmlZstdNetwork"

        classTarget("net/minecraft/server/network/ServerLoginPacketListenerImpl") {
            method("handleHello", desc = "(Lnet/minecraft/network/protocol/login/ServerboundHelloPacket;)V") {
                atTail {
                    call(
                        bridge, "onServerHello", "(Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;)V",
                        args = listOf(DslValue.This),
                    )
                }
            }
            method(
                "handleCustomQueryPacket",
                desc = "(Lnet/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket;)V",
            ) {
                atHead {
                    // Cancellable by necessity: 26.3's vanilla body disconnects on ANY answer
                    // (javap: the method is a single `disconnect(UNEXPECTED_QUERY)`), so both the
                    // OML answer and a vanilla client's empty fallback answer must cancel it.
                    cancellableCall(
                        bridge, "onServerQueryAnswer",
                        "(Lnet/minecraft/server/network/ServerLoginPacketListenerImpl;" +
                            "Lnet/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket;)Z",
                        args = listOf(DslValue.This, DslValue.Arg(0)),
                    )
                }
            }
        }
        classTarget("net/minecraft/network/protocol/login/ServerboundCustomQueryAnswerPacket") {
            method(
                "readPayload",
                desc = "(ILnet/minecraft/network/FriendlyByteBuf;)Lnet/minecraft/network/protocol/login/custom/CustomQueryAnswerPayload;",
            ) {
                atReturn {
                    transformReturn(
                        owner = bridge,
                        method = "onServerReadQueryAnswer",
                        desc = "(Lnet/minecraft/network/protocol/login/custom/CustomQueryAnswerPayload;" +
                            "ILnet/minecraft/network/FriendlyByteBuf;)" +
                            "Lnet/minecraft/network/protocol/login/custom/CustomQueryAnswerPayload;",
                        extras = listOf(DslValue.Arg(0), DslValue.Arg(1)),
                    )
                }
            }
        }
        classTarget("net/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl") {
            method(
                "handleCustomQuery",
                desc = "(Lnet/minecraft/network/protocol/login/ClientboundCustomQueryPacket;)V",
            ) {
                atHead {
                    cancellableCall(
                        bridge, "onClientQuery",
                        "(Lnet/minecraft/client/multiplayer/ClientHandshakePacketListenerImpl;" +
                            "Lnet/minecraft/network/protocol/login/ClientboundCustomQueryPacket;)Z",
                        args = listOf(DslValue.This, DslValue.Arg(0)),
                    )
                }
            }
        }
        classTarget("net/minecraft/network/Connection") {
            method("setupCompression", desc = "(IZ)V") {
                atHead {
                    cancellableCall(
                        bridge, "onCompressionConfigured",
                        "(Lnet/minecraft/network/Connection;IZ)Z",
                        args = listOf(DslValue.This, DslValue.Arg(0), DslValue.Arg(1)),
                    )
                }
                atTail {
                    call(
                        bridge, "onCompressionReady", "(Lnet/minecraft/network/Connection;IZ)V",
                        args = listOf(DslValue.This, DslValue.Arg(0), DslValue.Arg(1)),
                    )
                }
            }
        }
    },
    id = "$idPrefix:network-compression",
)
