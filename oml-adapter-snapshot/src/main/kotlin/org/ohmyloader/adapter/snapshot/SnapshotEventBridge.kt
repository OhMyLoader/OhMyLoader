package org.ohmyloader.adapter.snapshot

import net.minecraft.core.registries.BuiltinRegistryBuilder
import org.ohmyloader.adapter.common.MinecraftContentRegistry
import org.ohmyloader.core.OMLCore

/**
 * The snapshot line's registry-freeze handler. 26.3 freezes through a private **static**
 * `BuiltInRegistries.freeze()`, handled by the shared bridge; the snapshot moved the whole
 * bootstrap onto `BuiltinRegistryBuilder`, whose `freeze()` is a private **instance** method —
 * the redirect hands the builder over and this handler re-invokes it on the instance.
 *
 * This handler lives in the snapshot module, not the shared bridge, because
 * `BuiltinRegistryBuilder` does not exist in the 26.3 jar and the shared layer ships into 26.3
 * installs too; naming the class there would put a 26.3-unresolvable game reference into every
 * stable install.
 */
object SnapshotEventBridge {

    @JvmStatic
    fun onRegistryFreeze(builder: BuiltinRegistryBuilder) {
        MinecraftContentRegistry.materializeAll()
        val loader = OMLCore.gameClassLoader()
        Class.forName("net.minecraft.core.registries.BuiltinRegistryBuilder", true, loader)
            .getDeclaredMethod("freeze").apply { isAccessible = true }.invoke(builder)
    }
}
