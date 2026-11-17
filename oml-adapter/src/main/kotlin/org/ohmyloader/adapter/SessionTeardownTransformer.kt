package org.ohmyloader.adapter

import org.ohmyloader.api.inject.DslValue
import org.ohmyloader.api.inject.injection
import org.ohmyloader.core.transformer.injection.InjectingTransformer
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.MethodInsnNode

/**
 * Registers the two game-owned executors that have no public accessor with the session bridge.
 * The registration is a direct static call injected into the owner class, so teardown never has
 * to reflect on a session class (a JEP 416 accessor would keep the loader's metadata alive).
 */
class SessionTeardownTransformer : InjectingTransformer(
    injection {
        classTarget("net/minecraft/client/sounds/SoundEngineExecutor") {
            method("startUp", desc = "()V") {
                atTail {
                    call(
                        owner = BRIDGE,
                        method = "registerSoundExecutor",
                        desc = "(Lnet/minecraft/client/sounds/SoundEngineExecutor;)V",
                        args = listOf(DslValue.This),
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/server/network/EventLoopGroupHolder") {
            method("createEventLoopGroup", desc = "()Lio/netty/channel/EventLoopGroup;") {
                atTail {
                    transformReturn(
                        owner = BRIDGE,
                        method = "registerEventLoopGroup",
                        desc = "(Lio/netty/channel/EventLoopGroup;)Lio/netty/channel/EventLoopGroup;",
                    )
                }
                require(1)
            }
        }
        classTarget("net/minecraft/util/profiling/jfr/JfrProfiler") {
            method("<clinit>", desc = "()V") {
                atTail {
                    raw { _, _ ->
                        add(
                            FieldInsnNode(
                                Opcodes.GETSTATIC,
                                "net/minecraft/util/profiling/jfr/JfrProfiler",
                                "CUSTOM_EVENTS",
                                "Ljava/util/List;",
                            ),
                        )
                        add(
                            MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "registerJfrCustomEvents",
                                "(Ljava/util/List;)V",
                                false,
                            ),
                        )
                    }
                }
                require(1)
            }
            method("registerPeriodicEvents", desc = "()V") {
                beforeCall(
                    owner = "net/minecraft/util/profiling/jfr/JfrProfiler",
                    name = "addPeriodicEvent",
                    desc = "(Ljava/lang/Class;Ljava/lang/Runnable;)V",
                    opcode = Opcodes.INVOKESTATIC,
                ) {
                    modifyArg(
                        owner = BRIDGE,
                        method = "trackJfrTask",
                        desc = "(Ljava/lang/Runnable;)Ljava/lang/Runnable;",
                        index = 1,
                    )
                }
                require(3)
            }
        }
        classTarget("com/mojang/blaze3d/audio/CallbackDeviceTracker") {
            method("<clinit>", desc = "()V") {
                atTail {
                    raw { _, _ ->
                        add(
                            FieldInsnNode(
                                Opcodes.GETSTATIC,
                                "com/mojang/blaze3d/audio/CallbackDeviceTracker",
                                "SUBSCRIBED_EVENT_TYPES",
                                "[I",
                            ),
                        )
                        add(
                            MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "registerOpenAlEventTypes",
                                "([I)V",
                                false,
                            ),
                        )
                    }
                }
                require(1)
            }
        }
        classTarget("com/mojang/blaze3d/platform/ClientShutdownWatchdog") {
            method("<clinit>", desc = "()V") {
                atTail {
                    raw { _, _ ->
                        add(
                            FieldInsnNode(
                                Opcodes.GETSTATIC,
                                "com/mojang/blaze3d/platform/ClientShutdownWatchdog",
                                "THREAD_COUNTER",
                                "Ljava/util/concurrent/atomic/AtomicInteger;",
                            ),
                        )
                        add(
                            MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "registerWatchdogCounter",
                                "(Ljava/util/concurrent/atomic/AtomicInteger;)V",
                                false,
                            ),
                        )
                    }
                }
                require(1)
            }
        }
        classTarget("net/minecraft/client/telemetry/ClientTelemetryManager") {
            method("<clinit>", desc = "()V") {
                atTail {
                    raw { _, _ ->
                        add(
                            FieldInsnNode(
                                Opcodes.GETSTATIC,
                                "net/minecraft/client/telemetry/ClientTelemetryManager",
                                "EXECUTOR",
                                "Ljava/util/concurrent/Executor;",
                            ),
                        )
                        add(
                            MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                BRIDGE,
                                "registerTelemetryExecutor",
                                "(Ljava/util/concurrent/Executor;)V",
                                false,
                            ),
                        )
                    }
                }
                require(1)
            }
        }
    },
    id = "v26_3:session-teardown",
) {
    companion object {
        private const val BRIDGE = "org/ohmyloader/adapter/ThreadHygieneBridge"
    }
}
