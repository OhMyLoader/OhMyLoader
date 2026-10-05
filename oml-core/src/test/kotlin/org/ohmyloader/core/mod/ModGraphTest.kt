package org.ohmyloader.core.mod

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The dependency-graph contract: every problem (missing dependency, violated version constraint,
 * duplicate mod id, dependency cycle) is collected into one readable error, and the surviving
 * order is a deterministic topological sort that puts dependents after their dependencies.
 */
class ModGraphTest {

    private fun mod(id: String, version: String = "1.0.0", vararg deps: String) =
        ModContainer(id, id, version, "org/example/$id", File("$id.jar"), deps.map(DependencySpec::parse))

    @Test
    fun `dependents initialize after their dependencies`() {
        val order = ModGraph.order(listOf(mod("a"), mod("b", deps = arrayOf("a")), mod("c", deps = arrayOf("b"))))
        assertEquals(listOf("a", "b", "c"), order.map { it.id })
    }

    @Test
    fun `unrelated mods keep their scan order`() {
        val order = ModGraph.order(listOf(mod("x"), mod("a", deps = arrayOf("x")), mod("y")))
        assertEquals(listOf("x", "a", "y"), order.map { it.id })
    }

    @Test
    fun `every problem is collected into one error`() {
        val error = assertFailsWith<IllegalStateException> {
            ModGraph.order(
                listOf(
                    mod("lonely", deps = arrayOf("absent")),
                    mod("mismatched", deps = arrayOf("old@>=2.0")),
                    mod("old", version = "1.0"),
                    mod("dup"),
                    mod("dup"),
                )
            )
        }
        val message = error.message!!
        assertTrue("depends on [absent], which is not loaded" in message, "actual:\n$message")
        assertTrue("depends on [old@>=2.0], but the loaded version of [old] is 1.0" in message, "actual:\n$message")
        assertTrue("duplicate mod id [dup]" in message)
        assertTrue(message.lines().size >= 4, "all problems must be listed, not just the first")
    }

    @Test
    fun `a dependency cycle names the stuck mods`() {
        val error = assertFailsWith<IllegalStateException> {
            ModGraph.order(
                listOf(mod("a", deps = arrayOf("b")), mod("b", deps = arrayOf("c")), mod("c", deps = arrayOf("a")))
            )
        }
        assertTrue("dependency cycle" in error.message!!)
        assertTrue("a" in error.message!! && "b" in error.message!! && "c" in error.message!!)
    }

    @Test
    fun `version constraints are checked against the loaded version`() {
        // equal passes, bounds pass, exact bound fails only on the wrong side
        val ok = ModGraph.order(
            listOf(
                mod("lib", version = "1.2.3"),
                mod("app", deps = arrayOf("lib@>=1.2.0", "lib@<2.0")),
            )
        )
        assertEquals(listOf("lib", "app"), ok.map { it.id })

        val error = assertFailsWith<IllegalStateException> {
            ModGraph.order(
                listOf(mod("lib", version = "2.0.0"), mod("app", deps = arrayOf("lib@<2.0")))
            )
        }
        assertTrue("lib@<2.0" in error.message!!, "actual:\n${error.message}")
    }

    @Test
    fun `constraint syntax errors fail the declaration, not the boot`() {
        // parse errors throw at declaration (scanner) time with the offending entry named
        val error = assertFailsWith<IllegalStateException> { DependencySpec.parse("other@~1.2") }
        assertTrue("no readable constraint" in error.message!!, "actual: ${error.message}")
        val bare = assertFailsWith<IllegalStateException> { DependencySpec.parse("other@") }
        assertTrue("constraint with no version" in bare.message!!)
    }

    @Test
    fun `dotted versions compare numerically, not lexically`() {
        assertTrue(DependencySpec.compareVersions("1.10.0", "1.9.0") > 0, "1.10 must beat 1.9")
        assertEquals(0, DependencySpec.compareVersions("1.2", "1.2.0"), "missing segments count as 0")
        assertTrue(DependencySpec.compareVersions("1.2.0", "1.2.1") < 0)
    }

    @Test
    fun `constraint satisfaction covers every operator`() {
        val atLeast = DependencySpec.parse("x@>=1.0")
        assertTrue(atLeast.satisfiedBy("1.0") && atLeast.satisfiedBy("2.0"))
        assertTrue(!atLeast.satisfiedBy("0.9"))
        val equal = DependencySpec.parse("x@=1.0")
        assertTrue(equal.satisfiedBy("1.0") && !equal.satisfiedBy("1.0.1"))
        val any = DependencySpec.parse("x")
        assertTrue(any.satisfiedBy("0.0.1") && any.satisfiedBy("99.0"), "a bare mod id accepts any version")
    }
}
