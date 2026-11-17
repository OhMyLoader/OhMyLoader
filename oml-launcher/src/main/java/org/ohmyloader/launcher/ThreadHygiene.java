package org.ohmyloader.launcher;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Supervisor-owned thread plumbing for the disposable sessions.
 *
 * <p>A JNI global reference can keep a terminated Java {@code Thread} object alive. The loader
 * stays pinned if that Thread's {@code contextClassLoader}, {@code target} or class was defined
 * by the session. Every game-owned worker is therefore created as a plain bootstrap
 * {@code java.lang.Thread} with a supervisor {@link HygieneRunnable} target, or as the
 * supervisor-defined {@link HygieneWorker} ForkJoin thread. The wrapper clears the delegate and
 * restores the system context loader in a finally block, so a native global reference can only
 * keep supervisor/bootstrap objects alive after termination.
 */
public final class ThreadHygiene {

    private static final ClassLoader SYSTEM = ClassLoader.getSystemClassLoader();
    private static final AtomicInteger WORKER_IDS = new AtomicInteger();
    private static final HygieneUEH UEH = new HygieneUEH();
    // The game's Util executors are built here but live in session statics. Their workers are
    // supervisor-owned threads, so they can outlive the session with WorkQueues still holding
    // session tasks; the teardown must shut them down, not merely interrupt them.
    private static final List<ForkJoinPool> POOLS = new CopyOnWriteArrayList<>();

    public static ForkJoinPool newForkJoinPool(String name, int parallelism) {
        String prefix = name == null || name.isBlank() ? "OML-Worker" : name;
        ForkJoinPool.ForkJoinWorkerThreadFactory factory =
            pool -> new HygieneWorker(pool, prefix + "-" + WORKER_IDS.incrementAndGet());
        ForkJoinPool pool = new ForkJoinPool(Math.max(1, parallelism), factory, UEH, true);
        POOLS.add(pool);
        return pool;
    }

    /**
     * Drains every executor created by {@link #newForkJoinPool} and waits for its workers to
     * exit. Called from session teardown while the old session's statics still own these pools.
     */
    public static void shutdownPools() {
        List<ForkJoinPool> pools = new ArrayList<>(POOLS);
        POOLS.clear();
        for (ForkJoinPool pool : pools) pool.shutdownNow();
        for (ForkJoinPool pool : pools) {
            try {
                pool.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!pools.isEmpty()) {
            System.err.println("[ThreadHygiene] shut down " + pools.size() + " game executor(s)");
        }
    }

    public static ThreadFactory wrapFactory(ThreadFactory delegate) {
        return runnable -> {
            Thread probe = delegate.newThread(new NoopRunnable());
            Thread thread = new Thread(probe.getThreadGroup(), new HygieneRunnable(runnable), probe.getName());
            copy(probe, thread);
            return thread;
        };
    }

    public static Thread replace(Thread original) {
        if (original == null) return null;
        // Some factories (SoundEngineExecutor.createThread) start the thread inside the method
        // being wrapped. A replacement returned here would never be started, while the running
        // original is no longer the executor's getRunningThread() — the sound worker then parks
        // forever and SoundEngine.play waits on an unfinished future. Keep a started thread.
        if (original.getState() != Thread.State.NEW) return original;
        Object target = readTarget(original);
        if (!(target instanceof Runnable runnable)) {
            // Do not turn an uninspectable thread into a no-op: keep its task and apply the
            // hygiene it can still get through public setters.
            original.setContextClassLoader(SYSTEM);
            original.setUncaughtExceptionHandler(UEH);
            return original;
        }
        Thread thread = new Thread(
            original.getThreadGroup(),
            new HygieneRunnable(runnable),
            original.getName());
        copy(original, thread);
        return thread;
    }

    public static void startTimerHack() {
        Thread thread = new Thread(
            new HygieneRunnable(() -> {
                while (true) {
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }),
            "Timer hack thread");
        thread.setDaemon(true);
        thread.setContextClassLoader(SYSTEM);
        thread.setUncaughtExceptionHandler(UEH);
        thread.start();
    }

    /**
     * The integrated server's "Server thread" is created by vanilla and outlives the point where
     * its owner is cleared. Clear its JNI-visible fields on the way out of runServer: target and
     * blocker point at the IntegratedServer/Level objects, TCCL at the OML loader, and its
     * ThreadLocals can hold session state.
     */
    public static void clearCurrentServerThread() {
        Thread thread = Thread.currentThread();
        thread.setContextClassLoader(SYSTEM);
        thread.setUncaughtExceptionHandler(UEH);
        clearThreadTask(thread);
        clearThreadLocals(thread);
    }

    /**
     * JDK 27 moved the thread's target into {@code Thread$FieldHolder.task}; older releases
     * exposed {@code Thread.target}/{@code blocker}. Clear either shape, because a session
     * Runnable or blocker keeps the whole loader reachable from the dead Server thread.
     */
    private static void clearThreadTask(Thread thread) {
        try {
            Field holderField = Thread.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            Object holder = holderField.get(thread);
            if (holder != null) {
                for (String name : new String[] {"task", "target", "blocker"}) {
                    try {
                        Field field = holder.getClass().getDeclaredField(name);
                        field.setAccessible(true);
                        field.set(holder, null);
                    } catch (NoSuchFieldException ignored) {
                        // Field shape of another release.
                    }
                }
            }
        } catch (Throwable ignored) {
            // A restricted VM can hide the holder; TCCL/UEH/ThreadLocal cleanup still applies.
        }
        for (String name : new String[] {"target", "blocker"}) {
            try {
                Field field = Thread.class.getDeclaredField(name);
                field.setAccessible(true);
                field.set(thread, null);
            } catch (Throwable ignored) {
                // JDK 27 has neither field on Thread itself.
            }
        }
    }

    private static void clearThreadLocals(Thread thread) {
        for (String fieldName : new String[] {"threadLocals", "inheritableThreadLocals"}) {
            try {
                Field mapField = Thread.class.getDeclaredField(fieldName);
                mapField.setAccessible(true);
                Object map = mapField.get(thread);
                if (map == null) continue;
                Field tableField = map.getClass().getDeclaredField("table");
                tableField.setAccessible(true);
                Object[] table = (Object[]) tableField.get(map);
                if (table == null) continue;
                Field valueField = null;
                for (Object entry : table) {
                    if (entry == null) continue;
                    if (valueField == null) {
                        valueField = entry.getClass().getDeclaredField("value");
                        valueField.setAccessible(true);
                    }
                    valueField.set(entry, null);
                }
            } catch (Throwable ignored) {
                // Best effort; the JVM may not open Thread internals.
            }
        }
    }

    private static void copy(Thread source, Thread target) {
        target.setDaemon(source.isDaemon());
        target.setPriority(source.getPriority());
        target.setContextClassLoader(SYSTEM);
        target.setUncaughtExceptionHandler(UEH);
    }

    private static Object readTarget(Thread thread) {
        try {
            Field holderField = Thread.class.getDeclaredField("holder");
            holderField.setAccessible(true);
            Object holder = holderField.get(thread);
            if (holder != null) {
                for (String name : new String[] {"task", "target"}) {
                    try {
                        Field field = holder.getClass().getDeclaredField(name);
                        field.setAccessible(true);
                        Object value = field.get(holder);
                        if (value != null) return value;
                    } catch (NoSuchFieldException ignored) {
                        // Try the next field shape.
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the pre-27 Thread.target.
        }
        try {
            Field field = Thread.class.getDeclaredField("target");
            field.setAccessible(true);
            return field.get(thread);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class NoopRunnable implements Runnable {
        @Override public void run() {}
    }

    public static final class HygieneRunnable implements Runnable {
        private volatile Runnable delegate;

        public HygieneRunnable(Runnable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void run() {
            Runnable current = delegate;
            try {
                if (current != null) current.run();
            } finally {
                delegate = null;
                Thread thread = Thread.currentThread();
                thread.setContextClassLoader(SYSTEM);
                clearFastThreadLocals(thread);
            }
        }

        private static void clearFastThreadLocals(Thread thread) {
            try {
                if (!thread.getClass().getName().equals("io.netty.util.concurrent.FastThreadLocalThread")) return;
                Class<?> type = Class.forName(
                    "io.netty.util.concurrent.FastThreadLocal",
                    true,
                    thread.getClass().getClassLoader());
                type.getMethod("removeAll").invoke(null);
            } catch (Throwable ignored) {
                // Netty may not be present, or may use its JDK ThreadLocal fallback.
            }
        }
    }

    private static final class HygieneWorker extends ForkJoinWorkerThread {
        HygieneWorker(ForkJoinPool pool, String name) {
            super(pool);
            setName(name);
            setContextClassLoader(SYSTEM);
            setUncaughtExceptionHandler(UEH);
        }

        @Override
        public void run() {
            try {
                super.run();
            } finally {
                setContextClassLoader(SYSTEM);
            }
        }
    }

    private static final class HygieneUEH implements Thread.UncaughtExceptionHandler {
        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            System.err.println("[ThreadHygiene] uncaught exception in " + thread.getName() + ": " + error);
        }
    }

    private ThreadHygiene() {}
}
