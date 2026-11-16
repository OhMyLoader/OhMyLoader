package org.ohmyloader.adapter.v26_3

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer

/**
 * 26.3-only thread hygiene. Game executors and Netty event loops create workers while the render
 * thread still carries the disposable OML class loader; a JNI global reference can then keep the
 * dead Thread object (and its loader) alive forever. Every worker is replaced with a supervisor
 * ThreadHygiene wrapper: plain bootstrap Thread or supervisor ForkJoin worker, supervisor target
 * and UEH, system TCCL, and a finally block that clears the delegate.
 */
class ThreadHygieneTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/util/Util") {
            method("makeExecutor", desc = "(Ljava/lang/String;)Lnet/minecraft/TracingExecutor;") {
                wrapMethod(
                    owner = BRIDGE,
                    method = "makeExecutor",
                    desc = "(Ljava/lang/String;Lorg/ohmyloader/api/hook/Operation;I)Lnet/minecraft/TracingExecutor;",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
            method("makeIoExecutor", desc = "(Ljava/lang/String;Z)Lnet/minecraft/TracingExecutor;") {
                wrapMethod(
                    owner = BRIDGE,
                    method = "makeIoExecutor",
                    desc = "(Ljava/lang/String;ZLorg/ohmyloader/api/hook/Operation;I)Lnet/minecraft/TracingExecutor;",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
            method("startTimerHackThread", desc = "()V") {
                wrapMethod(
                    owner = BRIDGE,
                    method = "startTimerHack",
                    desc = "(Lorg/ohmyloader/api/hook/Operation;I)V",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
        }
        classTarget("net/minecraft/server/MinecraftServer") {
            method("runServer", desc = "()V") {
                atTail {
                    call(BRIDGE, "clearServerThread", "()V")
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/sounds/SoundEngineExecutor") {
            method("createThread", desc = "()Ljava/lang/Thread;") {
                wrapMethod(
                    owner = BRIDGE,
                    method = "soundThread",
                    desc = "(Lorg/ohmyloader/api/hook/Operation;I)Ljava/lang/Thread;",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
        }
        classTarget("net/minecraft/server/network/EventLoopGroupHolder") {
            method("createThreadFactory", desc = "()Ljava/util/concurrent/ThreadFactory;") {
                wrapMethod(
                    owner = BRIDGE,
                    method = "threadFactory",
                    desc = "(Lorg/ohmyloader/api/hook/Operation;I)Ljava/util/concurrent/ThreadFactory;",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
        }
        classTarget("io/netty/util/concurrent/DefaultThreadFactory") {
            method("newThread", desc = "(Ljava/lang/Runnable;)Ljava/lang/Thread;") {
                wrapMethod(
                    owner = BRIDGE,
                    method = "defaultThread",
                    desc = "(Ljava/lang/Runnable;Lorg/ohmyloader/api/hook/Operation;I)Ljava/lang/Thread;",
                    extras = listOf(DslValue.IntVal(0)),
                )
                require(1)
            }
        }
    },
    id = "v26_3:thread-hygiene",
) {
    companion object {
        private const val BRIDGE = "org/ohmyloader/adapter/v26_3/ThreadHygieneBridge"
    }
}
