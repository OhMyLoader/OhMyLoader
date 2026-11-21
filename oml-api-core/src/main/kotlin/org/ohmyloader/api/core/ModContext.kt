package org.ohmyloader.api.core

import org.ohmyloader.api.core.config.OMLConfig

/**
 * Context handed to a mod during initialization: its own metadata.
 * For event subscriptions, use [org.ohmyloader.api.event.Events] directly (the event
 * payloads are the game's own types, so they live in the version-facing module).
 */
class ModContext(
    val id: String,
    val name: String,
    val version: String,
    /** The mod's config file (`<gameDir>/config/<id>.toml`): define entries, then read values. */
    val config: OMLConfig,
)
