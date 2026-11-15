package org.ohmyloader.launcher;

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
        releaseThreads();
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
        System.err.println(
            "[OMLSupervisor] session classloader was not collected within " + timeoutMillis + " ms" +
                " (supervisor=" + (supervisor == null ? "platform" : supervisor) + ")");
        List<String> leaks = leakedThreads();
        if (leaks.isEmpty()) {
            System.err.println("[OMLSupervisor]   no thread names the session; a non-thread static or JNI callback holds it");
        } else {
            for (String line : leaks) System.err.println("[OMLSupervisor]   " + line);
        }
        return false;
    }

    /**
     * Last-resort thread cleanup before the collection probe: threads that inherited the old
     * session/main loader as their context loader are reset to the system loader and interrupted,
     * and threads whose stack still names either loader are interrupted. Game executors are shut
     * down by SessionTeardown first; this catches the parent-side daemons (JNA Cleaner, service
     * discovery, test harness threads) that the game never owned.
     */
    void releaseThreads() {
        ClassLoader target = session.get();
        ClassLoader main = SessionControl.mainLoader();
        if (target == null && main == null) return;
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
     * runtime class came from the session/main loader; the map's weak keys and probing layout stay
     * intact.
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
