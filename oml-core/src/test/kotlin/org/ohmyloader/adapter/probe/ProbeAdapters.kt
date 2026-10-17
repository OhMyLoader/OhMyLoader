package org.ohmyloader.adapter.probe

import org.ohmyloader.core.InstantiatedAdapters
import org.ohmyloader.core.spi.IAdapter
import org.ohmyloader.core.transformer.IClassTransformer

/**
 * Service providers for `AdapterDiscoveryTest` only. The package is not incidental: `org.ohmyloader.adapter.`
 * is child-first, so the main loader defines these the way it defines a real adapter.
 */
class ProbeAdapterA : IAdapter {
    init {
        InstantiatedAdapters.names.add("A")
    }

    override val versionId: String = "probe-a"
    override val mainClass: String = "com.example.probe.ClientMain"
    override fun createTransformers(): List<IClassTransformer> = emptyList()
}

class ProbeAdapterB : IAdapter {
    init {
        InstantiatedAdapters.names.add("B")
    }

    override val versionId: String = "probe-b"
    override val mainClass: String = "com.example.probe.ClientMain"
    override fun createTransformers(): List<IClassTransformer> = emptyList()
}
