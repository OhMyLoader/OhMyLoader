package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The reboot bill's game-side probes: entry/exit marks on the bootstrap, device and reload sites
 * the flow runs through, so every stage of the timeline comes from the game's own code rather
 * than being inferred from log lines. The marks themselves are measurement only — the injected
 * payload is a static call into [RebootProbe], which relays to the supervisor loader's stopwatch,
 * and nothing reads them to make decisions.
 *
 * <p>The one rule here that changes behaviour is `friendsDisabled`: `Minecraft.<init>` joins a
 * session-server future for the friends flag, and that join is a pure network wait on the boot
 * path (see the handler).
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
        classTarget("net/minecraft/client/renderer/ShaderManager") {
            method(
                "reload",
                desc = "(Lnet/minecraft/server/packs/resources/PreparableReloadListener\$SharedState;" +
                    "Ljava/util/concurrent/Executor;" +
                    "Lnet/minecraft/server/packs/resources/PreparableReloadListener\$PreparationBarrier;" +
                    "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            ) {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/v26_3/RebootProbe",
                    method = "shaderReload",
                    desc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;" +
                        "Lorg/ohmyloader/api/hook/Operation;I)Ljava/util/concurrent/CompletableFuture;",
                    extras = listOf(DslValue.IntVal(0)),
                    coerceParams = setOf(0, 1, 2, 3),
                )
                require(1)
            }
        }
        // The two ~2 s blacks of the phase-5 bill. `Minecraft.<init>` dispatches the initial resource
        // reload directly through `createReload`, so the gap between the GL device and the shader
        // manager's reload splits there; `openWorld` and `setLevel` split the world-open stretch into
        // "read the save / start the server" and "wait for the first player tick".
        classTarget("net/minecraft/server/packs/resources/ReloadableResourceManager") {
            method(
                "createReload",
                desc = "(Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;" +
                    "Ljava/util/concurrent/CompletableFuture;Ljava/util/List;)" +
                    "Lnet/minecraft/server/packs/resources/ReloadInstance;",
            ) {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.resourceReload.begin")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/gui/screens/worldselection/WorldOpenFlows") {
            method("openWorld", desc = "(Ljava/lang/String;Ljava/lang/Runnable;)V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.openWorld.begin")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/Minecraft") {
            method("friendsEnabled", desc = "()Z") {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/v26_3/RebootProbe",
                    method = "friendsDisabled",
                    desc = "(Lorg/ohmyloader/api/hook/Operation;I)Z",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
            method("allowFriendRequests", desc = "()Z") {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/v26_3/RebootProbe",
                    method = "friendsDisabled",
                    desc = "(Lorg/ohmyloader/api/hook/Operation;I)Z",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
            method("setLevel", desc = "(Lnet/minecraft/client/multiplayer/ClientLevel;)V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.level.set")),
                    )
                }
                require(1)
            }
            // `loadCriticalShaders` is the only synchronous shader work in the constructor: it
            // installs the fallback pipeline cache and compiles the UI pipelines the loading screen
            // needs. It, the include listing it performs and `preloadUiShader` split the phase-5
            // black box between the GL device and the resource-reload dispatch.
            method("loadCriticalShaders", desc = "()V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.criticalShaders.begin")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/renderer/GameRenderer") {
            method(
                "preloadUiShader",
                desc = "(Lnet/minecraft/server/packs/resources/ResourceManager;)V",
            ) {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.uiShader.begin")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/renderer/ShaderManager") {
            method(
                "listAllIncludes",
                desc = "(Lnet/minecraft/server/packs/resources/ResourceManager;)Ljava/util/Map;",
            ) {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.includes.begin")),
                    )
                }
                require(1)
            }
        }
        // The rest of `Minecraft.<init>`, between the GL device and the critical shaders, is the
        // phase-5 black box. These five constructions are the heavy candidates; each mark is the
        // moment the constructor is entered, so the delta from the previous mark is the cost of
        // whatever was being built before it.
        classTarget("net/minecraft/client/renderer/block/BlockModelResolver") {
            method("<init>", desc = "(Lnet/minecraft/client/resources/model/ModelManager;)V") {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.blockModels")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/renderer/item/ItemModelResolver") {
            method("<init>", desc = "(Lnet/minecraft/client/resources/model/ModelManager;)V") {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.itemModels")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/renderer/entity/EntityRenderDispatcher") {
            method(
                "<init>",
                desc = "(Lnet/minecraft/client/renderer/texture/TextureManager;" +
                    "Lnet/minecraft/client/renderer/block/BlockModelResolver;" +
                    "Lnet/minecraft/client/renderer/item/ItemModelResolver;" +
                    "Lnet/minecraft/client/renderer/MapRenderer;" +
                    "Lnet/minecraft/client/resources/model/sprite/AtlasManager;" +
                    "Lnet/minecraft/client/gui/Font;" +
                    "Lnet/minecraft/client/Options;" +
                    "Ljava/util/function/Supplier;" +
                    "Lnet/minecraft/client/resources/model/EquipmentAssetManager;" +
                    "Lnet/minecraft/client/renderer/PlayerSkinRenderCache;" +
                    "Lnet/minecraft/client/resources/palette/PalettedTextureManager;)V",
            ) {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.entityRenderers")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/renderer/GameRenderer") {
            method(
                "<init>",
                desc = "(Lnet/minecraft/client/Minecraft;" +
                    "Lnet/minecraft/client/renderer/FirstPersonHandsAndItemsRenderer;" +
                    "Lnet/minecraft/client/resources/model/ModelManager;" +
                    "Lnet/minecraft/client/renderer/item/ItemModelResolver;)V",
            ) {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.gameRenderer")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/renderer/LevelRenderer") {
            method(
                "<init>",
                desc = "(Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;" +
                    "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;" +
                    "Lnet/minecraft/client/resources/model/ModelManager;" +
                    "Lnet/minecraft/client/renderer/texture/TextureManager;" +
                    "Lnet/minecraft/client/resources/model/sprite/AtlasManager;" +
                    "Lnet/minecraft/client/renderer/ShaderManager;" +
                    "Lnet/minecraft/client/renderer/GameRenderer;II)V",
            ) {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.levelRenderer")),
                    )
                }
                require(1)
            }
        }
        // The 1.6 s inside that window. `PalettedTextureManager`, `MapRenderer` and the friends-list
        // service stand between the two model resolvers and the entity renderers; the service is the
        // only network-touching one, so its marks separate "local construction" from "waiting on
        // authlib".
        classTarget("net/minecraft/client/resources/palette/PalettedTextureManager") {
            method(
                "<init>",
                desc = "(Lnet/minecraft/server/packs/resources/ResourceManager;" +
                    "Lnet/minecraft/client/renderer/texture/TextureManager;)V",
            ) {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.palettes")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/renderer/MapRenderer") {
            method(
                "<init>",
                desc = "(Lnet/minecraft/client/resources/model/sprite/AtlasManager;" +
                    "Lnet/minecraft/client/resources/MapTextureManager;)V",
            ) {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.mapRenderer")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/gui/screens/social/PlayerSocialManager") {
            method(
                "<init>",
                desc = "(Lnet/minecraft/client/Minecraft;" +
                    "Lcom/mojang/authlib/minecraft/UserApiService;" +
                    "Lcom/mojang/authlib/services/FriendsService;" +
                    "Lnet/minecraft/client/gui/screens/social/RemoteFriendListUpdateHandler;)V",
            ) {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.social")),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/gui/screens/social/RemoteFriendListUpdateHandler") {
            method(
                "<init>",
                desc = "(Lcom/mojang/authlib/services/FriendsService;" +
                    "Lnet/minecraft/client/Minecraft;)V",
            ) {
                atConstructorHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.friendHandler")),
                    )
                }
                require(1)
            }
            method("start", desc = "()V") {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/RebootProbe", "mark", "(Ljava/lang/String;)V",
                        listOf(DslValue.Str("game.ctor.friendStart")),
                    )
                }
                require(1)
            }
        }
        // The remaining shader path, as totals: spirv-cross decompile (compile executor) and the
        // synchronous GL link (render thread). Measurement only.
        classTarget("com/mojang/renderpearl/backend/opengl/GlProgram") {
            method(
                "link",
                desc = "(Ljava/util/List;Ljava/lang/String;)" +
                    "Lcom/mojang/renderpearl/backend/opengl/GlProgram;",
            ) {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/v26_3/RebootProbe",
                    method = "programLink",
                    desc = "(Ljava/lang/Object;Ljava/lang/Object;Lorg/ohmyloader/api/hook/Operation;I)" +
                        "Ljava/lang/Object;",
                    extras = listOf(DslValue.IntVal(0)),
                    coerceParams = setOf(0, 1),
                    coerceReturn = true,
                )
                require(1)
            }
        }
        classTarget("com/mojang/renderpearl/backend/opengl/GlPipelineRecompiler") {
            method(
                "decompileShaders",
                desc = "(Lcom/mojang/renderpearl/backend/api/BackendRenderPipeline\$CreateInfo;)Ljava/util/Map;",
            ) {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/v26_3/RebootProbe",
                    method = "decompileShaders",
                    desc = "(Ljava/lang/Object;Lorg/ohmyloader/api/hook/Operation;I)Ljava/lang/Object;",
                    extras = listOf(DslValue.IntVal(0)),
                    coerceParams = setOf(0),
                    coerceReturn = true,
                )
                require(1)
            }
        }
    },
    id = "v26_3:reboot-timing",
)
