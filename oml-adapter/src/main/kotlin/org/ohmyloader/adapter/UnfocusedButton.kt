package org.ohmyloader.adapter

import net.minecraft.client.gui.components.Button
import net.minecraft.network.chat.Component

/**
 * A plain vanilla button ([Button.Plain]) whose white focus border is hover-only. The vanilla
 * click loop asks every clicked widget [Button.shouldTakeFocusAfterInteraction] before marking
 * it focused, and this one answers "no", so a press never sticks the border: it shows while the
 * mouse hovers and disappears when the mouse leaves — the vanilla menu look — instead of
 * lingering on the last pressed button. Keyboard focus navigation is unaffected.
 */
class UnfocusedButton(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    message: Component,
    onPress: (Button) -> Unit,
) : Button.Plain(x, y, width, height, message, Button.OnPress { onPress(it) }, Button.CreateNarration { it.get() }) {
    override fun shouldTakeFocusAfterInteraction(): Boolean = false
}
