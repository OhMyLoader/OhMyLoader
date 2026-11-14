package org.ohmyloader.adapter.common

import org.ohmyloader.api.inject.DslValue

/**
 * How this line exposes the finished command dispatcher to the bridge — the fact the command
 * registration rules branch on (see [HookShapes.commandHandover]).
 */
enum class CommandHandover {
    /**
     * The dispatcher is a field on the `Commands` instance behind `getDispatcher()`, so the
     * handover hooks sit on `performPrefixedCommand` and `MinecraftServer.getCommands` and name the
     * 26.3 adapter's Commands-shaped handlers (`V26_3EventBridge`) — reading the field from shared
     * bytecode would put a snapshot-unresolvable reference into the layer every install ships.
     */
    COMMANDS_INSTANCE,

    /**
     * The dispatcher is private to the `CommandBox` hierarchy and only leaves the `Commands`
     * constructor through the caller consumer and the `SuggestionBox` / `ParseBox.fromFullTree`
     * builds, so the handover hook sits on `ParseBox.fromFullTree` — every `Commands` construction
     * funnels through it with the finished dispatcher (vanilla included) as its first argument.
     */
    PARSE_BOX,
}

/**
 * The anchor shapes the shared hook declarations branch on: the game lines this repository tracks
 * (the 26.3 stable line and the tracked 26.4 snapshot) differ in exactly these four sites — the
 * Hud extraction pass, the creative-tab sprite bound, the registry freeze and the command
 * dispatcher handover. Each adapter passes the facts for its own versionId, so when the snapshot
 * line drifts again only the snapshot adapter's facts move.
 */
data class HookShapes(
    /** `Hud.extractRenderState`: the method descriptor as this line's jar declares it. */
    val hudExtractDesc: String,
    /** The `EventBridge.onHudRender` overload matching [hudExtractDesc]'s parameter list. */
    val onHudRenderDesc: String,
    /** The bridge arguments for the HUD hook, aligned with [onHudRenderDesc]. */
    val hudRenderArgs: List<DslValue>,
    /** `CreativeModeInventoryScreen.extractTabButton`: the descriptor of the tab-button pass. */
    val tabButtonDesc: String,
    /** The clamp call the sprite-bound rule narrows: owner and descriptor in this line's jar. */
    val tabClampOwner: String,
    val tabClampDesc: String,
    /** The class hosting `bootStrap()`, the anchor of the registry-freeze redirect. */
    val freezeAnchorClass: String,
    /** The owner of the `freeze` call the redirect replaces. */
    val freezeCallOwner: String,
    /**
     * Where the redirect handler lives. 26.3 freezes through a private static call, so the shared
     * [EventBridge] handles it; the snapshot line freezes through a private **instance** method on
     * `BuiltinRegistryBuilder`, a class absent from the 26.3 jar — a shared handler naming it would
     * put a 26.3-unresolvable game reference into a layer that ships into 26.3 installs, so the
     * instance-form handler lives in the snapshot module.
     */
    val freezeHandlerOwner: String,
    /** The redirect handler's name; on the instance form the builder arrives as the first parameter. */
    val freezeHandlerMethod: String,
    /** How the finished command dispatcher reaches [EventBridge] / the version bridge. */
    val commandHandover: CommandHandover,
)
