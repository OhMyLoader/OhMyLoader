package org.ohmyloader.adapter.v26_3

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.ohmyloader.adapter.common.MinecraftHookTransformer
import org.ohmyloader.adapter.common.NetworkPayloadTransformer
import org.ohmyloader.adapter.common.ServerHookTransformer
import org.ohmyloader.api.inject.InjectionPoint
import java.io.File
import java.util.jar.JarFile
import kotlin.test.*

/**
 * **Anti-drift shape assertions**: every hook the 26.3 adapters declare is checked against the *real* `26.3-client.jar` bytecode, so the
 * day Mojang renames or reshapes an anchor, this build fails instead of the game silently doing one thing less at launch.
 * The assertions are **derived from the live rule sets** (`MinecraftHookTransformer.rules` / `ServerHookTransformer.rules` / `NetworkPayloadTransformer.rules`), not a
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
        "client" to MinecraftHookTransformer(idPrefix = "v26_3").rules,
        "server" to ServerHookTransformer(idPrefix = "v26_3").rules,
        "payload" to NetworkPayloadTransformer(idPrefix = "v26_3").rules,
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
                val node = classNode(jar, target) // covered by the class-existence test
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
                val node = classNode(jar, target)
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
                val node = classNode(jar, target)
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
            assertNotNull(
                method,
                "net/minecraft/client/main/Main no longer has main([Ljava/lang/String;)V — " +
                    "the entry-point intercept anchor has drifted",
            )
        }
    }

    @Test
    fun `BuiltInRegistries bootStrap still calls freeze before validation`() {
        JarFile(clientJar).use { jar ->
            val registries = assertNotNull(classNode(jar, "net/minecraft/core/registries/BuiltInRegistries"))
            val bootStrap = registries.methods.firstOrNull { it.name == "bootStrap" && it.desc == "()V" }
            assertNotNull(
                bootStrap,
                "BuiltInRegistries.bootStrap()V is gone — the registry freeze redirect has no target",
            )
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
            assertNotNull(
                minecraft.methods.firstOrNull { it.name == "runTick" && it.desc == "(Z)V" },
                "net/minecraft/client/Minecraft.runTick(Z)V is gone — the client tick hook has no target",
            )
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
                "net/minecraft/server/MinecraftServer.tickServer(Ljava/util/function/BooleanSupplier;)V is " +
                    "gone — the server tick hook has no target",
            )
        }
    }

    @Test
    fun `placeNewPlayer still ends in one return, so TAIL means fully joined`() {
        JarFile(clientJar).use { jar ->
            val list = assertNotNull(classNode(jar, "net/minecraft/server/players/PlayerList"))
            val place = list.methods.firstOrNull { it.name == "placeNewPlayer" }
            assertNotNull(place, "PlayerList.placeNewPlayer is gone — PLAYER_JOINED has no target")
            val returns = place.instructions.toArray().count { it.opcode == Opcodes.RETURN }
            // With more than one exit, "before the last return" is no longer "after the player is in the
            // list": a mod could get the event for a player the game then rejects.
            assertEquals(
                1,
                returns,
                "PlayerList.placeNewPlayer now has $returns exits — the TAIL anchor no longer guarantees a fully joined player",
            )
        }
    }

    @Test
    fun `hurtServer is still declared on LivingEntity, so damage fires once`() {
        JarFile(clientJar).use { jar ->
            val living = assertNotNull(classNode(jar, "net/minecraft/world/entity/LivingEntity"))
            assertNotNull(
                living.methods.firstOrNull {
                    it.name == "hurtServer" &&
                        it.desc == "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z"
                },
                "LivingEntity.hurtServer is no longer the base declaration — hooking it would fire " +
                    "zero or several times per damage application instead of exactly once",
            )
        }
    }

    @Test
    fun `the block break owner is still reachable only through the constructor argument`() {
        JarFile(clientJar).use { jar ->
            val gameMode = assertNotNull(classNode(jar, "net/minecraft/server/level/ServerPlayerGameMode"))
            assertNotNull(
                gameMode.methods.firstOrNull { it.name == "destroyBlock" && it.desc == "(Lnet/minecraft/core/BlockPos;)Z" },
                "ServerPlayerGameMode.destroyBlock(BlockPos)Z is gone — BLOCK_BREAK has no target",
            )
            val playerField = gameMode.fields.firstOrNull { it.name == "player" }
            assertNotNull(playerField, "ServerPlayerGameMode.player is gone; the constructor hook needs a new owner")
            // The association hook exists only because the field is protected; if Mojang published an
            // accessor, the WeakHashMap in EventBridge should go and this test says so.
            assertEquals(
                0,
                playerField.access and Opcodes.ACC_PUBLIC,
                "ServerPlayerGameMode.player is now public — project it directly instead of tracking the constructor argument"
            )
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Test
    fun `the inbound chat handlers still re-dispatch through ensureRunningOnSameThread`() {
        JarFile(clientJar).use { jar ->
            val listener = assertNotNull(classNode(jar, "net/minecraft/client/multiplayer/ClientPacketListener"))
            for (name in listOf("handleSystemChat", "handlePlayerChat", "handleDisguisedChat")) {
                val handler = listener.methods.firstOrNull { it.name == name }
                assertNotNull(handler, "ClientPacketListener.$name is gone — CHAT_RECEIVED has no target")
                // The CHAT_RECEIVED hook lands immediately after this call, because the call is what
                // re-dispatches the packet onto the game thread: on the netty thread it re-schedules and
                // throws, so only the game-thread invocation reaches the instruction after it. If the
                // re-dispatch call is no longer the handler's first, the hook fires once per thread.
                val redispatches = handler.instructions.toArray().take(12).filterIsInstance<MethodInsnNode>()
                    .any {
                        it.opcode == Opcodes.INVOKESTATIC &&
                            it.owner == "net/minecraft/network/protocol/PacketUtils" &&
                            it.name == "ensureRunningOnSameThread"
                    }
                assertTrue(
                    redispatches,
                    "ClientPacketListener.$name no longer re-dispatches through " +
                        "PacketUtils.ensureRunningOnSameThread at its head — the CHAT_RECEIVED hook would " +
                        "fire on the netty thread and again on the game thread",
                )
            }
        }
    }

    private fun <T> failIfNotEmpty(what: String, items: Collection<T>, render: (T) -> String) {
        if (items.isEmpty()) return
        fail("${items.size} $what:\n" + items.joinToString("\n") { " - " + render(it) })
    }
}
