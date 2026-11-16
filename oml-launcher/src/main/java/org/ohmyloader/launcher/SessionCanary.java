package org.ohmyloader.launcher;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.management.ManagementFactory;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The WeakReference probe that decides whether the previous session really became unreachable.
 *
 * <p>A classloader is collectable only when nothing outside it holds a strong reference to the
 * loader or to any of its classes: a lingering mod thread, a Netty handler, a shutdown hook, a
 * common-pool task, or a parent-first static that cached a game object all pin it. The probe never
 * keeps a strong reference itself; {@link #awaitCollected} only asks the GC and reports the threads
 * that still name the session when the answer is no.
 *
 * <p>Phase 1 is observational: a failed collection is logged with diagnostics, while the reboot
 * pipeline does not exist yet. The same primitive is the safety gate for the real reboot — a leak
 * must downgrade to a process restart rather than accumulate Metaspace.
 */
final class SessionCanary {

    // The session replaces System.err with its own log4j-backed stream; after the session's
    // formatter ThreadLocals are cleared, that stream throws. Write diagnostics to the process fd.
    private static final PrintStream RAW_ERR =
        new PrintStream(new FileOutputStream(FileDescriptor.err), true);

    /**
     * Bootstrap classes that keep static {@link MethodHandle}s used by language bootstraps
     * (record equals/hashCode/toString, pattern switches, lambdas, string concat). Once a bootstrap
     * adapts one of those handles to a session record's method type, {@code MethodHandle} caches the
     * adapted view in an {@code asTypeSoftCache} SoftReference. A soft reference survives ordinary
     * GCs (HotSpot only clears it under memory pressure), so that single cached MethodType pins the
     * session class, and through it the whole loader, long after the game stopped.
     */
    private static final String[] BOOTSTRAP_METHOD_HANDLE_HOLDERS = {
        "java.lang.runtime.ObjectMethods",
        "java.lang.runtime.SwitchBootstraps",
        "java.lang.invoke.LambdaMetafactory",
        "java.lang.invoke.StringConcatFactory",
        "java.lang.invoke.ConstantBootstraps",
    };

    private final WeakReference<ClassLoader> session;
    private final ClassLoader supervisor;

    SessionCanary(ClassLoader session, ClassLoader supervisor) {
        this(new WeakReference<>(session), supervisor);
    }

    private SessionCanary(WeakReference<ClassLoader> session, ClassLoader supervisor) {
        this.session = session;
        this.supervisor = supervisor;
    }

    /** The weak probe, for callers that must run the GC wait after their own frames returned. */
    WeakReference<ClassLoader> probe() {
        return session;
    }

    /**
     * Runs the whole probe on a fresh thread that only ever names the {@link WeakReference}.
     *
     * <p>The caller of an in-process reboot still has its frames on the stack while this waits
     * (blocked in {@code join}), and a C2-compiled caller can keep an old loader oop in a register
     * or stack slot across the call. Those oops are roots for the concurrent GC, so the wait loop
     * must not share a thread with them. This thread's stack contains the weak reference, the
     * timeout and the supervisor loader — nothing from the retired session.
     */
    static boolean awaitCollected(
        WeakReference<ClassLoader> session, long timeoutMillis, boolean reboot, ClassLoader supervisor) {
        SessionCanary canary = new SessionCanary(session, supervisor);
        boolean[] verdict = new boolean[1];
        Thread probe = new Thread(() -> verdict[0] = canary.runProbe(timeoutMillis, reboot), "oml-canary-probe");
        probe.setDaemon(true);
        probe.setContextClassLoader(ClassLoader.getSystemClassLoader());
        probe.start();
        try {
            probe.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return verdict[0];
    }

    /**
     * Asks the GC to collect the session and returns whether it disappeared within the timeout.
     * On failure, prints the threads that still hold the session (context classloader or a stack
     * frame defined by it).
     */
    boolean awaitCollected(long timeoutMillis) {
        return awaitCollected(timeoutMillis, false);
    }

    boolean awaitCollected(long timeoutMillis, boolean reboot) {
        return runProbe(timeoutMillis, reboot);
    }

    private boolean runProbe(long timeoutMillis, boolean reboot) {
        releaseThreads(reboot);
        releaseBootstrapMethodHandleCaches();
        runFinalizationOnce(2_000);
        String pauseAfterRelease = System.getProperty("oml.canary.pauseAfterRelease");
        if (pauseAfterRelease != null && !pauseAfterRelease.isBlank()) {
            try {
                RAW_ERR.println("[OMLSupervisor] canary pause after release " + pauseAfterRelease + " ms");
                Thread.sleep(Long.parseLong(pauseAfterRelease));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (NumberFormatException ignored) {
                // Diagnostic property only.
            }
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
            // The game's shutdown path can install its LoggedPrintStream again after the first
            // restore; System.out/err are sticky static roots, so repeat the restore every round.
            OMLBootstrap.restoreConsole();
            System.gc();
            if (collected()) return true;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return collected();
            }
        } while (System.nanoTime() < deadline);

        if (collected()) return true;
        OMLBootstrap.restoreConsole();
        dumpHeapOnFailure();
        System.gc();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        RAW_ERR.println(
            "[OMLSupervisor] post-dump probe: session=" + session.get() + " main=" + SessionControl.mainLoader());
        RAW_ERR.println(
            "[OMLSupervisor] session classloader was not collected within " + timeoutMillis + " ms" +
                " (supervisor=" + (supervisor == null ? "platform" : supervisor) + ")");
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            boolean namesAllocator = false;
            for (StackTraceElement element : entry.getValue()) {
                if (element.getClassName().contains("AdaptivePoolingAllocator")) {
                    namesAllocator = true;
                    break;
                }
            }
            if (namesAllocator || thread.getName().toLowerCase().contains("finalizer")) {
                RAW_ERR.println(
                    "[OMLSupervisor] thread '" + thread.getName() + "' state=" + thread.getState() +
                        " contextClassLoader=" + thread.getContextClassLoader());
                for (StackTraceElement element : entry.getValue()) RAW_ERR.println("    at " + element);
            }
        }
        List<String> leaks = leakedThreads();
        if (leaks.isEmpty()) {
            RAW_ERR.println("[OMLSupervisor]   no thread names the session; a non-thread static or JNI callback holds it");
        } else {
            for (String line : leaks) RAW_ERR.println("[OMLSupervisor]   " + line);
        }
        String pause = System.getProperty("oml.canary.pause");
        if (pause != null && !pause.isBlank()) {
            try {
                RAW_ERR.println("[OMLSupervisor] canary pause " + pause + " ms");
                Thread.sleep(Long.parseLong(pause));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (NumberFormatException ignored) {
                // A malformed diagnostic property must not change the verdict.
            }
        }
        return false;
    }

    /**
     * Evidence for a canary failure: a live heap dump taken before the hard gate throws. The path
     * is overridable for CI (`-Doml.canary.dump=...`); the default lives under the game's
     * working-directory build folder and is reported on the raw stderr.
     */
    private static void dumpHeapOnFailure() {
        String configured = System.getProperty("oml.canary.dump");
        File file = configured == null || configured.isBlank()
            ? new File("build/canary-leak.hprof").getAbsoluteFile()
            : new File(configured).getAbsoluteFile();
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            HotSpotDiagnosticMXBean bean =
                ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            if (bean == null) {
                RAW_ERR.println("[OMLSupervisor] heap dump unavailable: no HotSpotDiagnosticMXBean");
                return;
            }
            if (file.exists() && !file.delete()) {
                RAW_ERR.println("[OMLSupervisor] heap dump target is not replaceable: " + file.getAbsolutePath());
                return;
            }
            bean.dumpHeap(file.getAbsolutePath(), true);
            RAW_ERR.println("[OMLSupervisor] heap dump: " + file.getAbsolutePath());
        } catch (Throwable t) {
            RAW_ERR.println("[OMLSupervisor] heap dump failed: " + t);
        }
    }

    /**
     * Drops the soft MethodHandle caches that language bootstraps filled with session types.
     *
     * <p>The fields live in java.base and are private, and {@code java.lang.invoke} is not opened to
     * the launcher, so this uses {@code sun.misc.Unsafe} only for its field offsets (the {@code
     * theUnsafe} field itself is reflectively reachable because {@code jdk.unsupported} opens {@code
     * sun.misc}). Clearing the reference is ordinary {@link Reference#clear()} — no JVM flag, and
     * the handles themselves stay valid; only the cached adapted views are recomputed on demand.
     */
    private static void releaseBootstrapMethodHandleCaches() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
            Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
            Method getObject = unsafeClass.getMethod("getObject", Object.class, long.class);
            long cacheOffset =
                (Long) objectFieldOffset.invoke(unsafe, MethodHandle.class.getDeclaredField("asTypeSoftCache"));
            int cleared = 0;
            for (String holderName : BOOTSTRAP_METHOD_HANDLE_HOLDERS) {
                Class<?> holder;
                try {
                    holder = Class.forName(holderName);
                } catch (ClassNotFoundException e) {
                    continue;
                }
                for (Field field : holder.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers())) continue;
                    if (!MethodHandle.class.isAssignableFrom(field.getType())) continue;
                    Object handle = getObject.invoke(
                        unsafe, staticFieldBase.invoke(unsafe, field), staticFieldOffset.invoke(unsafe, field));
                    if (!(handle instanceof MethodHandle)) continue;
                    Object cache = getObject.invoke(unsafe, handle, cacheOffset);
                    if (cache instanceof Reference<?> reference) {
                        reference.clear();
                        cleared++;
                    }
                }
            }
            if (cleared > 0) {
                RAW_ERR.println("[OMLSupervisor] cleared " + cleared + " bootstrap MethodHandle cache(s)");
            }
        } catch (Throwable t) {
            // A different JDK may rename the private cache field; the probe still reports honestly.
            RAW_ERR.println("[OMLSupervisor] bootstrap MethodHandle cache release skipped: " + t);
        }
    }

    /**
     * Netty's AdaptivePoolingAllocator overrides finalize(); an unfinalized allocator holds its
     * session-defined class, and through it the whole loader, for as long as it sits on the
     * finalizer queue. The game never runs finalization itself, so ask for it explicitly. A
     * blocked finalizer must not hang the reboot: the helper is a daemon with a bounded join.
     */
    private static void runFinalizationOnce(long timeoutMillis) {
        Thread thread = new Thread(System::runFinalization, "oml-canary-finalization");
        thread.setDaemon(true);
        thread.start();
        try {
            thread.join(timeoutMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (thread.isAlive()) {
            RAW_ERR.println("[OMLSupervisor] finalization did not finish within " + timeoutMillis + " ms");
        }
    }

    /**
     * Last-resort thread cleanup before the collection probe: threads that inherited the old
     * session/main loader as their context loader are reset to the system loader and interrupted,
     * and threads whose stack still names either loader are interrupted. Game executors are shut
     * down by SessionTeardown first; this catches the parent-side daemons (JNA Cleaner, service
     * discovery, test harness threads) that the game never owned.
     */
    void releaseThreads() {
        releaseThreads(false);
    }

    void releaseThreads(boolean reboot) {
        // Run the sweep on a short-lived supervisor thread: every strong `target`/`main` local
        // then dies with that thread's stack before the GC loop starts. Keeping the sweep on the
        // canary thread leaves those oops live in C2's stack slots across the probe.
        Thread cleaner = new Thread(() -> releaseThreadsOnCleaner(reboot), "oml-canary-release");
        cleaner.setDaemon(true);
        cleaner.setContextClassLoader(ClassLoader.getSystemClassLoader());
        cleaner.start();
        try {
            cleaner.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void releaseThreadsOnCleaner(boolean reboot) {
        // The cleaner inherited the render thread's map; clear its own entries before doing
        // anything else so the thread object itself cannot become a session root.
        clearThreadLocals(Thread.currentThread(), session.get(), SessionControl.mainLoader());
        // The game's log4j stream is itself a session object and its formatter state lives in a
        // ThreadLocal we are about to clear; print from the original process streams first.
        OMLBootstrap.restoreConsole();
        ClassLoader target = session.get();
        ClassLoader main = SessionControl.mainLoader();
        if (target == null && main == null) return;
        if (reboot) {
            removeOldShutdownHooks(target, main);
        }
        clearSupervisorReferences(target, main);
        ClassLoader system = ClassLoader.getSystemClassLoader();
        Thread current = Thread.currentThread();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            clearThreadLocals(thread, target, main);
            if (thread == current) continue;
            ClassLoader context = thread.getContextClassLoader();
            boolean namesLoader = context == target || (main != null && context == main);
            if (namesLoader) {
                try {
                    thread.setContextClassLoader(system);
                } catch (SecurityException ignored) {
                    // A protected system thread can still be interrupted below.
                }
            }
            if (namesLoader || names(entry.getValue(), target, main)) {
                thread.interrupt();
            }
        }
        // Do not leave the probe's strong locals in the caller's frame; C2 may inline this method
        // and keep the oop live in a stack slot across the following System.gc() loop.
        target = null;
        main = null;
    }

    /**
     * The render thread survives the session, and its ThreadLocalMap can still hold Session 1
     * values (RenderSystem/GlStateManager caches, profiler state). Neither TCCL nor the stack
     * diagnostics see those values, yet they keep the old loader reachable. Null the values whose
     * runtime class came from the session/main loader; clearing bootstrap-class containers such as
     * log4j's formatter cache would break the supervisor's own diagnostics.
     */
    private static void clearThreadLocals(Thread thread, ClassLoader target, ClassLoader main) {
        if (target == null && main == null) return;
        for (String fieldName : new String[] {"threadLocals", "inheritableThreadLocals"}) {
            try {
                java.lang.reflect.Field mapField = Thread.class.getDeclaredField(fieldName);
                mapField.setAccessible(true);
                Object map = mapField.get(thread);
                if (map == null) continue;
                java.lang.reflect.Field tableField = map.getClass().getDeclaredField("table");
                tableField.setAccessible(true);
                Object[] table = (Object[]) tableField.get(map);
                if (table == null) continue;
                java.lang.reflect.Field valueField = null;
                for (Object entry : table) {
                    if (entry == null) continue;
                    if (valueField == null) {
                        valueField = entry.getClass().getDeclaredField("value");
                        valueField.setAccessible(true);
                    }
                    if (fromLoader(valueField.get(entry), target, main)) {
                        valueField.set(entry, null);
                    }
                }
            } catch (Throwable ignored) {
                // Tests and restricted JVMs may not open java.lang; the runtime launch does.
            }
        }
    }

    private static boolean fromLoader(Object value, ClassLoader target, ClassLoader main) {
        if (value == null) return false;
        Class<?> type = value.getClass();
        ClassLoader loader = type.isArray() ? type.getComponentType().getClassLoader() : type.getClassLoader();
        return loader == target || (main != null && loader == main);
    }

    /**
     * Old shutdown hooks are a classic static root: {@code ApplicationShutdownHooks.hooks} keeps
     * their Thread objects after the session stops, and a hook's context loader/target belongs to
     * the disposable session. Remove only those hooks on an in-process reboot; the final process
     * shutdown must keep the verdict/test hooks.
     */
    private static void removeOldShutdownHooks(ClassLoader target, ClassLoader main) {
        try {
            Class<?> hooksClass = Class.forName("java.lang.ApplicationShutdownHooks");
            java.lang.reflect.Field field = hooksClass.getDeclaredField("hooks");
            field.setAccessible(true);
            Object value = field.get(null);
            if (!(value instanceof Map<?, ?> hooks)) return;
            List<Thread> old = new ArrayList<>();
            for (Object key : hooks.keySet()) {
                if (key instanceof Thread thread && threadInSession(thread, target, main)) {
                    old.add(thread);
                }
            }
            for (Thread thread : old) {
                try {
                    Runtime.getRuntime().removeShutdownHook(thread);
                } catch (IllegalArgumentException | IllegalStateException ignored) {
                    // Already gone or shutdown is in progress.
                }
            }
            if (!old.isEmpty()) {
                RAW_ERR.println("[OMLSupervisor] removed " + old.size() + " old shutdown hook(s)");
            }
        } catch (Throwable ignored) {
            // A restricted runtime can hide ApplicationShutdownHooks; the thread sweep remains.
        }
    }

    private static boolean threadInSession(Thread thread, ClassLoader target, ClassLoader main) {
        ClassLoader typeLoader = thread.getClass().getClassLoader();
        if (typeLoader == target || (main != null && typeLoader == main)) return true;
        ClassLoader context = thread.getContextClassLoader();
        if (context == target || (main != null && context == main)) return true;
        try {
            java.lang.reflect.Field targetField = Thread.class.getDeclaredField("target");
            targetField.setAccessible(true);
            Object runnable = targetField.get(thread);
            if (runnable != null) {
                ClassLoader runnableLoader = runnable.getClass().getClassLoader();
                if (runnableLoader == target || (main != null && runnableLoader == main)) return true;
            }
        } catch (Throwable ignored) {
            // Fall through to the stack check.
        }
        return names(thread.getStackTrace(), target, main);
    }

    /**
     * The supervisor URLClassLoader survives every session, so any of its fields that caches a
     * child loader is a permanent root. The canary heap dump showed the session loader reachable
     * from this object; inspect its fields directly, report the exact field, and clear the cache
     * containers that hold the old loader. The launcher's classes rebuild cache values on demand.
     */
    private static void clearSupervisorReferences(ClassLoader target, ClassLoader main) {
        ClassLoader supervisor = OMLSupervisor.class.getClassLoader();
        if (supervisor == null) return;
        Class<?> type = supervisor.getClass();
        while (type != null && type != Object.class) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(supervisor);
                    String fieldName = type.getSimpleName() + "." + field.getName();
                    if (value == target || value == main) {
                        RAW_ERR.println("[OMLSupervisor] supervisor field " + fieldName + " is the old loader");
                        continue;
                    }
                    if (value instanceof Map<?, ?> map && mapContainsLoader(map, target, main)) {
                        int size = map.size();
                        map.clear();
                        RAW_ERR.println("[OMLSupervisor] cleared supervisor field " + fieldName + " (" + size + " entries)");
                        continue;
                    }
                    if (value instanceof List<?> list) {
                        boolean namesLoader = list.contains(target) || (main != null && list.contains(main));
                        if (!namesLoader) {
                            for (Object element : list) {
                                if (element instanceof Class<?> cls &&
                                    (cls.getClassLoader() == target || (main != null && cls.getClassLoader() == main))) {
                                    namesLoader = true;
                                    break;
                                }
                            }
                        }
                        if (namesLoader) {
                            RAW_ERR.println("[OMLSupervisor] supervisor field " + fieldName + " holds old-session classes/loader");
                        }
                    }
                } catch (Throwable ignored) {
                    // A restricted JVM can hide individual fields; the remaining fields still run.
                }
            }
            type = type.getSuperclass();
        }
    }

    private static boolean mapContainsLoader(Map<?, ?> map, ClassLoader target, ClassLoader main) {
        if (map.containsKey(target) || map.containsValue(target)) return true;
        if (main != null && (map.containsKey(main) || map.containsValue(main))) return true;
        for (Object value : map.values()) {
            if (value == target || value == main) return true;
            if (value instanceof Map<?, ?> nested &&
                (nested.containsKey(target) || nested.containsValue(target) ||
                    (main != null && (nested.containsKey(main) || nested.containsValue(main))))) {
                return true;
            }
            if (value instanceof Class<?> cls &&
                (cls.getClassLoader() == target || (main != null && cls.getClassLoader() == main))) {
                return true;
            }
        }
        return false;
    }

    private static boolean names(StackTraceElement[] stack, ClassLoader target, ClassLoader main) {
        for (StackTraceElement element : stack) {
            if (definedBy(element, target) || definedBy(element, main)) return true;
        }
        return false;
    }

    /**
     * Both layers must be gone: the session URL loader and the game/mod loader OMLCore created as
     * its child. The child is registered weakly, so this check never keeps it alive.
     */
    private boolean collected() {
        return session.refersTo(null) && SessionControl.mainLoaderCleared();
    }

    /**
     * The live threads that still name the session. Package-private for the probe's contract test.
     */
    List<String> leakedThreads() {
        ClassLoader target = session.get();
        ClassLoader main = SessionControl.mainLoader();
        if (target == null && main == null) return List.of();
        List<String> leaked = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            Thread thread = entry.getKey();
            boolean contextTarget = thread.getContextClassLoader() == target;
            boolean contextMain = main != null && thread.getContextClassLoader() == main;
            boolean frame = false;
            for (StackTraceElement element : entry.getValue()) {
                if (definedBy(element, target) || definedBy(element, main)) {
                    frame = true;
                    break;
                }
            }
            if (contextTarget || contextMain || frame) {
                String layer = contextTarget ? "session" : contextMain ? "main" : "frame";
                leaked.add(
                    "thread '" + thread.getName() + "' state=" + thread.getState() +
                        " contextClassLoader=" + layer);
                for (StackTraceElement element : entry.getValue()) leaked.add("    at " + element);
            }
        }
        return leaked;
    }

    private static boolean definedBy(StackTraceElement element, ClassLoader loader) {
        if (loader == null) return false;
        Class<?> type = loadQuietly(element.getClassName(), loader);
        return type != null && type.getClassLoader() == loader;
    }

    private static Class<?> loadQuietly(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
