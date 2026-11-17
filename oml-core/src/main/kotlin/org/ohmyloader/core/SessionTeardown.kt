package org.ohmyloader.core

import org.ohmyloader.api.OmlLog
import org.ohmyloader.core.hook.HookRegistry
import org.ohmyloader.core.spi.IAdapter
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Session-side teardown for the executors the vanilla close path leaves behind.
 *
 * <p>Game-owned classes are torn down by the version adapter with typed calls; this class cannot
 * name them and must not reflect on them, because a JEP 416 accessor created here can keep the
 * disposable loader alive in VM metadata. Library/JDK cleanup stays here behind reflective
 * fallbacks, where a missing member degrades to a logged skip. The supervisor's TCCL sweep runs
 * afterwards and deals with threads that live outside the game classes.
 */
internal object SessionTeardown {

    fun run(loader: ClassLoader, adapter: IAdapter? = null, clientSide: Boolean = true) {
        adapter?.shutdownSessionResources(clientSide)
        HookRegistry.releaseForTeardown()
        shutdownGameExecutors(loader)
        releaseTelemetryExecutor(adapter, clientSide)
        releaseJfr(loader, adapter)
        releaseLwjglCallbacks(loader, adapter)
        resetLwjglDebugStream(loader)
        interruptTimerHack()
    }

    /**
     * ClientTelemetryManager.EXECUTOR is a static newSingleThreadExecutor. Its JDK
     * AutoShutdownDelegatedExecutorService registers a Cleaner whose action captures the
     * ThreadPoolExecutor; the executor's threadFactory is a lambda defined by this session.
     * The Cleaner is a JDK root, so the loader can never go away while that factory is held.
     * Shut the executor down and replace its factory with the bootstrap default.
     */
    private fun releaseTelemetryExecutor(adapter: IAdapter?, clientSide: Boolean) {
        runCatching {
            val executor: ExecutorService = adapter?.telemetryExecutor(clientSide) ?: return
            executor.shutdownNow()
            runCatching {
                executor.awaitTermination(2, TimeUnit.SECONDS)
            }
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val objectFieldOffset = unsafeType.getMethod("objectFieldOffset", Field::class.java)
            val getObject = unsafeType.getMethod("getObject", Any::class.java, Long::class.javaPrimitiveType)
            val putObject = unsafeType.getMethod(
                "putObject",
                Any::class.java,
                Long::class.javaPrimitiveType,
                Any::class.java,
            )
            var delegate: Any? = executor
            val wrapperField = findField(executor.javaClass, "e")
            if (wrapperField != null) {
                val offset = objectFieldOffset.invoke(unsafe, wrapperField) as Long
                val value = getObject.invoke(unsafe, executor, offset)
                if (value is java.util.concurrent.ThreadPoolExecutor) delegate = value
            }
            if (delegate is java.util.concurrent.ThreadPoolExecutor) {
                val factoryField = java.util.concurrent.ThreadPoolExecutor::class.java
                    .getDeclaredField("threadFactory")
                val offset = objectFieldOffset.invoke(unsafe, factoryField) as Long
                putObject.invoke(unsafe, delegate, offset, java.util.concurrent.Executors.defaultThreadFactory())
                OmlLog.info("Teardown", "telemetry executor detached from session")
            }
        }.onFailure { OmlLog.warn("Teardown", "telemetry executor release failed: $it") }
    }

    private fun findField(type: Class<*>, name: String): Field? {
        var current: Class<*>? = type
        while (current != null) {
            try {
                return current.getDeclaredField(name)
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        return null
    }

    /**
     * LWJGL callbacks are libffi closures whose user_data is a JNI global reference to the
     * callback object. SDL/GL/OpenAL keep the function pointer after the game stops, and the
     * callback's delegate is a lambda defined by this session, so the JNI global pins the loader.
     * Walk LWJGL's closure registry, find the closures whose callback graph names this session,
     * unregister the process-global ones, and free the closures (which deletes the JNI global).
     */
    private fun releaseLwjglCallbacks(loader: ClassLoader, adapter: IAdapter?) {
        runCatching {
            val callbackType = Class.forName("org.lwjgl.system.Callback", true, loader)
            val get = callbackType.getMethod("get", Long::class.javaPrimitiveType)
            val free = callbackType.getMethod("free", Long::class.javaPrimitiveType)
            val upcalls = Class.forName("org.lwjgl.system.Upcalls", true, loader)
            // JDK 25+ loads the multi-release FFM version (UPCALL_REGISTRY/Upcall records); the
            // base class on older JDKs uses the libffi CLOSURE_REGISTRY wrapper.
            val registryField = runCatching { upcalls.getDeclaredField("UPCALL_REGISTRY") }
                .recoverCatching { upcalls.getDeclaredField("CLOSURE_REGISTRY") }
                .getOrThrow()
            val registry = registryField.apply { isAccessible = true }.get(null)
            OmlLog.info("Teardown", "LWJGL closure registry: ${registry?.javaClass?.name}")
            val map = when (registry) {
                is Map<*, *> -> registry
                else -> registry?.javaClass?.getDeclaredField("map")?.apply { isAccessible = true }
                    ?.get(registry) as? Map<*, *>
            } ?: return
            OmlLog.info("Teardown", "LWJGL closures=${map.size}")
            val addresses = map.keys.filterIsInstance<Long>()
            val sessionLoaders = setOfNotNull(loader, loader.parent)
            fun callbackFor(address: Long): Any? {
                val entry = map[address]
                if (entry != null) {
                    val accessor = runCatching {
                        entry.javaClass.getDeclaredMethod("javaCallback").apply { isAccessible = true }
                    }.getOrNull()
                    if (accessor != null) return runCatching { accessor.invoke(entry) }.getOrNull()
                }
                return runCatching { get.invoke(null, address) }.getOrNull()
            }
            val owned = addresses.filter { address ->
                val callback = callbackFor(address) ?: return@filter false
                callbackUsesSession(callback, sessionLoaders)
            }
            if (owned.isEmpty()) {
                OmlLog.info("Teardown", "no session LWJGL callbacks (closures=${addresses.size})")
                return
            }
            unregisterSdlLog()
            unregisterOpenAlEvents(loader, adapter)
            for (address in owned) {
                val callback = callbackFor(address)
                runCatching { free.invoke(null, address) }
                OmlLog.info(
                    "Teardown",
                    "released LWJGL callback ${callback?.javaClass?.name ?: "?"} closure=0x${address.toString(16)}",
                )
            }
        }.onFailure { OmlLog.warn("Teardown", "LWJGL callback release failed: $it") }
    }

    private fun callbackUsesSession(callback: Any, sessionLoaders: Set<ClassLoader>): Boolean {
        val seen = HashSet<Any>()
        fun scan(value: Any?, depth: Int): Boolean {
            if (value == null || depth > 3 || !seen.add(value)) return false
            if (value.javaClass.classLoader in sessionLoaders) return true
            var type: Class<*>? = value.javaClass
            while (type != null) {
                for (field in type.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    if (runCatching { field.isAccessible = true }.isFailure) continue
                    val nested = runCatching { field.get(value) }.getOrNull() ?: continue
                    if (scan(nested, depth + 1)) return true
                }
                type = type.superclass
            }
            return false
        }
        return scan(callback, 0)
    }

    private fun unregisterSdlLog() {
        runCatching {
            // The public overload null-checks the interface; the native entry point accepts 0/0,
            // which restores SDL's default log output before the closure is freed.
            Class.forName("org.lwjgl.sdl.SDLLog")
                .getMethod("nSDL_SetLogOutputFunction", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                .invoke(null, 0L, 0L)
        }
    }

    private fun unregisterOpenAlEvents(loader: ClassLoader, adapter: IAdapter?) {
        runCatching {
            val events = Class.forName("org.lwjgl.openal.SOFTSystemEvents", true, loader)
            events.getMethod("nalcEventCallbackSOFT", Long::class.javaPrimitiveType, Long::class.javaPrimitiveType)
                .invoke(null, 0L, 0L)
            runCatching {
                val types = adapter?.openAlEventTypes() ?: return@runCatching
                events.getMethod(
                    "alcEventControlSOFT",
                    IntArray::class.java,
                    Boolean::class.javaPrimitiveType,
                ).invoke(null, types, false)
            }
        }
    }

    /**
     * LWJGL is persistent, but the game replaces APIUtil.DEBUG_STREAM (public static final) with
     * a Blaze3D capturing stream defined by this session. The field is final, so reset it through
     * Unsafe; the new session's NativeLibrariesBootstrap installs its own stream again.
     */
    private fun resetLwjglDebugStream(loader: ClassLoader) {
        runCatching {
            val apiUtil = Class.forName("org.lwjgl.system.APIUtil", true, loader)
            val field = apiUtil.getDeclaredField("DEBUG_STREAM")
            val value = field.get(null)
            if (value == null || value.javaClass.classLoader == apiUtil.classLoader) return
            val replacement = java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true)
            // The game also sets the Configuration object that backs the resolved static.
            runCatching {
                val configuration = Class.forName("org.lwjgl.system.Configuration", true, loader)
                val debugStream = configuration.getField("DEBUG_STREAM").get(null)
                debugStream.javaClass.getMethod("set", Any::class.java).invoke(debugStream, replacement)
            }
            val unsafeType = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val base = unsafeType.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
            val offset = unsafeType.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field) as Long
            unsafeType.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                .invoke(unsafe, base, offset, replacement)
            OmlLog.info("Teardown", "LWJGL debug stream detached from session")
        }.onFailure { OmlLog.warn("Teardown", "LWJGL debug stream reset failed") }
    }

    /**
     * JFR is a process-wide JDK subsystem. JfrProfiler registers a FlightRecorderListener and
     * three periodic-event Runnables (and registers its custom event classes) in JDK statics;
     * none of that is withdrawn by the vanilla close path, so the listener class pins this
     * session's loader. Remove the session registrations before the canary probes.
     *
     * <p>PlatformRecorder.changeListeners is package-private in jdk.jfr.internal and the module
     * does not open it; Unsafe is used only to read that list, and removal goes through the
     * public FlightRecorder API.
     */
    private fun releaseJfr(loader: ClassLoader, adapter: IAdapter?) {
        runCatching {
            val flightRecorder = Class.forName("jdk.jfr.FlightRecorder")
            // isInitialized() stays false when the game registers through the public listener
            // API alone, so it cannot gate this cleanup; removeListener works either way.
            val listenerType = Class.forName("jdk.jfr.FlightRecorderListener")
            val removeListener = flightRecorder.getMethod("removeListener", listenerType)
            val sessionLoaders = setOfNotNull(loader, loader.parent)

            val unsafeType = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeType.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val staticFieldBase = unsafeType.getMethod("staticFieldBase", Field::class.java)
            val staticFieldOffset = unsafeType.getMethod("staticFieldOffset", Field::class.java)
            val getObject = unsafeType.getMethod(
                "getObject",
                Any::class.java,
                Long::class.javaPrimitiveType,
            )
            val listenersField = Class.forName("jdk.jfr.internal.PlatformRecorder")
                .getDeclaredField("changeListeners")
            val base = staticFieldBase.invoke(unsafe, listenersField)
            val offset = staticFieldOffset.invoke(unsafe, listenersField) as Long
            @Suppress("UNCHECKED_CAST")
            val listeners = getObject.invoke(unsafe, base, offset) as? MutableList<Any?> ?: return
            val oldListeners = listeners.filter { it != null && it.javaClass.classLoader in sessionLoaders }
            for (listener in oldListeners) {
                runCatching { removeListener.invoke(null, listener) }
            }
            if (oldListeners.isNotEmpty()) {
                synchronized(listeners) { listeners.removeAll(oldListeners.toSet()) }
                OmlLog.info("Teardown", "removed ${oldListeners.size} JFR listener(s)")
            }

            val removePeriodic = flightRecorder.getMethod("removePeriodicEvent", Runnable::class.java)
            for (task in adapter?.jfrPeriodicTasks().orEmpty()) {
                runCatching { removePeriodic.invoke(null, task) }
            }
            val unregister = flightRecorder.getMethod("unregister", Class::class.java)
            for (event in adapter?.jfrCustomEvents().orEmpty()) {
                runCatching { unregister.invoke(null, event) }
            }
            OmlLog.info("Teardown", "JFR session registrations released")
        }.onFailure { OmlLog.warn("Teardown", "JFR registration release failed") }
    }

    /**
     * The Util executors are supervisor-created ForkJoinPools, so interrupting their workers is
     * not enough: a worker parked in getTask keeps the pool's WorkQueue (and any queued session
     * Runnable) on its stack after the session stops. Shut the pools down and wait for the workers
     * to leave before the supervisor starts probing.
     */
    private fun shutdownGameExecutors(loader: ClassLoader) {
        runCatching {
            val hygiene = Class.forName("org.ohmyloader.launcher.ThreadHygiene", true, loader)
            hygiene.getMethod("shutdownPools").invoke(null)
            OmlLog.info("Teardown", "game executors shut down")
        }.onFailure { OmlLog.warn("Teardown", "game executor shutdown failed") }
    }

    private fun interruptTimerHack() {
        for (thread in Thread.getAllStackTraces().keys) {
            if (thread.name == "Timer hack thread") thread.interrupt()
        }
    }
}
