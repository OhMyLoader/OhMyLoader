package org.ohmyloader.api

import org.ohmyloader.api.config.OMLConfig

/**
 * Context handed to a mod during initialization: its own metadata.
 * For event subscriptions, use [org.ohmyloader.api.event.Events] directly.
 */
class ModContext(
    val id: String,
    val name: String,
    val version: String,
    /** The mod's config file (`<gameDir>/config/<id>.toml`): define entries, then read values. */
    val config: OMLConfig,
)
