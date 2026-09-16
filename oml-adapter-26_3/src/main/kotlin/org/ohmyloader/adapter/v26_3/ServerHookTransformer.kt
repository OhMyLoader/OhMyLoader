package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * 26.3 **server-side** game hook, kept separate from [MinecraftHookTransformer] because the two sides target different classes (the client hooks `Minecraft`, the server hooks `MinecraftServer`): a merged batch would let every rule silently miss on the other side — the most dangerous failure mode of this engine.
 * The exception is rules targeting **shared bootstrap code**, written in both transformers — the duplication is deliberate, it is the same fact: `BuiltInRegistries.bootStrap()` runs on both sides and content must materialize on both (a block that exists only on the client cannot be placed, saved or sent); the region-file compression rules likewise.
 * 26.3 shape facts: `MinecraftServer` gained constructor parameters, so the constructor rule deliberately declares no descriptor and matches every overload — what the once-only [org.ohmyloader.core.OMLCore.onServerReady] guard expects; `tickServer` is `protected`, so its rule matches on the descriptor, not visibility; `bootStrap()` is reached from `Bootstrap.bootStrap()` in the dedicated-server call path, so the rule is live on the server.
 */
class ServerHookTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/server/MinecraftServer") {
            // invoked once per logic tick of the dedicated server main loop
            method("tickServer", desc = "(Ljava/util/function/BooleanSupplier;)V") {
                atHead { call("org/ohmyloader/adapter/v26_3/EventBridge", "onServerTick", "()V") }
                require(1)
            }

            // The server-side "environment ready" entry point, symmetric to onMinecraftReady at the
            // tail of the client constructor — there is no `Minecraft` class on this side, so that
            // path never fires.
            constructor {
                atTail { omlCall("onServerReady") }
            }
        }

        // Shared bootstrap code, so the same rule as in MinecraftHookTransformer (see the class doc
        // for why it is written twice). Without it the dedicated server never reaches
        // EventBridge.onRegistryFreeze, and the symptom is not a crash but an asymmetry: the client
        // has the modded block and the server does not, so placing it produces a ghost block and the
        // next save drops it.
        classTarget("net/minecraft/core/registries/BuiltInRegistries") {
            method("bootStrap", desc = "()V") {
                redirectCall(
                    owner = "net/minecraft/core/registries/BuiltInRegistries",
                    name = "freeze",
                    desc = "()V",
                    handlerOwner = "org/ohmyloader/adapter/v26_3/EventBridge",
                    handlerMethod = "onRegistryFreeze"
                )
            }
        }

        // Zstd region-file compression — the identical rule pair as in MinecraftHookTransformer
        // (same fact, written twice, per the BuiltInRegistries convention above). The dedicated
        // server is the primary writer of region files; without this rule it would keep producing
        // deflate chunks while the client expected Zstd. See ZstdRegionChunkFormat for the
        // constraints and the old-world compatibility argument.
        classTarget("net/minecraft/world/level/chunk/storage/RegionFileVersion") {
            method("<clinit>", desc = "()V") {
                atTail {
                    call(
                        "org/ohmyloader/adapter/v26_3/ZstdRegionChunkFormat",
                        "onRegionFileVersionInitialized", "()V",
                    )
                }
            }
            method("getSelected", desc = "()Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;") {
                atTail {
                    transformReturn(
                        owner = "org/ohmyloader/adapter/v26_3/ZstdRegionChunkFormat",
                        method = "onSelectedVersion",
                        desc = "(Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;)Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;",
                    )
                }
            }
        }
    },
    id = "v26_3:server-hooks",
)