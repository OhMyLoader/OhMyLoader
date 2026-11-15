package org.ohmyloader.launcher;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;
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

    private final WeakReference<ClassLoader> session;
    private final ClassLoader supervisor;

    SessionCanary(ClassLoader session, ClassLoader supervisor) {
        this.session = new WeakReference<>(session);
        this.supervisor = supervisor;
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
        releaseThreads(reboot);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        do {
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
        dumpHeapOnFailure();
        RAW_ERR.println(
            "[OMLSupervisor] session classloader was not collected within " + timeoutMillis + " ms" +
                " (supervisor=" + (supervisor == null ? "platform" : supervisor) + ")");
        List<String> leaks = leakedThreads();
        if (leaks.isEmpty()) {
            RAW_ERR.println("[OMLSupervisor]   no thread names the session; a non-thread static or JNI callback holds it");
        } else {
            for (String line : leaks) RAW_ERR.println("[OMLSupervisor]   " + line);
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
            bean.dumpHeap(file.getAbsolutePath(), true);
            RAW_ERR.println("[OMLSupervisor] heap dump: " + file.getAbsolutePath());
        } catch (Throwable t) {
            RAW_ERR.println("[OMLSupervisor] heap dump failed: " + t);
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
        return session.get() == null && SessionControl.mainLoader() == null;
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
