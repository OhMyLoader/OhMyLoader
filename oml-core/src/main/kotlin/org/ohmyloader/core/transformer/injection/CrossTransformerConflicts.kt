package org.ohmyloader.core.transformer.injection

import org.ohmyloader.core.transformer.ExclusiveClaim

/**
 * Cross-transformer exclusive-claim comparison. Each [Source] is one transformer's claims on the untouched
 * class; a site claimed by **two different transformers** would let the first one run and the second miss
 * silently, so it is reported before any transform starts.
 */
internal object CrossTransformerConflicts {

    class Source(val transformerId: String, val claims: List<ExclusiveClaim>)

    fun find(sources: List<Source>): List<String> {
        val groups = linkedMapOf<String, MutableList<Pair<String, ExclusiveClaim>>>()
        for (source in sources) {
            for (claim in source.claims) {
                val key = "${claim.methodName}${claim.methodDesc}#${claim.wholeMethod}#${claim.instructionIndex}"
                groups.getOrPut(key) { mutableListOf() } += source.transformerId to claim
            }
        }
        return groups.values.mapNotNull { claims ->
            val owners = claims.map { it.first }.distinct()
            if (owners.size < 2) return@mapNotNull null
            val (_, first) = claims.first()
            val site = if (first.wholeMethod) "whole method body" else "instruction ${first.instructionIndex}"
            "${first.methodName}${first.methodDesc} ($site): " +
                claims.joinToString("; ") { (id, claim) -> "[$id] ${claim.label}" }
        }
    }
}