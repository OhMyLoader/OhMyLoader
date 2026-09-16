package org.ohmyloader.api

/**
 * Context handed to a mod during initialization: its own metadata.
 * For event subscriptions, use [org.ohmyloader.api.event.Events] directly.
 */
class ModContext(
    val id: String,
    val name: String,
    val version: String,
)
