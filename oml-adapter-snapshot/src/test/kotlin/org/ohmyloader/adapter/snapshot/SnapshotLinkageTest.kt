package org.ohmyloader.adapter.snapshot

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Type
import org.objectweb.asm.tree.*
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * `oml-api-26_3` and `oml-adapter-common` are compiled against 26.3 and shipped unchanged into a 26.4
 * snapshot launch. [HookShapeTest] checks the anchors this adapter *declares*; nothing checked the
 * game references already baked into our own bytecode, where a Mojang rename or retype is a
 * `NoSuchMethodError` for a snapshot player — and no CI job boots a snapshot.
 *
 * So: collect every game reference from the shipped jars and resolve it against the real snapshot jar
 * and its version JSON libraries, with the JVM's link rules as the oracle. Two controls keep the
 * check from passing vacuously — a fabricated member must be refused, and `oml-api` must name no game
 * type at all, which is T-3.8's layering rule as an assertion. What to scan comes from
 * `oml.linkage.jars` / `oml.neutralJar`, the game from `oml.clientJar` / `oml.gameLibraries`.
 */
class SnapshotLinkageTest {

    private val clientJar = File(System.getProperty("oml.clientJar", "libs/26.4-snapshot-5-client.jar"))
    private val librariesDir = File(System.getProperty("oml.gameLibraries", "libs/26.4-snapshot-5-libraries"))
    private val linkageJars = propertyFiles("oml.linkage.jars")
    private val neutralJar = File(System.getProperty("oml.neutralJar", ""))

    /** owner -> superclass, interfaces, and the members that class declares itself. */
    private class GameIndex(
        val superOf: Map<String, String?>,
        val interfacesOf: Map<String, List<String>>,
        val methodsOf: Map<String, Set<String>>,
        val fieldsOf: Map<String, Set<String>>,
    )

    private fun propertyFiles(name: String) =
        System.getProperty(name, "").split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)

    private fun requireGame() {
        assertTrue(
            clientJar.isFile,
            "missing client jar at ${clientJar.absolutePath} — run :oml-adapter-snapshot:fetchClientJar",
        )
        assertTrue(linkageJars.isNotEmpty(), "oml.linkage.jars is empty — the build script wires the shipped OML jars")
    }

    private fun isGameOwner(name: String) =
        name.startsWith("net/minecraft/") || name.startsWith("com/mojang/")

    /** Object type names inside a field or method descriptor: every `L…;` segment, arrays included. */
    private fun objectTypes(descriptor: String): List<String> =
        Regex("L([^;]+);").findAll(descriptor).map { it.groupValues[1] }.toList()

    /** An instruction's element type: `[Lnet/minecraft/…;` names `net/minecraft/…`. */
    private fun elementType(desc: String): String? =
        desc.trimStart('[').takeIf { it.startsWith("L") && it.endsWith(";") }?.removePrefix("L")?.removeSuffix(";")

    /** The version JSON's library tree is Maven-shaped — group, artifact, version, then the jar — so
     *  the jars sit below the top level and a flat listing would index none of them. */
    private fun gameJars() =
        (listOf(clientJar) + librariesDir.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList())
            .filter { it.isFile }

    private fun readClass(jar: JarFile, entryName: String, flags: Int): ClassNode =
        jar.getInputStream(jar.getJarEntry(entryName)).use { stream ->
            ClassNode().also { ClassReader(stream.readAllBytes()).accept(it, flags) }
        }

    /** Only game-owned classes are indexed — parsing every class in the game jar would triple the run. */
    private fun buildIndex(): GameIndex {
        val superOf = HashMap<String, String?>()
        val interfacesOf = HashMap<String, List<String>>()
        val methodsOf = HashMap<String, Set<String>>()
        val fieldsOf = HashMap<String, Set<String>>()
        val flags = ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        for (jar in gameJars()) {
            JarFile(jar).use { file ->
                for (entry in file.entries()) {
                    val owner = entry.name.removeSuffix(".class")
                    if (!entry.name.endsWith(".class") || !isGameOwner(owner)) continue
                    val node = readClass(file, entry.name, flags)
                    superOf[node.name] = node.superName
                    interfacesOf[node.name] = node.interfaces
                    methodsOf[node.name] = node.methods.map { "${it.name} ${it.desc}" }.toSet()
                    fieldsOf[node.name] = node.fields.map { "${it.name} ${it.desc}" }.toSet()
                }
            }
        }
        return GameIndex(superOf, interfacesOf, methodsOf, fieldsOf)
    }

    /** The supertype names the JVM would walk for [owner], as far as the indexed jars reach them. */
    private fun chain(index: GameIndex, owner: String): List<String> {
        val seen = LinkedHashSet<String>()
        val queue = ArrayDeque(listOf(owner))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            index.superOf[current]?.let(queue::add)
            index.interfacesOf[current]?.let(queue::addAll)
        }
        return seen.toList()
    }

    /**
     * The link the JVM performs. A chain leaves the game jar at the `java` prefix — `Enum.name()`,
     * `Record`, `Object` — and there this test's own JVM is exactly what the game links against, so
     * those members are read by reflection rather than hand-listed.
     */
    private fun resolves(index: GameIndex, member: Member): Boolean {
        val declared = "${member.name} ${member.desc}"
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(member.owner))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            if (current in index.methodsOf) {
                if (declared in index.methodsOf.getValue(current) || declared in index.fieldsOf.getValue(current)) {
                    return true
                }
                index.superOf[current]?.let(queue::add)
                index.interfacesOf[current]?.let(queue::addAll)
            } else if (current.startsWith("java/") || current.startsWith("jdk/")) {
                if (declared in jvmMembers(current)) return true
            }
        }
        return false
    }

    private val jvmMemberCache = HashMap<String, Set<String>>()

    /** Every member a JDK class carries along its own chain, spelled the way the index spells them. */
    private fun jvmMembers(owner: String): Set<String> = jvmMemberCache.getOrPut(owner) {
        val found = HashSet<String>()
        val seen = HashSet<Class<*>>()
        val queue = ArrayDeque<Class<*>>()
        runCatching { queue.add(Class.forName(owner.replace('/', '.'), false, null)) }
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            current.declaredMethods.forEach { found += "${it.name} ${descriptorOf(it)}" }
            current.declaredFields.forEach { found += "${it.name} ${Type.getDescriptor(it.type)}" }
            current.superclass?.let(queue::add)
            queue.addAll(current.interfaces)
        }
        found
    }

    private fun descriptorOf(method: java.lang.reflect.Method) =
        method.parameterTypes.joinToString(separator = "", prefix = "(", postfix = ")") {
            Type.getDescriptor(it)
        } + Type.getDescriptor(method.returnType)

    /** Types and members this class file mentions, code included. */
    private fun referencesOf(node: ClassNode): Pair<Set<String>, Set<Member>> {
        val types = HashSet<String>()
        val members = HashSet<Member>()
        node.superName?.let(types::add)
        types += node.interfaces
        node.fields.forEach { types += objectTypes(it.desc) }
        for (method in node.methods) {
            types += objectTypes(method.desc)
            method.instructions?.forEach { collect(it, types, members) }
        }
        return types to members
    }

    private fun collect(instruction: AbstractInsnNode, types: MutableSet<String>, members: MutableSet<Member>) {
        when (instruction) {
            is MethodInsnNode -> {
                types += instruction.owner
                members += Member(instruction.owner, instruction.name, instruction.desc)
            }

            is FieldInsnNode -> {
                types += instruction.owner
                members += Member(instruction.owner, instruction.name, instruction.desc)
            }

            is TypeInsnNode -> elementType(instruction.desc)?.let(types::add)
            is MultiANewArrayInsnNode -> elementType(instruction.desc)?.let(types::add)
            is LdcInsnNode -> {
                (instruction.cst as? Type)?.takeIf { it.sort == Type.OBJECT }?.let { types += it.internalName }
                (instruction.cst as? ConstantDynamic)?.let { constant ->
                    // A ConstantDynamic is an object Type, so its descriptor carries the owner. Its
                    // bootstrap arguments are literals, not member references the game has to provide.
                    types += objectTypes(constant.descriptor)
                }
            }

            is InvokeDynamicInsnNode -> {
                types += objectTypes(instruction.desc)
                listOf(
                    instruction.bsm,
                    *instruction.bsmArgs.filterIsInstance<Handle>().toTypedArray(),
                ).forEach { handle ->
                    types += handle.owner
                    members += Member(handle.owner, handle.name, handle.desc)
                }
            }

            else -> Unit
        }
    }

    private data class Member(val owner: String, val name: String, val desc: String)

    private fun shippedNodes(jar: File): List<ClassNode> = JarFile(jar).use { file ->
        file.entries().toList().mapNotNull { entry ->
            if (!entry.name.endsWith(".class")) return@mapNotNull null
            readClass(file, entry.name, ClassReader.SKIP_DEBUG)
        }
    }

    @Test
    fun `the version-neutral API names no game type`() {
        requireGame()
        assertTrue(
            neutralJar.isFile,
            "oml.neutralJar must point at the version-neutral API jar, got ${neutralJar.path}",
        )
        val offenders = shippedNodes(neutralJar)
            .flatMap { node -> val [types, members] = referencesOf(node); types + members.map { it.owner } }
            .filter(::isGameOwner)
            .distinct()
            .sorted()
        if (offenders.isNotEmpty()) {
            fail(
                "oml-api is the version-neutral layer (T-3.8): naming a game type there means every game " +
                    "version needs its own copy. Found in ${neutralJar.name}: $offenders",
            )
        }
    }

    @Test
    fun `every game reference in the shipped bytecode resolves in the snapshot`() {
        requireGame()
        val index = buildIndex()
        val failures = mutableListOf<String>()
        for (jar in linkageJars) {
            for (node in shippedNodes(jar)) {
                val [types, members] = referencesOf(node)
                for (owner in types.filter(::isGameOwner).filter { it !in index.superOf }.sorted()) {
                    failures += "${node.name}: type $owner is absent from the snapshot"
                }
                for (member in members.filter { isGameOwner(it.owner) }
                    .sortedBy { "${it.owner}.${it.name} ${it.desc}" }) {
                    if (member.owner !in index.superOf) continue // already reported as a missing type
                    if (resolves(index, member)) continue
                    val chain = chain(index, member.owner)
                    val sameName = chain
                        .flatMap { index.methodsOf[it].orEmpty() + index.fieldsOf[it].orEmpty() }
                        .filter { it.startsWith("${member.name} ") }
                        .sorted()
                        .take(3)
                    failures += "${node.name}: ${member.owner}.${member.name} ${member.desc} is absent from the snapshot" +
                        if (sameName.isEmpty()) " (no member of that name in its chain)" else " (there: $sameName)"
                }
            }
        }
        if (failures.isNotEmpty()) {
            failures.sorted().take(40).forEach { println("[OML-Linkage] $it") }
            fail("${failures.size} game references in the shipped bytecode do not resolve in 26.4-snapshot-5 (40 printed)")
        }
    }

    /**
     * Control for the check above: the scan must collect a real number of references, and the resolver
     * must be able to say *absent*. A check that collected nothing would be green forever, which is the
     * same failure mode as a gate whose expectations are never reported.
     */
    @Test
    fun `the checker collects references and can refuse one`() {
        requireGame()
        val index = buildIndex()
        val collected = linkageJars
            .flatMap { shippedNodes(it) }
            .flatMap { referencesOf(it).second }
            .filter { isGameOwner(it.owner) && it.owner in index.superOf }
        assertTrue(
            collected.size > 100,
            "only ${collected.size} game member references were collected from $linkageJars — the scan reads nothing",
        )
        val sample = collected.first()
        assertFalse(
            resolves(index, Member(sample.owner, "omlNotAMemberOfThisType", "()V")),
            "a fabricated member resolved against ${sample.owner}, so nothing this test reports as absent means anything",
        )
    }
}
