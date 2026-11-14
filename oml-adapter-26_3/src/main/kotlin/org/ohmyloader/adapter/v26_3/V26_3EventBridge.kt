package org.ohmyloader.adapter.v26_3

import net.minecraft.commands.Commands
import org.ohmyloader.adapter.common.OMLCommandBridge

/**
 * The 26.3 half of the command-dispatcher handover: this line keeps the dispatcher on the
 * `Commands` instance, so the handover hooks pass the wrapper and this bridge unwraps it. The
 * unwrap names `Commands.getDispatcher`, which the tracked snapshot removed — that is why it lives
 * here and not in the shared [org.ohmyloader.adapter.common.EventBridge], whose bytecode ships
 * into snapshot installs too (the same layering the snapshot-side freeze handler uses).
 */
object V26_3EventBridge {

    /** [Commands handover] Registers the mod commands into the freshly built dispatcher. */
    @JvmStatic
    fun onCommandsReady(commands: Commands) = OMLCommandBridge.register(commands.dispatcher)

    /** Return-transform half of [onCommandsReady]: observes and passes the Commands instance through. */
    @JvmStatic
    fun onCommandsReadyReturn(commands: Commands): Commands {
        OMLCommandBridge.register(commands.dispatcher)
        return commands
    }
}
