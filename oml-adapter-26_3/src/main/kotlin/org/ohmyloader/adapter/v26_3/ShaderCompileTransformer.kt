package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * 26.3-only RenderPearl shader-compile hook. The frontend's async pipeline compile reaches
 * Shaderc through an FFM downcall/upcall chain that is unstable on a shared ForkJoin worker's
 * stack; the shared [org.ohmyloader.adapter.common.ShaderCompileExecutor] runs those tasks on
 * dedicated large-stack threads. The renderpearl frontend is version-specific, so this rule lives
 * with the 26.3 adapter rather than the shared Minecraft transformer (the 26.4 snapshot has no
 * such method).
 */
class ShaderCompileTransformer : InjectingTransformer(
    injection {
        classTarget("com/mojang/renderpearl/frontend/shaders/PipelineBuilder") {
            method(
                "compilePipeline",
                desc = "(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;" +
                    "Lcom/mojang/renderpearl/api/pipeline/ShaderSource;" +
                    "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            ) {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/common/ShaderCompileExecutor",
                    method = "compilePipeline",
                    desc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/concurrent/Executor;" +
                        "Lorg/ohmyloader/api/hook/Operation;I)Ljava/util/concurrent/CompletableFuture;",
                    extras = listOf(DslValue.IntVal(0)),
                    coerceParams = setOf(0, 1),
                )
                require(1)
            }
        }
    },
    id = "v26_3:shader-compile-executor",
)
