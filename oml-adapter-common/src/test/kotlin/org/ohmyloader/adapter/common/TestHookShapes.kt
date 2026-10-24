package org.ohmyloader.adapter.common

import org.ohmyloader.api.inject.DslValue

/**
 * The 26.3 facts for this module's mechanics tests. The reference toolchain here is the 26.3 jar
 * (see the build script), so every synthetic- and real-jar assertion speaks 26.3 shapes; the
 * snapshot line's shapes are asserted by the adapters' own HookShapeTests against their jars.
 */
internal val testHookShapes = HookShapes(
    hudExtractDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
    onHudRenderDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
    hudRenderArgs = listOf(DslValue.Arg(0), DslValue.Arg(1)),
    tabButtonDesc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IILnet/minecraft/world/item/CreativeModeTab;)V",
    tabClampOwner = "net/minecraft/util/Mth",
    tabClampDesc = "(III)I",
    freezeAnchorClass = "net/minecraft/core/registries/BuiltInRegistries",
    freezeCallOwner = "net/minecraft/core/registries/BuiltInRegistries",
    freezeHandlerOwner = "org/ohmyloader/adapter/common/EventBridge",
    freezeHandlerMethod = "onRegistryFreeze",
)
