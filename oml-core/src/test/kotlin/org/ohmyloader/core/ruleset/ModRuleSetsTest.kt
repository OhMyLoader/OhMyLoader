package org.ohmyloader.core.ruleset

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*
import org.ohmyloader.core.mod.ModContainer
import org.ohmyloader.core.ruleset.fixtures.*
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Discovery / validation / evaluation of mod rule sets ([ModRuleSets]).
 *
 * This layer catches the kinds of mistakes that are necessarily missed at startup time: rule
 * classes are loaded **first**, so ① the discovery phase can only read bytecode; ② references to
 * game classes must be intercepted **before** loading (by the time a load happens it is already too
 * late — the game class would be pulled in, and as the unrewritten copy); ③ an exception thrown by
 * author code during evaluation must become a readable problem rather than blowing up the bootstrap
 * in someone else's exception stack. The cases walk all three stages through real temp jars —
 * discovery and evaluation both assume real bytecode, and building a ClassNode doesn't exercise
 * the evaluation stage (reflection needs a real class). The fixtures live in the `fixtures` package.
 */
class ModRuleSetsTest {

    private fun jarOf(vararg classes: Class<*>): File {
        val file = File.createTempFile("oml-rules-", ".jar")
        file.deleteOnExit()
        JarOutputStream(file.outputStream()).use { out ->
            for (type in classes) {
                val path = type.name.replace('.', '/') + ".class"
                val bytes = type.getResourceAsStream("/$path")?.readBytes()
                    ?: error("test fixture bytecode not on the classpath: $path")
                out.putNextEntry(JarEntry(path))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    private fun emptyJar(): File {
        val file = File.createTempFile("oml-empty-", ".jar")
        file.deleteOnExit()
        JarOutputStream(file.outputStream()).close()
        return file
    }

    private fun load(vararg classes: Class<*>): ModRuleSets.Loaded {
        val mod = ModContainer("fixture", "Fixture", "1.0", "x.Y", jarOf(*classes))
        return ModRuleSets.load(listOf(mod), javaClass.classLoader)
    }

    // ---------- discovery + evaluation ----------

    @Test
    fun `a rule source is discovered and its rules are collected`() {
        val loaded = load(OkRules::class.java)
        assertTrue(loaded.problems.isEmpty(), loaded.problems.toString())
        assertEquals(setOf("omltest/Target"), loaded.rules.targets)
        assertEquals(1, loaded.sources.size)
        val line = loaded.sources.single()
        assertTrue(line.contains("OkRules"), "startup log should name the contributor: $line")
        assertTrue(line.contains("1 rules"), line)
    }

    @Test
    fun `a jar without rule sources yields nothing and no problems`() {
        val mod = ModContainer("empty", "Empty", "1.0", "x.Y", emptyJar())
        val loaded = ModRuleSets.load(listOf(mod), javaClass.classLoader)
        assertTrue(loaded.sources.isEmpty(), loaded.sources.toString())
        assertTrue(loaded.problems.isEmpty(), loaded.problems.toString())
        assertTrue(loaded.rules.targets.isEmpty())
    }

    @Test
    fun `a missing jar is skipped instead of failing the bootstrap`() {
        val mod = ModContainer("gone", "Gone", "1.0", "x.Y", File("no/such/mod.jar"))
        val loaded = ModRuleSets.load(listOf(mod), javaClass.classLoader)
        assertTrue(loaded.problems.isEmpty(), loaded.problems.toString())
    }

    // ---------- validation ----------

    @Test
    fun `a class that does not implement the provider is reported`() {
        val loaded = load(NoInterfaceRules::class.java)
        assertEquals(1, loaded.problems.size, loaded.problems.toString())
        assertTrue(loaded.problems.single().contains("does not implement RuleSetProvider"), loaded.problems.toString())
        assertTrue(loaded.sources.isEmpty())
    }

    @Test
    fun `referencing a class outside the allow-list is reported before loading`() {
        val loaded = load(GameRefRules::class.java)
        assertEquals(1, loaded.problems.size, loaded.problems.toString())
        val problem = loaded.problems.single()
        assertTrue(problem.contains("org/objectweb/asm/tree/ClassNode"), problem)
        assertTrue(problem.contains("may only depend on"), problem)
        assertTrue(loaded.sources.isEmpty())
    }

    @Test
    fun `one bad rule source does not hide the good one in the same mod`() {
        val loaded = load(OkRules::class.java, NoInterfaceRules::class.java)
        assertEquals(setOf("omltest/Target"), loaded.rules.targets)
        assertEquals(1, loaded.problems.size, loaded.problems.toString())
        assertEquals(1, loaded.sources.size)
    }

    // ---------- evaluation ----------

    @Test
    fun `an exception from the rule set becomes a readable problem`() {
        val loaded = load(ThrowingRules::class.java)
        assertEquals(1, loaded.problems.size, loaded.problems.toString())
        val problem = loaded.problems.single()
        assertTrue(problem.contains("value lookup failed"), problem)
        assertTrue(problem.contains("rule-set construction failed"), problem)
    }

    // ---------- merge sources ----------

    @Test
    fun `a merge source is read from the declarer's jar and its handler is pinned`() {
        val loaded = load(MergeRules::class.java, MergePatch::class.java)
        assertTrue(loaded.problems.isEmpty(), loaded.problems.toString())
        val merge = loaded.merges.single()
        assertEquals("omltest/Target", merge.targetInternal)
        assertEquals("org/ohmyloader/core/ruleset/fixtures/MergePatch", merge.className)
        // The injection point pins the "name-preserved" method: merging must preserve the name and
        // promote visibility to public, both are required
        assertEquals(setOf("onProbe()V"), merge.handlerMethods, merge.handlerMethods.toString())
    }

    @Test
    fun `a rule class shared by several mods in one jar is registered once`() {
        // A jar may declare several @Mod entry points; the scanner yields one container per entry,
        // all pointing at the same file. The shared @RuleSource must still register exactly once —
        // otherwise the merge runs twice and the second run collides with its own first.
        val file = jarOf(MergeRules::class.java, MergePatch::class.java)
        val mods = listOf(
            ModContainer("mod-a", "A", "1.0", "x.Y", file),
            ModContainer("mod-b", "B", "1.0", "x.Y", file),
        )
        val loaded = ModRuleSets.load(mods, javaClass.classLoader)
        assertTrue(loaded.problems.isEmpty(), loaded.problems.toString())
        assertEquals(1, loaded.merges.size, "the shared merge source must be prepared once, not once per @Mod")
        assertEquals(1, loaded.sources.size, loaded.sources.toString())
    }

    @Test
    fun `a merge source outside the declarer's jar is reported`() {
        val loaded = load(MergeRules::class.java)
        assertEquals(1, loaded.problems.size, loaded.problems.toString())
        assertTrue(loaded.problems.single().contains("not in the same jar"), loaded.problems.toString())
        assertTrue(loaded.merges.isEmpty())
    }

    @Test
    fun `a merge source may reference classes outside the allow-list`() {
        // Validation only targets the rule classes: a merge source lives inside a game class, so
        // referencing game classes is its normal duty
        val loaded = load(MergeRules::class.java, MergePatch::class.java)
        assertTrue(loaded.problems.isEmpty(), loaded.problems.toString())
    }

    // ---------- reference surface ----------

    @Test
    fun `the reference surface covers supertypes, descriptors and instruction owners`() {
        val node = ClassNode()
        node.name = "omltest/Refs"
        node.superName = "omltest/Base"
        node.interfaces = listOf("omltest/Iface")
        node.fields.add(FieldNode(Opcodes.ACC_PRIVATE, "f", "Lomltest/FieldType;", null, null))
        val method = MethodNode(Opcodes.ACC_PUBLIC, "m", "([Lomltest/Arg;)Lomltest/Ret;", null, null)
        method.instructions.add(TypeInsnNode(Opcodes.NEW, "omltest/Newed"))
        method.instructions.add(InsnNode(Opcodes.POP))
        method.instructions.add(MethodInsnNode(Opcodes.INVOKESTATIC, "omltest/Called", "c", "()V"))
        method.instructions.add(InsnNode(Opcodes.RETURN))
        node.methods.add(method)

        val refs = ModRuleSets.referencedTypes(node)
        for (expected in listOf(
            "omltest/Base", "omltest/Iface", "omltest/FieldType",
            "omltest/Arg", "omltest/Ret", "omltest/Newed", "omltest/Called"
        )) {
            assertTrue(expected in refs, "reference surface missed $expected: $refs")
        }
        assertTrue("omltest/Refs" !in refs, "the class itself should not be in the reference surface: $refs")
    }
}
