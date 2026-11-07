package org.ohmyloader.api26_3.event

import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.inventory.AbstractContainerMenu
import org.ohmyloader.api.event.Event

/**
 * A player is about to open a menu (cancelable): a chest screen, a machine's own interface, any
 * [net.minecraft.world.MenuProvider] the game or a mod offers. Canceling answers the game's
 * "nothing opened", so the click simply does nothing.
 */
class MenuOpenEvent(
    val player: ServerPlayer,
    /** The display name the menu would open under, as the game shows it. */
    val menuTitle: String,
) : Event() {
    override val cancelable = true
}

/**
 * A player closed a menu; [menu] is the container the player was using and is still readable —
 * closing a menu by opening another one fires this first, then [MenuOpenEvent].
 */
class MenuClosedEvent(
    val player: ServerPlayer,
    val menu: AbstractContainerMenu,
) : Event()
