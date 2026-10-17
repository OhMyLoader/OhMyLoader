package org.ohmyloader.core

import org.ohmyloader.adapter.probe.ProbeAdapterA
import org.ohmyloader.adapter.probe.ProbeAdapterB
import org.ohmyloader.core.classloader.OMLClassLoader
import org.ohmyloader.core.spi.IAdapter
import java.io.File
import java.net.URLClassLoader
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Records which probe adapter was instantiated; parent-first, so one identity across both loaders. */
internal object InstantiatedAdapters {
    val names = mutableListOf<String>()
}

/**
 * Replicates the install layout — the adapter jar on the main loader's own search path, discovered through
 * that loader — because the two faults `discoverAdapter` must refuse leave no trace otherwise: a stale
 * second adapter in `lib/` would be picked by classpath enumeration order and transform the game against
 * another version's member names, and a missing one would hand over to a game that never starts.
 *
 * Asserted: both providers are refused **by name before either is instantiated** (running a second
 * adapter's initializer to find the fault is not a harmless way to ask), and the single adapter is the
 * instance the main loader defined.
 */
class AdapterDiscoveryTest {

    private val providersPath = "META-INF/services/${IAdapter::class.java.name}"

    /** The test classes directory is a URL of the main loader, so `org.ohmyloader.adapter.` lands child-first. */
    private fun loaderWithProviders(names: List<String>): OMLClassLoader {
        val servicesDir = createTempDirectory("oml-adapter-discovery").toFile().apply { deleteOnExit() }
        File(servicesDir, providersPath).apply { parentFile.mkdirs() }.writeText(names.joinToString("\n"))
        val testClasses = File(javaClass.protectionDomain.codeSource.location.toURI()).toURI().toURL()
        val parent = URLClassLoader(arrayOf(testClasses), javaClass.classLoader)
        return OMLClassLoader(arrayOf(testClasses, servicesDir.toURI().toURL()), parent)
    }

    @Test
    fun `two adapters on the install layer are refused by name`() {
        InstantiatedAdapters.names.clear()
        val loader = loaderWithProviders(listOf(ProbeAdapterA::class.java.name, ProbeAdapterB::class.java.name))

        val failure = assertFailsWith<IllegalStateException> { OMLCore.discoverAdapter(loader) }

        assertTrue(
            failure.message!!.contains(ProbeAdapterA::class.java.name) &&
                failure.message!!.contains(ProbeAdapterB::class.java.name),
            "the refusal must name both copies, got: ${failure.message}",
        )
        assertEquals(emptyList(), InstantiatedAdapters.names, "refusing must not run either adapter's initializer")
    }

    @Test
    fun `no adapter at all is fatal`() {
        val loader = loaderWithProviders(emptyList())

        val failure = assertFailsWith<IllegalStateException> { OMLCore.discoverAdapter(loader) }

        assertTrue(failure.message!!.contains("no IAdapter implementation"), "got: ${failure.message}")
    }

    @Test
    fun `the single adapter is defined by the main loader`() {
        InstantiatedAdapters.names.clear()
        val loader = loaderWithProviders(listOf(ProbeAdapterA::class.java.name))

        val adapter = OMLCore.discoverAdapter(loader)

        assertEquals("probe-a", adapter.versionId)
        assertSame(loader, adapter.javaClass.classLoader)
        assertEquals(listOf("A"), InstantiatedAdapters.names)
    }
}
