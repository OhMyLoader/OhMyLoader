package org.ohmyloader.core.classloader

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.ohmyloader.core.diagnostics.Diagnostics
import org.ohmyloader.core.transformer.IClassTransformer
import org.ohmyloader.core.transformer.TransformContext
import java.net.URL
import java.net.URLClassLoader

/**
 * OML's primary loader: game classes and mod classes are all defined here, with bytecode
 * uniformly flowing through the transformation pipeline (locate the bytecode → run each
 * [IClassTransformer] → write back and define).
 *
 * Delegation strategy: game/mod classes are child-first (otherwise the parent loader would
 * load them as-is and the pipeline would never get a chance); JDK internals and the
 * loader's own dependencies (ASM, Kotlin, logging, LWJGL, etc.) stay parent-first,
 * guaranteeing module access and a single global class identity.
 */
class OMLClassLoader(
    urls: Array<URL>,
    parent: ClassLoader
) : URLClassLoader(urls, parent) {

    private val transformers = mutableListOf<IClassTransformer>()

    fun registerTransformer(transformer: IClassTransformer) {
        transformers.add(transformer)
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(getClassLoadingLock(name)) {
            var c = findLoadedClass(name)
            if (c == null) {
                c = if (shouldParentDelegate(name)) {
                    super.loadClass(name, false)
                } else {
                    try {
                        findClass(name)
                    } catch (_: ClassNotFoundException) {
                        super.loadClass(name, false)
                    }
                }
            }
            if (resolve) resolveClass(c)
            return c
        }
    }

    private fun shouldParentDelegate(name: String): Boolean =
        PARENT_FIRST_PREFIXES.any { name.startsWith(it) } && !name.startsWith(ADAPTER_PREFIX)

    /**
     * Resources child-first: root resources of the game jar (such as version.json) must not
     * be shadowed by same-named resources in the parent loader, otherwise the game's version
     * detection silently fails. Fall back to the parent loader only if not found locally.
     */
    override fun getResource(name: String): URL? =
        findResource(name) ?: super.getResource(name)

    override fun findClass(name: String): Class<*> {
        val started = System.nanoTime()
        val internalName = name.replace('.', '/')
        val resourcePath = "$internalName.class"
        val readStarted = System.nanoTime()
        val bytes = getResourceAsStream(resourcePath)?.readAllBytes()
            ?: throw ClassNotFoundException("$name (resource $resourcePath not in the OML loader search path)")
        Diagnostics.recordPhase("read bytes", System.nanoTime() - readStarted)

        // Both exit points must be recorded — put it in a local function so a newly added exit
        // point cannot be forgotten (each missed path means an entire batch of classes goes
        // uncounted). `defineClass` is measured here too: the JVM's link-time validation is often
        // more expensive than our own rewriting, and without measuring it separately the report
        // cannot explain why the total exceeds the sum of the stages.
        fun finished(make: () -> Class<*>, changed: Boolean): Class<*> {
            val defineStarted = System.nanoTime()
            val result = make()
            Diagnostics.recordPhase("define", System.nanoTime() - defineStarted)
            Diagnostics.recordClass(internalName, System.nanoTime() - started, changed)
            return result
        }

        // 1) Fast path: `appliesTo` consults only the name, so it can run before any parsing. The
        //    overwhelming majority of game classes interest no transformer — skip the ClassNode
        //    build entirely (EXPAND_FRAMES is the most expensive parse mode) and define the
        //    original bytes untouched.
        val transformStarted = System.nanoTime()
        val interested = transformers.any { it.appliesTo(internalName) }
        if (!interested) {
            Diagnostics.recordPhase("version transform", System.nanoTime() - transformStarted)
            return finished({ defineClass(name, bytes, 0, bytes.size) }, false)
        }

        // 2) Parse — only classes some transformer claimed
        val parseStarted = System.nanoTime()
        val node = ClassNode(Opcodes.ASM9)
        // EXPAND_FRAMES so the transformers see full frames (the injection engine walks the
        // local-variable table, which is what the expanded form carries).
        ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES)
        Diagnostics.recordPhase("parse", System.nanoTime() - parseStarted)

        // 3) Version transformers modify in turn
        var modified = false
        for (transformer in transformers) {
            if (transformer.appliesTo(node.name) && transformer.transform(TransformContext(node.name, node))) {
                modified = true
            }
        }
        Diagnostics.recordPhase("version transform", System.nanoTime() - transformStarted)

        if (!modified) {
            return finished({ defineClass(name, bytes, 0, bytes.size) }, false)
        }

        // 4) Write back and define. COMPUTE_MAXS recomputes maxStack/maxLocals (injection grows the
        //    stack demand), but not COMPUTE_FRAMES: frames rewritten in place stay valid, whereas
        //    COMPUTE_FRAMES back-loads other classes mid-definition, risking re-entrancy and
        //    identity splits.
        val writeStarted = System.nanoTime()
        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        val transformed = writer.toByteArray()
        Diagnostics.recordPhase("write-back", System.nanoTime() - writeStarted)
        return finished({ defineClass(name, transformed, 0, transformed.size) }, true)
    }

    /**
     * Entry point for defining generated classes: defines a runtime-generated bytecode
     * class into this loader.
     *
     * Generated classes (such as Mixin bridge classes) must share a loader with the game
     * classes, otherwise their type identity would split. Since
     * `ClassLoader.defineClass` is protected and `setAccessible` can no longer open
     * `java.lang` as of Java 26, this class exposes its own public method instead of
     * forcing access via reflection from outside.
     */
    fun defineGeneratedClass(internalName: String, bytes: ByteArray): Class<*> =
        defineClass(internalName.replace('/', '.'), bytes, 0, bytes.size)

    private companion object {
        /**
         * Version-adapter classes are game-coupled code: their signatures and bodies reference game types, so
         * they must be **defined in this loader** — the same loader as the game classes — or the JVM's
         * loader-constraint check splits the game type into two Class objects and every injected call into the
         * adapter dies with `LinkageError: loader constraint violation`. Under the blanket `org.ohmyloader.`
         * parent-first rule the parent loader (which carries the game jar on the app classpath) would define
         * the adapter and resolve the game type to *its own* copy — re-running its `<clinit>` in a world where
         * the game's library jars are invisible, hence `NoClassDefFoundError`. So adapters join the mods' child-first
         * treatment; the rest of `org.ohmyloader.` keeps parent-first for a single identity with the app-loader world.
         */
        const val ADAPTER_PREFIX = "org.ohmyloader.adapter."

        /** These prefixes are always parent-first: the JVM itself, loader runtime, and all loader-level dependency libraries. */
        val PARENT_FIRST_PREFIXES = listOf(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            // OML itself
            "org.ohmyloader.", "org.objectweb.asm.", "org.jetbrains.",
            "kotlin.", "kotlinx.",
            // logging
            "org.slf4j.", "org.apache.logging.log4j.", "org.apache.logging.log4j.core.",
            // third-party libraries the game runtime depends on (provided uniformly by the parent loader, guaranteeing a single identity)
            "org.lwjgl.", "com.paulscode.", "net.java.games.", "net.java.dev.jna.",
            "com.mojang.authlib.", "com.mojang.text2speech.", "joptsimple.",
            "com.google.", "io.netty.", "org.apache.commons.",
            "it.unimi.dsi.", "com.ibm.icu.", "oshi.",
            // runtime dependencies that may be absent on a given target (listing them is harmless — the packages simply do not exist)
            "org.joml.", "com.mojang.brigadier.", "com.mojang.serialization.",
            "com.mojang.datafixers.", "com.sun.jna.",
            // JDK internal XML/JAXP family: mixing the java.xml module with xml libraries on the
            // classpath triggers loader constraint conflicts (org.xml.sax.InputSource defined
            // twice) — the whole family must be parent-first
            "org.xml.", "org.w3c.", "javax.xml.", "com.sun.org.apache.xerces.",
            "com.sun.org.apache.xalan.", "javax.management.", "javax.naming.",
        )
    }
}