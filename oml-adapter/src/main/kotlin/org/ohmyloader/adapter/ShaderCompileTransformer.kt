package org.ohmyloader.adapter

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * The RenderPearl shader-compile hooks. The frontend's async pipeline compile reaches Shaderc
 * through an FFM downcall/upcall chain that is unstable on a shared ForkJoin worker's stack; the
 * dedicated [ShaderCompileExecutor] runs those tasks on its own large-stack threads.
 */
class ShaderCompileTransformer : InjectingTransformer(
    injection {
        classTarget("com/mojang/blaze3d/pipeline/PipelineBuilder") {
            method(
                "compilePipeline",
                desc = "(Lcom/mojang/blaze3d/pipeline/RenderPipeline;" +
                    "Lcom/mojang/renderpearl/api/pipeline/RenderPassLayout\$Subpass;" +
                    "Lcom/mojang/blaze3d/pipeline/ShaderSource;" +
                    "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",
            ) {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/ShaderCompileExecutor",
                    method = "compilePipeline",
                    desc = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;" +
                        "Ljava/util/concurrent/Executor;" +
                        "Lorg/ohmyloader/api/hook/Operation;I)Ljava/util/concurrent/CompletableFuture;",
                    extras = listOf(DslValue.IntVal(0)),
                    coerceParams = setOf(0, 1, 2),
                )
                require(1)
            }
        }
        // The SPIR-V cache. `compileToSpv` is the one place shaderc runs; caching its output turns
        // the second session's ~200 native compiles into memory copies. The include texts are
        // recorded as the source loader reads them, because the loaded `CachedIncludeSource` keeps
        // only a native pointer and the cache key must cover the expanded source.
        classTarget("com/mojang/blaze3d/pipeline/GlslCompiler") {
            method(
                "compileToSpv",
                desc = "(Ljava/lang/String;Ljava/lang/String;" +
                    "Lcom/mojang/renderpearl/api/pipeline/ShaderType;" +
                    "Lnet/minecraft/client/renderer/ShaderDefines;" +
                    "Lcom/mojang/blaze3d/pipeline/ShaderSource;" +
                    "Lcom/mojang/renderpearl/api/device/DeviceInfo;)" +
                    "Ljava/nio/ByteBuffer;",
            ) {
                wrapMethod(
                    owner = "org/ohmyloader/adapter/ShaderSpvCache",
                    method = "compileToSpv",
                    desc = "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;" +
                        "Ljava/lang/Object;Ljava/lang/Object;Lorg/ohmyloader/api/hook/Operation;I)" +
                        "Ljava/nio/ByteBuffer;",
                    extras = listOf(DslValue.IntVal(0)),
                    coerceParams = setOf(2, 3, 4, 5),
                )
                require(1)
            }
        }
        classTarget("com/mojang/blaze3d/pipeline/ShaderSource\$CachedIncludeSource") {
            method(
                "create",
                desc = "(Lnet/minecraft/resources/Identifier;Ljava/lang/String;)" +
                    "Lcom/mojang/blaze3d/pipeline/ShaderSource\$CachedIncludeSource;",
            ) {
                atHead {
                    call(
                        "org/ohmyloader/adapter/ShaderSpvCache", "noteInclude",
                        "(Ljava/lang/Object;Ljava/lang/String;)V",
                        listOf(DslValue.Arg(0), DslValue.Arg(1)),
                    )
                }
                require(1)
            }
        }
    },
    id = "oml:shader-compile-executor",
)
