package org.ohmyloader.adapter

import org.ohmyloader.api.core.inject.DslValue
import org.ohmyloader.api.core.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The hooks behind [CreativeScreenPagination] — the takeover of the creative screen's tab strip
 * (client only). The screen builds one `TabNavigationBar` per row from `ItemTabButton`s; the strip
 * model decides where each of those buttons sits:
 *
 * - `extractBackground` head → [CreativeScreenPagination.applyStripLayout]: moves or hides the
 *   buttons before either draw pass runs (the screen draws unselected buttons there, the bars
 *   draw all of them later);
 * - `extractLabels` tail → the `i / n` indicator;
 * - `init` tail → the strip rebuild and the page arrows / Manage Tabs entry.
 */
class CreativeScreenPaginationTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen") {
            method("extractBackground", desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V") {
                atHead {
                    call(
                        PAGINATION, "applyStripLayout",
                        "(L" + SCREEN_TYPE + ";)V",
                        args = listOf(DslValue.This),
                    )
                }
            }
            method("extractLabels", desc = "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V") {
                atTail {
                    call(
                        PAGINATION, "drawPageIndicator",
                        "(L" + SCREEN_TYPE + ";Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V",
                        args = listOf(DslValue.This, DslValue.Arg(0)),
                    )
                }
            }
            method("init", desc = "()V") {
                atTail {
                    call(PAGINATION, "onScreenInit", "(L" + SCREEN_TYPE + ";)V", args = listOf(DslValue.This))
                }
            }
        }
    },
    id = "oml:creative-pagination",
) {
    companion object {
        private const val PAGINATION = "org/ohmyloader/adapter/CreativeScreenPagination"
        private const val SCREEN_TYPE = "net/minecraft/client/gui/screens/inventory/CreativeModeInventoryScreen"
    }
}
