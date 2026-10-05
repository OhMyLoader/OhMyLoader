package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.api.inject.local
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * 26.3 game hook. 26.x jars are **not obfuscated** (Mojang stopped publishing the official
 * mappings with this line and does not need to — class and member names ship readable), so there
 * is no mapping space to translate from: every name below is literally what the jar contains.
 * Each rule is asserted against the live `26.3-client.jar` by [HookShapeTest], so a Mojang rename
 * or reshape fails the build instead of the hook silently missing at launch.
 */
class MinecraftHookTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/client/main/Main") {
            method("main", desc = "([Ljava/lang/String;)V") {
                atHead { omlCall("onMainIntercepted") }
                // Main's outer catch converts a fatal startup exception into a crash report; building
                // that report itself throws before the version is initialized, which is exactly the
                // case where the original exception is lost. The anchor is the first
                // `CrashReport.forThrowable(Throwable, String)` (offset 608); DUP2 duplicates the
                // throwable+message pair and feeds the copies to the OML log, leaving the stack as the
                // original call expects it. A second call site (offset 1454) is hit by the same rule.
                beforeCall(owner = "net/minecraft/CrashReport", name = "forThrowable") {
                    raw { m, _ ->
                        add(org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP2))
                        add(
                            org.objectweb.asm.tree.MethodInsnNode(
                                org.objectweb.asm.Opcodes.INVOKESTATIC,
                                "org/ohmyloader/core/OMLCore",
                                "debugOnCrash",
                                "(Ljava/lang/Throwable;Ljava/lang/String;)V",
                                false,
                            ),
                        )
                    }
                }
            }
        }
        classTarget("net/minecraft/client/Minecraft") {
            // Access-flag rewrite: `proxy` is still `private final java.net.Proxy` (26.3 added a
            // public `getProxy()` next to it, but the field itself stays closed). Loosening the bits
            // touches no instruction, so game behavior is unchanged; any package in the same process
            // can now read it as an ordinary public member.
            field("proxy", desc = "Ljava/net/Proxy;") {
                makePublic()
                removeFinal()
                require(1)
            }
            constructor {
                atTail { omlCall("onMinecraftReady") }
            }
            method("runTick", desc = "(Z)V") {
                atHead { call("org/ohmyloader/adapter/v26_3/EventBridge", "onClientTick", "()V") }
            }
            // Local read: `runTick(boolean)` stores `DeltaTracker$Timer.advanceGameTime(J)I` into int
            // slot 2 (offset 96 istore_2, the method's only int-slot write at that point — the other
            // int local is slot 4, not yet written). Read it back after the **first** ProfilerFiller.push
            // (offset 115, the first thing inside the `if (render)` branch). The slot is named
            // explicitly instead of being resolved by type alone: 26.3's runTick has a second int local
            // (slot 4, offsets 229 / 547), so "the only int local" is no longer a safe inference.
            method("runTick", desc = "(Z)V") {
                expect(1)
                afterCall(
                    owner = "net/minecraft/util/profiling/ProfilerFiller",
                    name = "push",
                    desc = "(Ljava/lang/String;)V",
                    ordinal = 0,
                ) {
                    call(
                        "org/ohmyloader/core/OMLCore", "onRunTickTicks", "(I)V",
                        args = listOf(local(index = 2, type = "I")),
                    )
                }
            }
            // Local-variable rewrite: the same slot 2, but anchored **after the STORE** — the engine
            // synthesizes a read-modify-write around it here. Slot 2 is also written once as a
            // reference (`pendingReload`'s CompletableFuture at offset 48, `astore_2`), which is why
            // the filter pins both the index and the type: `type = "I"` keeps the reference store out.
            // If the slot or type were off, the class would VerifyError at definition time instead of
            // silently rewriting a different variable.
            method("runTick", desc = "(Z)V") {
                require(1)
                afterStore(index = 2, type = "I") {
                    modifyVariable("org/ohmyloader/core/OMLCore", "onTicksStored", "(I)I")
                }
            }
            // Window title, product decision: OMLCore.onWindowTitle replaces whatever the game
            // computed with OMLCore.WINDOW_TITLE. Two producers, two rules:
            // - createTitle(): the constructor passes its result straight into the Window
            //   constructor (offsets 1004 / 1371), **bypassing** updateTitle() — rewriting the
            //   return value here covers the very first title the window is created with;
            // - updateTitle(): a straight `Window.setTitle(createTitle())` (offset 8); its
            //   ModifyArg below covers every later recompute (world join, focus change) and also
            //   keeps the ModifyArg demo chain alive — by then the argument is already the
            //   branded title, so the handler is idempotent.
            method("createTitle", desc = "()Ljava/lang/String;") {
                atReturn {
                    transformReturn(
                        owner = "org/ohmyloader/core/OMLCore",
                        method = "onWindowTitle",
                        desc = "(Ljava/lang/String;)Ljava/lang/String;",
                    )
                }
            }
            method("updateTitle", desc = "()V") {
                beforeCall(
                    owner = "com/mojang/blaze3d/platform/Window",
                    name = "setTitle",
                    desc = "(Ljava/lang/String;)V",
                ) {
                    modifyArg(
                        owner = "org/ohmyloader/core/OMLCore",
                        method = "onWindowTitle",
                        desc = "(Ljava/lang/String;)Ljava/lang/String;",
                        index = 0,
                    )
                }
            }
            // GuiOpenEvent (cancellable). 26.3 renamed `setScreen` to `setScreenAndShow`, and the
            // method now also forces a frame: `Gui.setScreen(screen)` at offset 17 is followed by
            // `renderFrame(false)`. Cancelling here therefore short-circuits both the screen swap and
            // that forced frame — the hook must sit ahead of both, so HEAD is the only anchor that
            // covers the whole GUI-open path.
            method("setScreenAndShow", desc = "(Lnet/minecraft/client/gui/screens/Screen;)V") {
                atHead {
                    cancellableCall(
                        "org/ohmyloader/adapter/v26_3/EventBridge", "onGuiOpen", "(Ljava/lang/Object;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            // WorldLoadEvent. 26.3 dropped the `ReceivingLevelScreen$Reason` parameter, so the
            // descriptor is back to a single argument (`(Lnet/minecraft/client/multiplayer/ClientLevel;)V`,
            // body: putfield level + updateLevelInEngines).
            method("setLevel", desc = "(Lnet/minecraft/client/multiplayer/ClientLevel;)V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/EventBridge", "onWorldLoad", "(Ljava/lang/Object;)V",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            // WorldLoadEvent (world = null). 26.3 has no `disconnect()` on Minecraft any more:
            // `disconnectFromWorld(Component)` is the leaving-the-world path (it disconnects the
            // ClientLevel, swaps in the progress/saving screen and finally a TitleScreen). Anchoring
            // its head fires the event while the world is still live, before any teardown.
            method("disconnectFromWorld", desc = "(Lnet/minecraft/network/chat/Component;)V") {
                atHead { call("org/ohmyloader/adapter/v26_3/EventBridge", "onWorldDisconnect", "()V") }
            }
        }
        classTarget("com/mojang/blaze3d/platform/FramerateLimitTracker") {
            // FrameRateLimitEvent (modifiable). The limit moved off Minecraft: this is the method
            // `Minecraft.renderFrame` reads at offset 411 to fill `GameRenderState.framerateLimit`.
            // Its body is a tableswitch over the throttle reason, so there are several returns; only
            // the **last** return is the value the caller will actually use, so TAIL — not RETURN —
            // is the anchor that means "the limit that will be applied".
            method("getFramerateLimit", desc = "()I") {
                atTail {
                    transformReturn(
                        owner = "org/ohmyloader/adapter/v26_3/EventBridge",
                        method = "onFrameLimit",
                        desc = "(I)I",
                    )
                }
            }
        }
        classTarget("net/minecraft/core/registries/BuiltInRegistries") {
            // Registry freeze-point redirect: 26.3's `bootStrap()` is `createContents()` (offset 0) ->
            // `freeze()` (offset 3) -> `validate(REGISTRY)`. Redirecting the freeze inserts the mod
            // content after vanilla content exists but before the registry is closed for writes (and
            // before validation) — the window mod content must land in to be visible to the game.
            method("bootStrap", desc = "()V") {
                redirectCall(
                    owner = "net/minecraft/core/registries/BuiltInRegistries",
                    name = "freeze",
                    desc = "()V",
                    handlerOwner = "org/ohmyloader/adapter/v26_3/EventBridge",
                    handlerMethod = "onRegistryFreeze",
                )
            }
        }
        // Zstd region-file compression (see ZstdRegionChunkFormat for the full constraint record).
        // Shared bootstrap code, so the identical rule pair is written in ServerHookTransformer too.
        // - <clinit> tail: registers the ID-127 Zstd version right after vanilla's own instances —
        //   the registry map must exist before anything can be pushed into it.
        // - getSelected() return rewrite: every new RegionFile (via the 4-arg constructor all
        //   RegionFileStorage paths use) picks the write format here; the chunk header's 5th byte
        //   is stamped from the same version's id, so format and on-disk byte stay consistent.
        // The read path is NOT touched: it dispatches on the on-disk byte via fromId, so old
        // deflate/gzip chunks keep decoding through vanilla's wrappers unchanged.
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
        // Resource pack repo openAllSelected pre-hook (mounts the mod pack). Shared bootstrap code,
        // so the rule is written in BOTH transformers — the client reload opens `assets/` and the
        // dedicated server's SERVER_DATA repository opens `data/` (declared ores merge into biome
        // files; recipes and loot are datapack JSON), and each side needs the pack mounted into its
        // own repository instance.
        //
        // Verified in the client jar that both resource-reload paths pass through here: the Minecraft
        // constructor inlines `reload()` -> `Options.loadSelectedResourcePacks` ->
        // `openAllSelected()` -> `createReload(...)`, and F3+T / the resource pack screen goes
        // `reloadResourcePacks` -> `reload()` -> `openAllSelected()`. Both call it *after* the
        // selection list has been applied from options, so the pack this hook adds is part of the
        // reload that is asking for it and is not overwritten afterwards.
        classTarget("net/minecraft/server/packs/repository/PackRepository") {
            method("openAllSelected", desc = "()Ljava/util/List;") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/EventBridge", "onPackRepositoryReload", "(Ljava/lang/Object;)V",
                        args = listOf(DslValue.This),
                    )
                }
            }
        }
        classTarget("net/minecraft/client/multiplayer/ClientPacketListener") {
            // ChatSentEvent (cancellable): sendChat head, message = Arg(1)
            method("sendChat", desc = "(Ljava/lang/String;)V") {
                atHead {
                    cancellableCall(
                        "org/ohmyloader/adapter/v26_3/EventBridge", "onChatSent", "(Ljava/lang/String;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            // ChatReceivedEvent (cancellable): head of the three inbound chat packet handlers,
            // packet = Arg(0)
            method("handleSystemChat", desc = "(Lnet/minecraft/network/protocol/game/ClientboundSystemChatPacket;)V") {
                atHead {
                    cancellableCall(
                        "org/ohmyloader/adapter/v26_3/EventBridge", "onChatReceived", "(Ljava/lang/Object;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            method("handlePlayerChat", desc = "(Lnet/minecraft/network/protocol/game/ClientboundPlayerChatPacket;)V") {
                atHead {
                    cancellableCall(
                        "org/ohmyloader/adapter/v26_3/EventBridge", "onChatReceived", "(Ljava/lang/Object;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            method(
                "handleDisguisedChat",
                desc = "(Lnet/minecraft/network/protocol/game/ClientboundDisguisedChatPacket;)V",
            ) {
                atHead {
                    cancellableCall(
                        "org/ohmyloader/adapter/v26_3/EventBridge", "onChatReceived", "(Ljava/lang/Object;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
        }
    },
    id = "v26_3:minecraft-hooks",
)
