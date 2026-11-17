package org.ohmyloader.adapter

import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import org.ohmyloader.api.core.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * Routes the client's two late-registration lookups through [ClientRegistrationBridge], because
 * the client registries they read were frozen before mods were constructed:
 *
 * - `MenuScreens.getConstructor` is replaced wholesale: the bridge answers with the mod's screen
 *   factory when it has one and the vanilla registry's result otherwise. Replacing the body keeps
 *   the two lookups in one place instead of replaying vanilla's registry key arithmetic twice.
 * - `BlockEntityRenderers.createEntityRenderers` builds its map from `Registry.getValue`; that
 *   read is redirected so the bridge can substitute a mod's provider. The method itself (and its
 *   error reporting) stays vanilla's, and the redirect runs on every resource reload.
 */
class ClientRegistrationTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/client/gui/screens/MenuScreens") {
            method("getConstructor", desc = MENU_LOOKUP) {
                atHead {
                    raw { m, _ ->
                        m.instructions.clear()
                        m.localVariables.clear()
                        m.visibleLocalVariableAnnotations?.clear()
                        m.invisibleLocalVariableAnnotations?.clear()
                        m.instructions.add(VarInsnNode(Opcodes.ALOAD, 0))
                        m.instructions.add(
                            MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "menuScreenConstructor", MENU_LOOKUP, false),
                        )
                        m.instructions.add(InsnNode(Opcodes.ARETURN))
                    }
                }
            }
        }
        classTarget("net/minecraft/client/renderer/blockentity/BlockEntityRenderers") {
            method(
                "createEntityRenderers",
                desc = "(Lnet/minecraft/client/renderer/blockentity/BlockEntityRendererProvider\$Context;)Ljava/util/Map;",
            ) {
                redirectCall(
                    owner = "net/minecraft/core/Registry",
                    name = "getValue",
                    desc = "(Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;",
                    handlerOwner = BRIDGE,
                    handlerMethod = "blockEntityProvider",
                    handlerDesc = "(Lnet/minecraft/core/Registry;Lnet/minecraft/resources/ResourceKey;)Ljava/lang/Object;",
                )
                // One read per block entity type in the loop; a second site would be a restructure
                // this redirect does not cover.
                require(1)
            }
        }
    },
    id = "oml:client-registration",
) {
    companion object {
        private const val BRIDGE = "org/ohmyloader/adapter/ClientRegistrationBridge"
        private const val MENU_LOOKUP =
            "(Lnet/minecraft/world/inventory/MenuType;)Lnet/minecraft/client/gui/screens/MenuScreens\$ScreenConstructor;"
    }
}
