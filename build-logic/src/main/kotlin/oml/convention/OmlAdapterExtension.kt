package oml.convention

import org.gradle.api.provider.Property

/**
 * The per-version facts an adapter module declares. Created once the convention plugin
 * `oml.convention.oml-adapter` is applied.
 *
 * The adapter is a *driver*: it tells the runtime how to start one game version and which bytecode
 * transformers that version needs. It does not run the game — launching lives in the `oml-gradle`
 * plugin, the single launch interface of this repository.
 */
interface OmlAdapterExtension {
    /** Version id, e.g. `26.3`; also the version the installer and launcher are pointed at. */
    val versionId: Property<String>
}