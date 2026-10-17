package org.ohmyloader.adapter.common

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.ohmyloader.core.transformer.TransformContext
import org.ohmyloader.core.transformer.injection.InjectingTransformer
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The live rules are **applied** to the real 26.3 bytecode here, class by class, so an anchor that no
 * longer matches fails the build on the `require=` line the rule declares instead of at game startup.
 *
 * [HandlerShapeTest] checks the handler side of each rule; this one checks the target side: anchor
 * resolution, hit counts and payload emission. What stays out of reach is the JVM's own verification of
 * the rewritten method — the frames are computed when the class is written out, so a `VerifyError` still
 * only shows up in a real run.
 */
class RuleApplyTest {

    private val clientJar: File =
        File(System.getProperty("oml.clientJar", "libs/26.3-client.jar"))

    private val transformers: List<InjectingTransformer> = listOf(
        MinecraftHookTransformer(idPrefix = "test"),
        ServerHookTransformer(idPrefix = "test"),
        NetworkCompressionTransformer(idPrefix = "test"),
        NetworkPayloadTransformer(idPrefix = "test"),
    )

    @Test
    fun `every live rule applies to the real 26_3 bytecode`() {
        JarFile(clientJar).use { jar ->
            val failures = mutableListOf<String>()
            for (transformer in transformers) {
                for (target in transformer.rules.classes.keys) {
                    val entry = jar.getJarEntry("$target.class")
                        ?: run { failures += "[${transformer.id}] $target is not in the jar"; continue }
                    val node = jar.getInputStream(entry).use { stream ->
                        ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, 0) }
                    }
                    try {
                        transformer.transform(TransformContext(target, node))
                    } catch (e: Exception) {
                        failures += "[${transformer.id}] $target: ${e.javaClass.simpleName} ${e.message}"
                    }
                }
            }
            if (failures.isNotEmpty()) {
                fail(
                    "${failures.size} rule applications failed against the jar:\n" +
                        failures.joinToString("\n") { " - $it" },
                )
            }
        }
    }

    @Test
    fun `the jar the rules are checked against is present`() {
        assertTrue(clientJar.isFile, "missing ${clientJar.absolutePath} — run :oml-adapter-26_3:fetchClientJar")
    }

    /**
     * The damage rewrite has to sit **after** the event dispatch it serves. Rules apply in declaration
     * order to the instruction list the previous rule left behind, so declaring the read anchor after the
     * HEAD hook makes it match the hook's own argument load of the damage parameter instead of vanilla's
     * first read: the event still fires and still writes its number, but nothing ever reads it, and the
     * game applies the damage the attacker computed.
     */
    @Test
    fun `the hurt amount rewrite sits after the dispatch whose value it consumes`() {
        val target = "net/minecraft/world/entity/LivingEntity"
        val node = JarFile(clientJar).use { jar ->
            val entry = requireNotNull(jar.getJarEntry("$target.class")) { "$target is not in the jar" }
            jar.getInputStream(entry).use { stream ->
                ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, 0) }
            }
        }
        ServerHookTransformer(idPrefix = "test").transform(TransformContext(target, node))
        val method = node.methods.single {
            it.name == "hurtServer" && it.desc == "(Lnet/minecraft/server/level/ServerLevel;" +
                "Lnet/minecraft/world/damagesource/DamageSource;F)Z"
        }
        val bridgeCalls = method.instructions.toList()
            .filterIsInstance<MethodInsnNode>()
            .filter { it.owner == "org/ohmyloader/adapter/common/EventBridge" }
            .map { it.name }
        val dispatch = bridgeCalls.indexOf("onLivingHurt")
        val rewrite = bridgeCalls.indexOf("onHurtAmount")
        assertTrue(
            dispatch in 0..<rewrite,
            "expected onLivingHurt then onHurtAmount in that order, got $bridgeCalls",
        )
    }
}
