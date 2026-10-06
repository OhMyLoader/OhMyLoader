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
    }

    classTarget("net/minecraft/server/level/ServerPlayerGameMode") {
        // The game keeps `player` protected, so the break handler cannot read its own player off the
        // instance — the constructor argument is public, and the bridge records the association from here.
        constructor(desc = "(Lnet/minecraft/server/level/ServerPlayer;)V") {
            atConstructorHead {
                call(
                    BRIDGE, "onGameModeCreated",
                    "(Lnet/minecraft/server/level/ServerPlayerGameMode;Lnet/minecraft/server/level/ServerPlayer;)V",
                    args = listOf(DslValue.This, DslValue.Arg(0)),
                )
            }
        }
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
        // Hooking the declaration in LivingEntity is what makes this fire exactly once per damage
        // application: ServerPlayer and Player override it, each override super-calls up the chain, and
        // the chain ends here. An override that returns before reaching the base (creative,
        // invulnerable) fires nothing — vanilla was not going to apply that damage either.
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
