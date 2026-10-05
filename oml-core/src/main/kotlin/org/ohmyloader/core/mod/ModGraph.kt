package org.ohmyloader.core.mod

/**
 * Validates the scanned mods against their declared dependencies and orders them for
 * initialization. [order] collects **every** problem in one pass (missing dependencies, violated
 * version constraints, duplicate mod ids, dependency cycles) and throws with the full list —
 * one broken mod should not make the others' problems invisible across repeated boots.
 *
 * The order is a Kahn topological sort: dependents initialize after their dependencies, and mods
 * with no relative constraint keep their scan order (a deterministic queue, not a set).
 */
object ModGraph {

    fun order(mods: List<ModContainer>): List<ModContainer> {
        val problems = mutableListOf<String>()

        val byId = LinkedHashMap<String, ModContainer>()
        for (mod in mods) {
            val existing = byId[mod.id]
            if (existing != null) {
                problems.add(
                    "duplicate mod id [${mod.id}]: loaded from both ${existing.file.name} and ${mod.file.name}"
                )
            } else {
                byId[mod.id] = mod
            }
        }

        for (mod in byId.values) {
            for (dep in mod.dependencies) {
                val required = byId[dep.modId]
                when {
                    required == null -> problems.add(
                        "mod [${mod.id}] (${mod.file.name}) depends on [${dep.display}], " +
                            "which is not loaded (installed mods: ${byId.keys.joinToString(", ")})"
                    )

                    !dep.satisfiedBy(required.version) -> problems.add(
                        "mod [${mod.id}] depends on [${dep.display}], " +
                            "but the loaded version of [${dep.modId}] is ${required.version}"
                    )
                }
            }
        }

        // Kahn over the dependency edges (mod -> its dependencies); insertion-ordered queue keeps
        // the scan order among unrelated mods, so the order is deterministic for a given scan.
        val inDegree = HashMap<String, Int>()
        val dependents = HashMap<String, MutableList<ModContainer>>()
        for (mod in byId.values) {
            inDegree.putIfAbsent(mod.id, 0)
            for (dep in mod.dependencies) {
                val required = byId[dep.modId] ?: continue
                inDegree.merge(mod.id, 1, Int::plus)
                dependents.getOrPut(required.id) { mutableListOf() }.add(mod)
            }
        }
        // Each round initializes the FIRST mod in scan order whose dependencies are all satisfied:
        // a plain FIFO would let an unrelated later mod jump ahead of a dependent whose dependency
        // just became ready, breaking the scan-order determinism this function promises.
        val remaining = byId.values.toMutableList()
        val ordered = mutableListOf<ModContainer>()
        while (remaining.isNotEmpty()) {
            val ready = remaining.firstOrNull { (inDegree[it.id] ?: 0) == 0 }
                ?: break // nothing ready: the rest is a cycle, reported below
            remaining.remove(ready)
            ordered += ready
            for (dependent in dependents[ready.id].orEmpty()) {
                inDegree.merge(dependent.id, -1, Int::plus)
            }
        }
        if (ordered.size < byId.size) {
            val stuck = byId.values.filter { it !in ordered }.map { it.id }
            problems.add(
                "dependency cycle through: ${stuck.joinToString(" -> ")} — " +
                    "every mod in the cycle depends (directly or transitively) on another one in it"
            )
        }

        if (problems.isNotEmpty()) {
            throw IllegalStateException(
                "mod loading cannot continue:\n" + problems.joinToString("\n") { "  - $it" }
            )
        }
        return ordered
    }
}
