package org.ohmyloader.adapter.v26_3

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.ohmyloader.api.inject.InjectionPoint
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * **Anti-drift shape assertions**: every hook the 26.3 adapters declare is checked against the *real* `26.3-client.jar` bytecode, so the
 * day Mojang renames or reshapes an anchor, this build fails instead of the game silently doing one thing less at launch.
 * The assertions are **derived from the live rule sets** (`MinecraftHookTransformer.rules` / `ServerHookTransformer.rules`), not a
 * hand-copied list: the rule declarations are the single source of truth, and this test walks them — every targeted class, method
 * selector, call anchor and access-rewritten field must exist in the jar with the declared shape. Headline anchors also get explicit
 * named tests, so a regression in the most load-bearing hooks reads in the report by name. The client jar is a gitignored development
 * reference: the test task depends on `fetchClientJar` and receives its path via the `oml.clientJar` system property.
 */
class HookShapeTest {

    private val clientJar: File =
        File(System.getProperty("oml.clientJar", "libs/26.3-client.jar"))

    /** Client and server transformers instantiate cleanly without any game class on the classpath: rules are pure data. */
    private val ruleSets = listOf(
        "client" to MinecraftHookTransformer().rules,
        "server" to ServerHookTransformer().rules,
    )

    private fun classNode(jar: JarFile, internalName: String): ClassNode =
        jar.getInputStream(jar.getJarEntry("$internalName.class")).use { stream ->
            ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, 0) }
        }

    /** All method-call sites inside [owner]: owner/name/desc triples as actually invoked in bytecode. */
    private fun invokedMethods(node: ClassNode): List<MethodInsnNode> =
        node.methods.flatMap { it.instructions.toArray().filterIsInstance<MethodInsnNode>() }

    private fun allClasses(jar: JarFile) = ruleSets.flatMap { [side, rules] ->
        rules.classes.map { [target, classRules] -> Triple(side, target, classRules) }
    }

    // ---------------------------------------------------------------------------------------------
    // Derived assertions: the whole rule set, checked against the real jar
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `client jar is present`() {
        assertTrue(
            clientJar.isFile,
            "missing client jar at ${clientJar.absolutePath} — run :oml-adapter-26_3:fetchClientJar",
        )
    }

    @Test
    fun `every targeted class exists in the game jar`() {
        JarFile(clientJar).use { jar ->
            val missing = allClasses(jar).filter { [_, target, _] -> jar.getJarEntry("$target.class") == null }
            failIfNotEmpty("classes targeted by rules but absent from the jar", missing) { [side, target, _] ->
                "[$side] $target"
            }
        }
    }

    @Test
    fun `every selected method exists with the declared descriptor`() {
        JarFile(clientJar).use { jar ->
            val failures = mutableListOf<String>()
            for ([side, target, classRules] in allClasses(jar)) {
                val node = classNode(jar, target) ?: continue // covered by the class-existence test
                for (rule in classRules.methods) {
                    val matches = node.methods.filter { it.name in rule.selector.names }
                    if (matches.isEmpty()) {
                        failures += "[$side] $target has no method ${rule.selector.names} (declared desc=${rule.selector.desc})"
                        continue
                    }
                    val desc = rule.selector.desc
                    if (desc != null && matches.none { it.desc == desc }) {
                        val actual = matches.joinToString { "${it.name}${it.desc}" }
                        failures += "[$side] $target: selector ${rule.selector.names} exists but not with desc=$desc (actual: $actual)"
                    }
                }
            }
            failIfNotEmpty("method selectors with no matching shape in the jar", failures) { it }
        }
    }

    @Test
    fun `every call anchor references a real method invocation`() {
        JarFile(clientJar).use { jar ->
            val failures = mutableListOf<String>()
            for ([side, target, classRules] in allClasses(jar)) {
                val node = classNode(jar, target) ?: continue
                val callSites = invokedMethods(node)
                for (rule in classRules.methods) {
                    for ([anchor, _] in rule.points) {
                        val call = anchor as? InjectionPoint.Call ?: continue
                        val owner = call.owner ?: continue // unanchored owner means "any call site" — nothing to assert
                        val name = call.name ?: continue
                        val found = callSites.any {
                            it.owner == owner && it.name == name && (call.desc == null || it.desc == call.desc)
                        }
                        if (!found) {
                            failures += "[$side] $target: anchor Call(owner=$owner, name=$name, desc=${call.desc}) has no call site in the real bytecode"
                        }
                    }
                }
            }
            failIfNotEmpty("call anchors with no matching invocation in the jar", failures) { it }
        }
    }

    @Test
    fun `every access-rewritten field exists with the declared descriptor`() {
        JarFile(clientJar).use { jar ->
            val failures = mutableListOf<String>()
            for ([side, target, classRules] in allClasses(jar)) {
                val node = classNode(jar, target) ?: continue
                for (rule in classRules.accessRules) {
                    if (rule.kind != org.ohmyloader.api.inject.MemberKind.FIELD) continue
                    for (name in rule.names) {
                        val field = node.fields.firstOrNull { it.name == name }
                        if (field == null) {
                            failures += "[$side] $target: field $name (access rule) no longer exists"
                        } else if (rule.desc != null && field.desc != rule.desc) {
                            failures += "[$side] $target: field $name is now ${field.desc}, rule declares ${rule.desc}"
                        }
                    }
                }
            }
            failIfNotEmpty("access rules with no matching field in the jar", failures) { it }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Headline anchors, asserted by name: the load-bearing hooks, readable directly in the report
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `Main main is still the static string-array entry point`() {
        JarFile(clientJar).use { jar ->
            val main = assertNotNull(classNode(jar, "net/minecraft/client/main/Main"))
            val method = main.methods.firstOrNull { it.name == "main" && it.desc == "([Ljava/lang/String;)V" }
            assertNotNull(method) {
                "net/minecraft/client/main/Main no longer has main([Ljava/lang/String;)V — " +
                    "the entry-point intercept anchor has drifted"
            }
        }
    }

    @Test
    fun `BuiltInRegistries bootStrap still calls freeze before validation`() {
        JarFile(clientJar).use { jar ->
            val registries = assertNotNull(classNode(jar, "net/minecraft/core/registries/BuiltInRegistries"))
            val bootStrap = registries.methods.firstOrNull { it.name == "bootStrap" && it.desc == "()V" }
            assertNotNull(bootStrap) { "BuiltInRegistries.bootStrap()V is gone — the registry freeze redirect has no target" }
            val freezeCall = bootStrap.instructions.toArray().filterIsInstance<MethodInsnNode>()
                .any { it.name == "freeze" && it.desc == "()V" }
            assertTrue(
                freezeCall,
                "BuiltInRegistries.bootStrap()V no longer invokes freeze()V — mod content would materialize " +
                    "after the registry closes and be rejected by the freeze check",
            )
        }
    }

    @Test
    fun `Minecraft runTick still takes the per-frame boolean`() {
        JarFile(clientJar).use { jar ->
            val minecraft = assertNotNull(classNode(jar, "net/minecraft/client/Minecraft"))
            assertNotNull(minecraft.methods.firstOrNull { it.name == "runTick" && it.desc == "(Z)V" }) {
                "net/minecraft/client/Minecraft.runTick(Z)V is gone — the client tick hook has no target"
            }
        }
    }

    @Test
    fun `MinecraftServer tickServer still takes the BooleanSupplier`() {
        JarFile(clientJar).use { jar ->
            val server = assertNotNull(classNode(jar, "net/minecraft/server/MinecraftServer"))
            assertNotNull(
                server.methods.firstOrNull {
                    it.name == "tickServer" && it.desc == "(Ljava/util/function/BooleanSupplier;)V"
                },
            ) { "net/minecraft/server/MinecraftServer.tickServer(Ljava/util/function/BooleanSupplier;)V is gone — the server tick hook has no target" }
        }
    }

    // ---------------------------------------------------------------------------------------------

    private fun <T> failIfNotEmpty(what: String, items: Collection<T>, render: (T) -> String) {
        if (items.isEmpty()) return
        fail("${items.size} $what:\n" + items.joinToString("\n") { " - " + render(it) })
    }
}
