package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * Puts the Mods button on the two menus a player can reach before and inside a world.
 *
 * <p>The anchor is the **tail of `init()`**, not its head: both screens build their widgets inside
 * `init`, and a button added before that would be cleared again by the screen's own layout pass.
 * At the tail the vanilla rows all exist, so the button lands beside them, and every later `init`
 * (a window resize) rebuilds it together with the rest of the menu.
 *
 * <p>The handler receives `this` as a plain `Object` ([DslValue.This]) and decides what to do with
 * it — the rule has no game type in its payload beyond the screen instance the game already owns.
 */
class ModsMenuTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/client/gui/screens/TitleScreen") {
            method("init", desc = "()V") {
                atTail {
                    call(
                        "org/ohmyloader/adapter/v26_3/ModsMenuHook", "onScreenInit",
                        "(Ljava/lang/Object;)V",
                        listOf(DslValue.This),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/gui/screens/PauseScreen") {
            method("init", desc = "()V") {
                atTail {
                    call(
                        "org/ohmyloader/adapter/v26_3/ModsMenuHook", "onScreenInit",
                        "(Ljava/lang/Object;)V",
                        listOf(DslValue.This),
                    )
                }
                require(1)
            }
        }
    },
    id = "v26_3:mods-menu",
)
