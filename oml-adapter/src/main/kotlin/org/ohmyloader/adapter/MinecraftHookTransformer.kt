package org.ohmyloader.adapter

import org.ohmyloader.api.core.inject.DslValue
import org.ohmyloader.api.core.inject.injection
import org.ohmyloader.api.core.inject.local
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * Client game hook. 26.x jars are **not obfuscated** (Mojang stopped publishing the official
 * mappings with this line and does not need to — class and member names ship readable), so there is
 * no mapping space to translate from: every name below is literally what the jar contains.
 * [HookShapeTest] asserts the declared rules against the live jar, so a Mojang rename or reshape
 * fails the build instead of the hook silently missing at launch.
 */
class MinecraftHookTransformer(private val idPrefix: String) : InjectingTransformer(
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
                atHead { call("org/ohmyloader/adapter/EventBridge", "onClientTick", "()V") }
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
            //   constructor, **bypassing** updateTitle() — rewriting the return value here covers
            //   the very first title the window is created with;
            // - updateTitle(): `Window.setTitle(createTitle())`, a straight pass-through whose
            //   ModifyArg covers every later recompute (world join, focus change). Both handlers
            //   are idempotent: by then the argument is already the branded title.
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
            // GuiOpenEvent (cancelable). 26.3 renamed `setScreen` to `setScreenAndShow`, and the
            // method now also forces a frame: `Gui.setScreen(screen)` at offset 17 is followed by
            // `renderFrame(false)`. Canceling here therefore short-circuits both the screen swap and
            // that forced frame — the hook must sit ahead of both, so HEAD is the only anchor that
            // covers the whole GUI-open path.
            method("setScreenAndShow", desc = "(Lnet/minecraft/client/gui/screens/Screen;)V") {
                atHead {
                    cancelableCall(
                        "org/ohmyloader/adapter/EventBridge", "onGuiOpen",
                        "(Lnet/minecraft/client/gui/screens/Screen;)Z",
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
                        "org/ohmyloader/adapter/EventBridge", "onWorldLoad",
                        "(Lnet/minecraft/client/multiplayer/ClientLevel;)V",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            // WorldLoadEvent (world = null). 26.3 has no `disconnect()` on Minecraft any more:
            // `disconnectFromWorld(Component)` is the leaving-the-world path (it disconnects the
            // ClientLevel, swaps in the progress/saving screen and finally a TitleScreen). Anchoring
            // its head fires the event while the world is still live, before any teardown.
            method("disconnectFromWorld", desc = "(Lnet/minecraft/network/chat/Component;)V") {
                atHead { call("org/ohmyloader/adapter/EventBridge", "onWorldDisconnect", "()V") }
            }
            // The surface is released every reboot, leased window or not: GlSurface.close() is a
            // no-op and VulkanGpuSurface.close() only destroys the swapchain, its semaphores and
            // the VkSurfaceKHR — neither touches the SDL window, which only Window.close destroys.
            // Skipping it would strand a swapchain on the leased handle and make the next session's
            // vkCreateSwapchainKHR fail with VK_ERROR_NATIVE_WINDOW_IN_USE_KHR.
            method("close", desc = "()V") {
                beforeCall(
                    owner = "com/mojang/renderpearl/api/device/GpuSurface",
                    name = "close",
                    desc = "()V",
                ) {
                    raw { m, _ ->
                        for (insn in m.instructions) {
                            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                                insn.owner == "com/mojang/renderpearl/api/device/GpuSurface" &&
                                insn.name == "close" &&
                                insn.desc == "()V"
                            ) {
                                // The receiver is already on the stack; a static helper with it as
                                // the only parameter keeps the frame shape unchanged.
                                insn.setOpcode(org.objectweb.asm.Opcodes.INVOKESTATIC)
                                insn.owner = "org/ohmyloader/adapter/WindowHooks"
                                insn.name = "surfaceClose"
                                insn.desc = "(Lcom/mojang/renderpearl/api/device/GpuSurface;)V"
                                insn.itf = false
                            }
                        }
                        add(org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP))
                    }
                }
                require(1)
            }
            // The client loop has stopped; the integrated server may still be saving. Wait for its
            // shutdown before the supervisor starts the next session over the same world directory.
            method("close", desc = "()V") {
                atHead { call("org/ohmyloader/adapter/EventBridge", "onGameClosing", "()V") }
            }
            // SDL shutdown for the window lease. This is the only SDL_Quit site and both the normal
            // and exceptional close paths carry it, hence require(2). The old session's quit is
            // skipped while the supervisor holds the window for the next session.
            method("close", desc = "()V") {
                // A Redirect/WrapOperation over this static no-arg void call generates an invalid
                // operand stack in the engine; replace the call itself instead. The helper has the
                // same ()V shape, so no stack or frame changes are needed.
                beforeCall(owner = "org/lwjgl/sdl/SDLInit", name = "SDL_Quit", desc = "()V") {
                    raw { m, _ ->
                        for (insn in m.instructions) {
                            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                                insn.owner == "org/lwjgl/sdl/SDLInit" &&
                                insn.name == "SDL_Quit" &&
                                insn.desc == "()V"
                            ) {
                                insn.owner = "org/ohmyloader/adapter/WindowHooks"
                                insn.name = "quit"
                            }
                        }
                        // A no-op marks the method as changed so the rewrite is written back.
                        add(org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP))
                    }
                }
                require(2)
            }
            method("close", desc = "()V") {
                // The backend that created the window must stay loaded while the window is leased;
                // its unload path is the SDL window owner. The new session's backend loads its own.
                beforeCall(
                    owner = "com/mojang/blaze3d/systems/RenderSystem",
                    name = "unloadTrackedBackendLibrary",
                    desc = "()V",
                ) {
                    raw { m, _ ->
                        for (insn in m.instructions) {
                            if (insn is org.objectweb.asm.tree.MethodInsnNode &&
                                insn.owner == "com/mojang/blaze3d/systems/RenderSystem" &&
                                insn.name == "unloadTrackedBackendLibrary" &&
                                insn.desc == "()V"
                            ) {
                                insn.owner = "org/ohmyloader/adapter/WindowHooks"
                                insn.name = "unloadBackend"
                            }
                        }
                        add(org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP))
                    }
                }
                require(2)
            }
        }
        classTarget("com/mojang/blaze3d/platform/Window") {
            // The SDL window is leased across a reboot. The first session records the handle that
            // GpuBackend.createWindow returned; the next session returns it without creating a second
            // window. createWindow is private and takes the backend/width/height/title; the GPU
            // surface is rebuilt separately by the new GpuDevice.createSurface(handle).
            method("createWindow", desc = "(Lcom/mojang/renderpearl/api/device/GpuBackend;IILjava/lang/String;)J") {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/WindowHooks",
                    method = "createWindow",
                    desc = "(Lcom/mojang/renderpearl/api/device/GpuBackend;IILjava/lang/String;Lorg/ohmyloader/api/core/hook/Operation;I)J",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
            // SDL's event queue is process state, not session state: a window event queued while the
            // retiring session was tearing down is delivered to the next one, and a size event there
            // silently redefines the adopted window. Traced on the lease path.
            //
            // This is the whole fix, and it is deliberately the only one: the event is dropped
            // *before* the game applies it, so the cache is never wrong in the first place. An
            // after-the-fact correction was tried and removed — re-deriving the size from SDL and
            // pushing it back through `onFramebufferResize` measured the native call wrong often
            // enough to write nonsense (a 1443037184-pixel-wide framebuffer) into the window, which
            // is a worse failure than the one it was meant to paper over.
            method("handleEvent", desc = "(Lorg/lwjgl/sdl/SDL_Event;)V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/WindowHooks", "onWindowEvent", "(Ljava/lang/Object;Ljava/lang/Object;)V",
                        args = listOf(DslValue.Arg(0), DslValue.This),
                    )
                }
            }
            // The old session's close must not destroy the leased window.
            method("close", desc = "()V") {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/WindowHooks",
                    method = "close",
                    desc = "(Lorg/ohmyloader/api/core/hook/Operation;I)V",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
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
                        owner = "org/ohmyloader/adapter/EventBridge",
                        method = "onFrameLimit",
                        desc = "(I)I",
                    )
                }
            }
        }
        classTarget("net/minecraft/core/registries/BuiltinRegistryBuilder") {
            // Registry freeze-point redirect: bootStrap runs as `createContents()` -> `freeze()` ->
            // `validate(REGISTRY)`. Redirecting the freeze inserts the mod content after vanilla
            // content exists but before the registry is closed for writes (and before validation) —
            // the window mod content must land in to be visible to the game. `freeze()` is a private
            // **instance** method here, so the handler receives the builder.
            method("bootStrap", desc = "()V") {
                redirectCall(
                    owner = "net/minecraft/core/registries/BuiltinRegistryBuilder",
                    name = "freeze",
                    desc = "()V",
                    handlerOwner = "org/ohmyloader/adapter/EventBridge",
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
                        "org/ohmyloader/adapter/ZstdRegionChunkFormat",
                        "onRegionFileVersionInitialized", "()V",
                    )
                }
            }
            method("getSelected", desc = "()Lnet/minecraft/world/level/chunk/storage/RegionFileVersion;") {
                atTail {
                    transformReturn(
                        owner = "org/ohmyloader/adapter/ZstdRegionChunkFormat",
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
        // Both reload paths — startup `Options.loadSelectedResourcePacks` and F3+T
        // `reloadResourcePacks` — reach `openAllSelected()` only *after* the selection list has been
        // applied from options, so the pack this hook adds is part of the reload that is asking for
        // it and is not overwritten afterwards.
        //
        // Mod commands: the dispatcher only leaves the Commands constructor as a constructor
        // argument and through the fromFullTree builds; ParseBox.fromFullTree receives the finished
        // dispatcher (vanilla registered) exactly once per construction, before its tree snapshot is
        // taken — so mod commands registered at its head are part of the tree. The SERVER half lives
        // in the server transformer.
        classTarget("net/minecraft/commands/ParseBox") {
            method(
                "fromFullTree",
                desc = "(Lcom/mojang/brigadier/CommandDispatcher;)Lnet/minecraft/commands/ParseBox;",
            ) {
                atHead {
                    call(
                        "org/ohmyloader/adapter/EventBridge",
                        "onDispatcherReady",
                        "(Lcom/mojang/brigadier/CommandDispatcher;)V",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
        }
        classTarget("net/minecraft/commands/Commands") {
            // Late declarations (the built-in `/oml` node, a mod's own nodes) land after the server's
            // Commands instance was built, so the execution entry point re-registers into the
            // dispatcher that instance parses with before the parse runs.
            method(
                "performPrefixedCommand",
                desc = "(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V",
            ) {
                atHead {
                    call(
                        "org/ohmyloader/adapter/OMLCommandBridge", "registerFor",
                        "(Lnet/minecraft/commands/Commands;)V",
                        args = listOf(DslValue.This),
                    )
                }
            }
        }
        classTarget("net/minecraft/server/packs/repository/PackRepository") {
            method("openAllSelected", desc = "()Ljava/util/List;") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/EventBridge", "onPackRepositoryReload", "(Ljava/lang/Object;)V",
                        args = listOf(DslValue.This),
                    )
                }
            }
        }
        classTarget("net/minecraft/client/multiplayer/ClientPacketListener") {
            // ChatSentEvent (cancelable): sendChat head, message = Arg(1)
            method("sendChat", desc = "(Ljava/lang/String;)V") {
                atHead {
                    cancelableCall(
                        "org/ohmyloader/adapter/EventBridge", "onChatSent", "(Ljava/lang/String;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            // ChatReceivedEvent (cancelable): the three inbound chat packet handlers, packet = Arg(0).
            // Each handler re-dispatches itself: PacketUtils.ensureRunningOnSameThread is its first call
            // and on the netty thread re-schedules the packet and throws (caught by the network loop),
            // so the anchor sits AFTER that call — a HEAD hook would fire once per message on the netty
            // thread and again on the game thread.
            method("handleSystemChat", desc = "(Lnet/minecraft/network/protocol/game/ClientboundSystemChatPacket;)V") {
                afterCall(
                    owner = "net/minecraft/network/protocol/PacketUtils",
                    name = "ensureRunningOnSameThread",
                    opcode = org.objectweb.asm.Opcodes.INVOKESTATIC,
                ) {
                    cancelableCall(
                        "org/ohmyloader/adapter/EventBridge", "onChatReceived",
                        "(Lnet/minecraft/network/protocol/Packet;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            method("handlePlayerChat", desc = "(Lnet/minecraft/network/protocol/game/ClientboundPlayerChatPacket;)V") {
                afterCall(
                    owner = "net/minecraft/network/protocol/PacketUtils",
                    name = "ensureRunningOnSameThread",
                    opcode = org.objectweb.asm.Opcodes.INVOKESTATIC,
                ) {
                    cancelableCall(
                        "org/ohmyloader/adapter/EventBridge", "onChatReceived",
                        "(Lnet/minecraft/network/protocol/Packet;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
            method(
                "handleDisguisedChat",
                desc = "(Lnet/minecraft/network/protocol/game/ClientboundDisguisedChatPacket;)V",
            ) {
                afterCall(
                    owner = "net/minecraft/network/protocol/PacketUtils",
                    name = "ensureRunningOnSameThread",
                    opcode = org.objectweb.asm.Opcodes.INVOKESTATIC,
                ) {
                    cancelableCall(
                        "org/ohmyloader/adapter/EventBridge", "onChatReceived",
                        "(Lnet/minecraft/network/protocol/Packet;)Z",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
        }
        classTarget("net/minecraft/client/Options") {
            // Mod key bindings: the array is `public final`, and a final-field putfield is only
            // legal from the declaring class, so the bits are loosened for the bridge's reassignment
            // (same pattern as `proxy`). The append itself must NOT happen at construction:
            // Options.<init> runs before mod declarations are collected, so
            // OMLKeyBindingsBridge applies them lazily on the first client tick.
            field("keyMappings", desc = "[Lnet/minecraft/client/KeyMapping;") {
                makePublic()
                removeFinal()
                require(1)
            }
        }
        classTarget("net/minecraft/client/KeyboardHandler") {
            // KeyPressEvent. The int parameter is the action encoded by SDLEventHandler:
            // 1 = press, 0 = release, -1 = repeat. HEAD fires for every keyboard event, including
            // screen-open ones; the bridge re-applies vanilla's own gating (press action, no
            // open screen) before dispatching to declared bindings.
            method("keyPress", desc = "(JILnet/minecraft/client/input/KeyEvent;)V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/EventBridge", "onKeyPress",
                        "(JILnet/minecraft/client/input/KeyEvent;)V",
                        args = listOf(DslValue.Arg(0), DslValue.Arg(1), DslValue.Arg(2)),
                    )
                }
            }
        }
        classTarget("net/minecraft/client/gui/Hud") {
            // HudRenderEvent. extractRenderState is the HUD extraction pass; its only caller is
            // guarded by GameRenderState.shouldRenderLevel, so the hook fires exactly once per
            // frame and only while a world is loaded. Internal early-returns (loading screens, F1)
            // sit after HEAD, so the event means "the HUD pass begins", not "pixels will follow".
            method("extractRenderState", desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/EventBridge", "onHudRender",
                        "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
                        args = listOf(DslValue.Arg(0)),
                    )
                }
            }
        }
        classTarget("net/minecraft/client/gui/Gui") {
            // The reboot transition's only draw site, and the reason it is one screen instead of
            // three. `extractRenderState` builds the frame's whole GUI in a fixed order — the HUD,
            // then the vanilla overlay (the loading screen), then the screen (the level-loading
            // screen), then the saving indicator — so the tail of this method is the single point
            // that is above all of them. Painting there means the retiring session's "Saving world"
            // and the starting session's loading screens are all drawn underneath the transition,
            // and neither session has to suppress anything.
            //
            // The extractor is captured as a local rather than fetched from a field: it is created
            // inside this method and is not reachable from anywhere else.
            method("extractRenderState", desc = "(Lnet/minecraft/client/DeltaTracker;ZZ)V") {
                atTail {
                    call(
                        "org/ohmyloader/adapter/EventBridge", "onGuiTail",
                        "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
                        args = listOf(local(type = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;")),
                    )
                }
            }
        }
        classTarget("net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen") {
            // The tab-button sprites hold one entry per vanilla column (seven in each row), and the
            // lookup clamps the column with `sprites.length` as its upper bound rather than
            // `length - 1`. Both rows are full, so any mod tab necessarily sits past that range and the
            // screen throws ArrayIndexOutOfBoundsException while rendering it. Narrowing the bound to
            // the last entry keeps the button on the final vanilla sprite, which is still drawn with the
            // tab's own icon item.
            method(
                "extractTabButton",
                desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/world/item/CreativeModeTab;)V",
            ) {
                beforeCall(owner = "java/lang/Math", name = "clamp", desc = "(JII)I") {
                    modifyArg(
                        owner = "org/ohmyloader/adapter/OMLCreativeTabs",
                        method = "spriteBound",
                        desc = "(I)I",
                        index = 2,
                    )
                }
                // The point rewrites every clamp(III) call in the method, so pin the count to exactly
                // one: a second call site must fail install loudly, not take the narrowed bound too.
                require(1)
                allow(1)
            }
        }
        classTarget("net/minecraft/client/multiplayer/ClientLevel") {
            // The client's own level tick, so `Events.LEVEL_TICK` covers whichever side ticks; a handler
            // tells the two apart through `Level.isClientSide`.
            method("tick", desc = "(Ljava/util/function/BooleanSupplier;)V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/EventBridge", "onLevelTick",
                        "(Lnet/minecraft/world/level/Level;)V",
                        args = listOf(DslValue.This),
                    )
                }
                require(1)
            }
        }
        // The integrated server of a singleplayer game runs the server-thread rules in this process,
        // where only this batch is installed — see ServerEventRules for the shared declaration.
        serverEventRules()
    },
    id = "$idPrefix:minecraft-hooks",
)
