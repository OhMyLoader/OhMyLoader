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
        // The SPIR-V cache. `compileToSpv` is the one place shaderc runs; caching its output turns
        // the second session's ~200 native compiles into memory copies. The include texts are
        // recorded as the source loader reads them, because the loaded `CachedIncludeSource` keeps
        // only a native pointer and the cache key must cover the expanded source.
        classTarget("com/mojang/renderpearl/frontend/shaders/GlslCompiler") {
            method(
                "compileToSpv",
                desc = "(Ljava/lang/String;Ljava/lang/String;" +
                    "Lcom/mojang/renderpearl/api/pipeline/ShaderType;" +
                    "Lnet/minecraft/client/renderer/ShaderDefines;" +
                    "Lcom/mojang/renderpearl/api/pipeline/ShaderSource;)" +
                    "Lcom/mojang/renderpearl/backend/api/SpvModule;",
            ) {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/v26_3/ShaderSpvCache",
                    method = "compileToSpv",
                    desc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;" +
                        "Ljava/lang/Object;Lorg/ohmyloader/api/hook/Operation;I)" +
                        "Lcom/mojang/renderpearl/backend/api/SpvModule;",
                    extras = listOf(DslValue.IntVal(0)),
                    coerceParams = setOf(2, 3, 4),
                )
                require(1)
            }
        }
        classTarget("com/mojang/renderpearl/api/pipeline/ShaderSource\$CachedIncludeSource") {
            method(
                "create",
                desc = "(Lnet/minecraft/resources/Identifier;Ljava/lang/String;)" +
                    "Lcom/mojang/renderpearl/api/pipeline/ShaderSource\$CachedIncludeSource;",
            ) {
                atHead {
                    call(
                        "org/ohmyloader/adapter/v26_3/ShaderSpvCache", "noteInclude",
                        "(Ljava/lang/Object;Ljava/lang/String;)V",
                        listOf(DslValue.Arg(0), DslValue.Arg(1)),
                    )
                }
                require(1)
            }
        }
    },
    id = "v26_3:shader-compile-executor",
)
