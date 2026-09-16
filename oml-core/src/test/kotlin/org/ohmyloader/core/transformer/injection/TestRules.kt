package org.ohmyloader.core.transformer.injection

import org.ohmyloader.api.inject.InjectionBuilder
import org.ohmyloader.api.inject.RuleSet
import org.ohmyloader.api.inject.injection

/**
 * Test convenience entry point: the DSL produces a rule set ([RuleSet]), while unit tests
 * drive the engine-side `transform` / `verify` directly, so a container wrapper is provided.
 * The production path uses the same wrapping via `InjectingTransformer` (which is itself built
 * from a rule set).
 */
internal fun specOf(block: InjectionBuilder.() -> Unit): InjectionSpec = InjectionSpec(injection(block))
