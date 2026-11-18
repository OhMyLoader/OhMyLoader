package org.ohmyloader.core.cache

/**
 * The transform cache surface the game loader consults before parsing and transforming a class.
 * Implementations must treat every failure as a miss; a cache must never fail a class load.
 */
interface TransformCache {
    fun get(namespace: String, internalName: String): ByteArray?
    fun put(namespace: String, internalName: String, bytes: ByteArray)

    /**
     * Drops stale fingerprint entries, keeping [namespace] and — for the soft-reboot mod-toggle
     * case (A → B → A) — the most recent previous fingerprint. Eviction is a disk-space concern
     * only, so a backend without a persistent tier (or a failed delete) does nothing.
     */
    fun prune(namespace: String) {}
}
