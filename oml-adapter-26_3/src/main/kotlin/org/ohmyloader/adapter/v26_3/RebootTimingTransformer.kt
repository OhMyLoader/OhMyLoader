package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The reboot bill's game-side probes: entry/exit marks on the bootstrap, device and reload sites
 * the flow runs through, so every stage of the timeline comes from the game's own code rather
 * than being inferred from log lines. Measurement only — the injected payload is a static call
 * into [RebootProbe], which relays to the supervisor loader's stopwatch; nothing reads the marks
 * to make decisions, and a wrong anchor surfaces as the usual injection verification problem at
 * startup rather than a behaviour change.
 *
 * 26.3-only (the RenderPearl frontend and the 26.3 reload internals are version-facing), so the
 * rules live with the adapter rather than the shared Minecraft transformer.
 */
class RebootTimingTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/client/Minecraft") {
            constructor(desc = "(Lnet/minecraft/client/main/GameConfig;)V") {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.minecraft.ctor.begin")),
                    )
                }
                atTail {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.minecraft.ctor.end")),
                    )
                }
            }
            method("close", desc = "()V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.close.begin")),
                    )
                }
                atTail {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.close.end")),
                    )
                }
            }
            method(
                "reloadResourcePacks",
                desc = "(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
            ) {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.reload.start")),
                    )
                }
            }
        }
        classTarget("net/minecraft/server/Bootstrap") {
            method("bootStrap", desc = "()V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.bootStrap.begin")),
                    )
                }
                atTail {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.bootStrap.end")),
                    )
                }
            }
        }
        classTarget("com/mojang/renderpearl/backend/opengl/GlDevice") {
            constructor(
                desc = "(Lcom/mojang/renderpearl/backend/opengl/GlBackend;" +
                    "Lcom/mojang/renderpearl/api/device/GpuDebugOptions;)V",
            ) {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.gldevice.begin")),
                    )
                }
                atTail {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.gldevice.end")),
                    )
                }
            }
        }
        classTarget("net/minecraft/client/renderer/texture/SpriteLoader") {
            method(
                "loadAndStitch",
                desc = "(Lnet/minecraft/server/packs/resources/ResourceManager;" +
                    "Lnet/minecraft/resources/Identifier;ILjava/util/concurrent/Executor;" +
                    "Ljava/util/Set;)Ljava/util/concurrent/CompletableFuture;",
            ) {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/v26_3/RebootProbe",
                    method = "loadAndStitch",
                    desc = "(Ljava/lang/Object;Ljava/lang/Object;ILjava/lang/Object;Ljava/lang/Object;" +
                        "Lorg/ohmyloader/api/hook/Operation;I)Ljava/util/concurrent/CompletableFuture;",
                    extras = listOf(DslValue.IntVal(0)),
                    coerceParams = setOf(0, 1, 3, 4),
                )
                require(1)
            }
        }
    },
    id = "v26_3:reboot-timing",
)
