package org.ohmyloader.adapter.common

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.InjectionBuilder

/** The bridge every handler below lives on: injected bytecode targets it by fixed owner+name+desc. */
private const val BRIDGE = "org/ohmyloader/adapter/common/EventBridge"

/** The `CallbackInfoReturnable` parameter type, the tail of every `cancellableReturn` bridge descriptor. */
private const val CIR = "Lorg/ohmyloader/api/mixin/CallbackInfoReturnable;"

/**
 * Server-thread lifecycle and interaction rules, declared **once** and added to both injection
 * batches: a singleplayer game ticks an integrated `MinecraftServer` inside the client process, where
 * only [MinecraftHookTransformer] is installed, while a dedicated server installs only
 * [ServerHookTransformer] — a rule in one batch simply does not exist on the other side. Installing
 * both batches on the client is not the alternative: the shared bootstrap rules
 * (`BuiltInRegistries`, `PackRepository`, `RegionFileVersion`) would then inject twice into the same class.
 */
internal fun InjectionBuilder.serverEventRules() {
    classTarget("net/minecraft/server/MinecraftServer") {
        // One dispatch per logic tick of the server thread. "Started" is derived from this hook rather
        // than anchored on `loadLevel()`: that method exists on MinecraftServer but nothing in the game
        // calls it on itself (subclasses drive it), so a rule there can miss. The core fires
        // SERVER_STARTED on the first tick of each server instance, which also re-arms it when the
        // client leaves one world and starts another integrated server.
        method("tickServer", desc = "(Ljava/util/function/BooleanSupplier;)V") {
            atHead {
                call(
                    BRIDGE, "onServerTick", "(Lnet/minecraft/server/MinecraftServer;)V",
                    args = listOf(DslValue.This),
                )
            }
            require(1)
        }
        // Fires once per shutdown, before the worlds are saved and the loop exits.
        method("stopServer", desc = "()V") {
            atHead {
                call(
                    BRIDGE, "onServerStopping", "(Lnet/minecraft/server/MinecraftServer;)V",
                    args = listOf(DslValue.This),
                )
            }
            require(1)
        }
    }

    classTarget("net/minecraft/server/level/ServerLevel") {
        // HEAD, once per loaded dimension per server tick: the handler's work lands before the level's own
        // entity and block-entity passes, and `Events.LEVEL_TICK` carries this level, so mod work can be
        // scoped to one world instead of to the whole server.
        method("tick", desc = "(Ljava/util/function/BooleanSupplier;)V") {
            atHead {
                call(
                    BRIDGE, "onLevelTick", "(Lnet/minecraft/world/level/Level;)V",
                    args = listOf(DslValue.This),
                )
            }
            require(1)
        }
        // The chunk reached its ticking status: `ChunkMap` calls this right after the level's own
        // post-generation pass, with the `LevelChunk` that is now live in this dimension. HEAD because the
        // body registers the chunk's block entities, and a handler that keeps per-chunk state wants to be
        // able to see them.
        method("startTickingChunk", desc = "(Lnet/minecraft/world/level/chunk/LevelChunk;)V") {
            atHead {
                call(
                    BRIDGE, "onChunkStartedTicking",
                    "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
                    args = listOf(DslValue.This, DslValue.Arg(0)),
                )
            }
            require(1)
        }
        // The chunk is being dropped. HEAD, not tail: the body clears the chunk's block entities and its
        // entity-section link, so by the tail there is nothing left for a handler to read.
        method("unload", desc = "(Lnet/minecraft/world/level/chunk/LevelChunk;)V") {
            atHead {
                call(
                    BRIDGE, "onChunkUnloaded",
                    "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
                    args = listOf(DslValue.This, DslValue.Arg(0)),
                )
            }
            require(1)
        }
    }

    classTarget("net/minecraft/server/level/ServerPlayer") {
        // The one funnel every consumer of a player's authority reads through — chat gates, command gates,
        // `/oml`-style checks. TAIL with a return transform: the game builds the level-based set itself, and
        // OML hands back either that very object (nothing granted: identity, no allocation) or a wrapper over
        // it. A wrapper is safe because the callers only ever invoke `PermissionSet.hasPermission`; nothing
        // casts the result back to `LevelBasedPermissionSet`.
        method("permissions", desc = "()Lnet/minecraft/server/permissions/PermissionSet;") {
            atTail {
                transformReturn(
                    BRIDGE, "onPlayerPermissions",
                    "(Lnet/minecraft/server/permissions/PermissionSet;" +
                        "Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/server/permissions/PermissionSet;",
                    extras = listOf(DslValue.This),
                )
            }
            require(1)
        }
    }

    classTarget("net/minecraft/server/players/PlayerList") {
        // Tail, not head: only after the whole body has run is the player in the list with its initial
        // packets sent, which is what "joined" has to mean for a mod reading the player from here.
        method(
            "placeNewPlayer",
            desc = "(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
        ) {
            atTail {
                call(
                    BRIDGE, "onPlayerJoined", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                    args = listOf(DslValue.Arg(1)),
                )
            }
            require(1)
        }
        // Head: the argument is still fully readable here, and this is the last point where a handler
        // can act on a player that is leaving.
        method("remove", desc = "(Lnet/minecraft/server/level/ServerPlayer;)V") {
            atHead {
                call(
                    BRIDGE, "onPlayerLeft", "(Lnet/minecraft/server/level/ServerPlayer;)V",
                    args = listOf(DslValue.Arg(0)),
                )
            }
            require(1)
        }
        // TAIL with a return-value transform: `respawn` builds a **new** ServerPlayer for the life that is
        // starting and returns it, while its argument is the instance being discarded — a HEAD hook could
        // only hand mods a dead entity. The handler gives the same object back, so the game's own return
        // path is untouched.
        method(
            "respawn",
            desc = "(Lnet/minecraft/server/level/ServerPlayer;ZLnet/minecraft/world/entity/Entity" +
                "\$RemovalReason;)Lnet/minecraft/server/level/ServerPlayer;",
        ) {
            atTail {
                transformReturn(
                    BRIDGE, "onPlayerRespawned",
                    "(Lnet/minecraft/server/level/ServerPlayer;)Lnet/minecraft/server/level/ServerPlayer;",
                )
            }
            require(1)
        }
    }

    classTarget("net/minecraft/server/level/ServerPlayerGameMode") {
        // The single funnel for both break paths: creative `destroyAndAck` calls this directly, the
        // survival delayed break completes inside it via `tick()`. Its return value is the resync
        // signal — false makes `destroyAndAck` send the block back to the client, so a cancelled break
        // snaps the block into place again without the mod sending anything.
        method("destroyBlock", desc = "(Lnet/minecraft/core/BlockPos;)Z") {
            atHead {
                cancellableReturn(
                    BRIDGE, "onBlockBreak",
                    "(Lnet/minecraft/server/level/ServerPlayerGameMode;Lnet/minecraft/core/BlockPos;I$CIR)V",
                    bridgeArgs = listOf(DslValue.This, DslValue.Arg(0), DslValue.IntVal(0)),
                )
            }
            require(1)
        }
        // The two right-click paths of the server thread, split by what the click hit. Neither method
        // calls the other, so one click dispatches one event.
        method(
            "useItemOn",
            desc = "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;" +
                "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;" +
                "Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;",
        ) {
            atHead {
                cancellableReturn(
                    BRIDGE, "onBlockUsed",
                    "(Lnet/minecraft/server/level/ServerPlayerGameMode;Lnet/minecraft/server/level/ServerPlayer;" +
                        "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;" +
                        "Lnet/minecraft/world/phys/BlockHitResult;I$CIR)V",
                    bridgeArgs = listOf(
                        DslValue.This, DslValue.Arg(0), DslValue.Arg(2), DslValue.Arg(3), DslValue.Arg(4),
                        DslValue.IntVal(0),
                    ),
                )
            }
            require(1)
        }
        method(
            "useItem",
            desc = "(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;" +
                "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)" +
                "Lnet/minecraft/world/InteractionResult;",
        ) {
            atHead {
                cancellableReturn(
                    BRIDGE, "onItemUsed",
                    "(Lnet/minecraft/server/level/ServerPlayerGameMode;Lnet/minecraft/server/level/ServerPlayer;" +
                        "Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;I$CIR)V",
                    bridgeArgs = listOf(
                        DslValue.This, DslValue.Arg(0), DslValue.Arg(2), DslValue.Arg(3), DslValue.IntVal(0),
                    ),
                )
            }
            require(1)
        }
    }

    classTarget("net/minecraft/world/entity/player/Player") {
        // Cancelling skips the entire swing: the damage computation, knockback, crit, sound and the
        // arm animation all sit after HEAD.
        method("attack", desc = "(Lnet/minecraft/world/entity/Entity;)V") {
            atHead {
                cancellableCall(
                    BRIDGE, "onEntityAttack",
                    "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)Z",
                    args = listOf(DslValue.This, DslValue.Arg(0)),
                )
            }
            require(1)
        }
        // Both sides run this method in a singleplayer game (the client's own player and the server's),
        // so the bridge dispatches only the server-side call — see [EventBridge.onEntityInteract].
        method(
            "interactOn",
            desc = "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/InteractionResult;",
        ) {
            atHead {
                cancellableReturn(
                    BRIDGE, "onEntityInteract",
                    "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/InteractionHand;I$CIR)V",
                    bridgeArgs = listOf(DslValue.This, DslValue.Arg(0), DslValue.Arg(1), DslValue.IntVal(0)),
                )
            }
            require(1)
        }
    }

    classTarget("net/minecraft/world/entity/LivingEntity") {
        // Declared **before** the HEAD dispatch on purpose: rules apply in declaration order to the
        // instruction list the previous rule left behind, so with the HEAD hook first, its own argument
        // loads of the damage parameter would sit ahead of vanilla's first read and `ordinal = 0` would
        // rewrite the hook's load rather than the game's — the number a handler wrote would never reach
        // the damage. Vanilla first reads the parameter after its invulnerability, dying and
        // fire-resistance guards; the engine writes the returned number back into that slot, so every
        // later read in the same call (shield reduction, damage cooldown, the damage itself) uses it.
        method(
            "hurtServer",
            desc = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
        ) {
            beforeLoad(index = 3, type = "F", argsOnly = true, ordinal = 0) {
                modifyVariable(
                    BRIDGE, "onHurtAmount",
                    "(FLnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)F",
                    extras = listOf(DslValue.This, DslValue.Arg(1)),
                )
            }
            require(1)
        }
        // Hooking the declaration in LivingEntity is what makes this fire exactly once per damage
        // application: `ServerPlayer` and `Player` both override it, each override super-calls up the
        // chain, and the chain ends here. An override that returns before reaching the base (a creative
        // player, an invulnerable entity) fires nothing — vanilla was not going to apply that damage
        // either.
        method(
            "hurtServer",
            desc = "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z",
        ) {
            atHead {
                cancellableReturn(
                    BRIDGE, "onLivingHurt",
                    "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;FI$CIR)V",
                    bridgeArgs = listOf(DslValue.This, DslValue.Arg(1), DslValue.Arg(2), DslValue.IntVal(0)),
                )
            }
            require(1)
        }
        method("die", desc = "(Lnet/minecraft/world/damagesource/DamageSource;)V") {
            atHead {
                cancellableCall(
                    BRIDGE, "onLivingDied",
                    "(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)Z",
                    args = listOf(DslValue.This, DslValue.Arg(0)),
                )
            }
            require(1)
        }
    }
}
