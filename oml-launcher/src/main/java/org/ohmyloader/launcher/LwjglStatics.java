package org.ohmyloader.launcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;

/**
 * Drops the LWJGL state that outlives a session.
 *
 * <p>LWJGL is not session-scoped. Its classes live on the supervisor's classpath, so both sessions
 * resolve the *same* {@code GL} and {@code VK} classes and share their static fields. A reboot then
 * inherits the retiring session's bindings even though its GPU context, its function pointer tables
 * and its native library handles are gone.
 *
 * <p>Checked against LWJGL 3.4.3's bytecode, the shared state that matters is small and entirely
 * per-class — {@code Library} and {@code MemoryUtil} hold no method-handle or address cache of
 * their own in this version, so there is no FFM stub table to invalidate. What does survive is:
 *
 * <ul>
 *   <li>non-final static capability, router and dispatch objects on {@code GL}/{@code AL}/{@code
 *       ALC}, which cache addresses that belonged to the retiring session's context;
 *   <li>a non-final static {@code functionProvider} on {@code VK}, which blocks the next session's
 *       own {@code VK.create()} (the game calls it again, inside a catch, so a stale provider turns
 *       Vulkan silently unavailable);
 *   <li>static {@code ThreadLocal}s, which outlive a classloader swap because the render thread is
 *       the *same* Java thread across a reboot.
 * </ul>
 *
 * <p>{@code GL.functionProvider} and {@code ALC.functionProvider} are not in that list — see
 * {@link #BINDINGS}. They are the game's own loaded-library flags and clearing them aborts the next
 * session before it has a window.
 *
 * <p>{@code GL.icd}, {@code AL.icd} and {@code ALC.icd} are deliberately left alone. {@code
 * GL.setCapabilities} and {@code AL.setCurrentProcess} forward to {@code icd.set(...)} with no null
 * check, so clearing the field would turn the *next* session's own setup into a
 * {@code NullPointerException}; {@code ALC.setCapabilities} recreates its {@code ICDStatic} lazily
 * but the other two do not. What those two setters do instead — install a null capability table — is
 * the state LWJGL's own {@code destroy()} leaves behind, and it turns every entry point into the
 * "function missing" abort rather than into a call through a dead address.
 *
 * <p>The reset is deliberately non-destructive: {@code VK.destroy()} and {@code GL.destroy()} call
 * {@code free()} on a provider whose native library the session may already have unloaded, turning a
 * stale binding into a double free. Clearing the field is what the next session needs.
 */
final class LwjglStatics {

    /**
     * Binding classes and the non-final static references they keep.
     *
     * <p>Every name here was read off the 3.4.3 jars. A missing field is reported, not ignored: if
     * LWJGL renames one, this list is what silently stops resetting it.
     *
     * <p>{@code GL.functionProvider} and {@code ALC.functionProvider} are deliberately **absent**.
     * They look like the same kind of residue and are not: the game reads them as its own "this
     * library is loaded" flag. {@code NativeLibrariesBootstrap.loadOpenGL()} is literally
     * {@code Objects.requireNonNull(GL.getFunctionProvider())}, and {@code loadOpenAL()} is the
     * {@code ALC} twin — both run at the start of every session, and {@code GL}'s class initialiser
     * (which is what loads the library) does not run a second time inside the same process. Clearing
     * the field therefore turns the next session's first library check into a
     * {@code NullPointerException} before a window exists. {@code VK.functionProvider} is the
     * opposite case: the game re-runs {@code VK.create()} itself, wrapped in a catch, and that call
     * refuses to run while the field is set.
     */
    private static final String[][] BINDINGS = {
        {"org.lwjgl.opengl.GL", "capabilitiesWGL", "capabilitiesGLX", "capabilitiesGLXClient"},
        {"org.lwjgl.vulkan.VK", "functionProvider", "globalCommands"},
        {"org.lwjgl.openal.AL", "processCaps"},
        {"org.lwjgl.openal.ALC", "router"},
    };

    /** Classes whose static {@code ThreadLocal}s belong to the retiring session. */
    private static final String[] THREAD_LOCAL_OWNERS = {
        "org.lwjgl.system.MemoryStack",
        "org.lwjgl.system.MemoryUtil",
        "org.lwjgl.system.Callback",
        "org.lwjgl.opengl.GL",
        "org.lwjgl.openal.AL",
        "org.lwjgl.openal.ALC",
        "org.lwjgl.sdl.SDLVideo",
        "org.lwjgl.vulkan.VK",
    };

    private LwjglStatics() {}

    /** Clears every LWJGL binding a session can install. Never throws. */
    static void reset(ClassLoader loader) {
        int cleared = 0;
        cleared += clearBindings(loader);
        cleared += clearThreadLocals(loader);
        cleared += releaseFunctionMissingTable(loader);
        System.out.println("[OMLLwjgl] cleared " + cleared + " binding(s) for the next session");
    }

    /**
     * Nulls the non-final static references of each binding class listed in {@link #BINDINGS}.
     *
     * <p>The two capability setters are called first where they exist, because a setter also resets
     * state that is only reachable through it — {@code GL.setCapabilities(null)} zeroes the native
     * capability pointer the render thread's TLS carries, and {@code AL.setCurrentProcess(null)}
     * clears the process-wide capability cache. The field sweep afterwards covers what the setters
     * do not touch.
     */
    private static int clearBindings(ClassLoader loader) {
        int cleared = 0;
        invokeSetter(loader, "org.lwjgl.opengl.GL", "setCapabilities", "org.lwjgl.opengl.GLCapabilities");
        invokeSetter(loader, "org.lwjgl.openal.AL", "setCurrentProcess", "org.lwjgl.openal.ALCapabilities");
        invokeSetter(loader, "org.lwjgl.openal.ALC", "setCapabilities", "org.lwjgl.openal.ALCCapabilities");

        for (String[] binding : BINDINGS) {
            Class<?> owner = load(binding[0], loader);
            if (owner == null) continue;
            for (int i = 1; i < binding.length; i++) {
                cleared += clearStatic(owner, binding[0], binding[i]);
            }
        }
        return cleared;
    }

    /**
     * The `(Capabilities)V` reset a binding exposes, invoked with null.
     *
     * <p>Matched by name and arity rather than by an exact signature: the parameter type is the
     * class named in {@code capabilities}, and asking for it directly fails on a version that moved
     * the type without moving the method.
     */
    private static void invokeSetter(ClassLoader loader, String ownerName, String setter, String capabilitiesName) {
        Class<?> owner = load(ownerName, loader);
        Class<?> capabilities = load(capabilitiesName, loader);
        if (owner == null || capabilities == null) return;
        try {
            owner.getMethod(setter, capabilities).invoke(null, new Object[] {null});
        } catch (NoSuchMethodException moved) {
            System.err.println("[OMLLwjgl] " + ownerName + "." + setter + " no longer takes a " + capabilitiesName);
        } catch (Throwable failed) {
            System.err.println("[OMLLwjgl] " + ownerName + "." + setter + "(null) failed: " + failed);
        }
    }

    private static int clearStatic(Class<?> owner, String ownerName, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            if (Modifier.isFinal(field.getModifiers())) {
                // A final binding is a compile-time constant or a handle the next session reuses;
                // only a non-final one can hold a stale library.
                return 0;
            }
            field.setAccessible(true);
            boolean wasSet = field.get(null) != null;
            field.set(null, null);
            return wasSet ? 1 : 0;
        } catch (NoSuchFieldException moved) {
            System.err.println("[OMLLwjgl] " + ownerName + "." + name + " no longer exists");
            return 0;
        } catch (Throwable failed) {
            System.err.println("[OMLLwjgl] could not clear " + ownerName + "." + name + ": " + failed);
            return 0;
        }
    }

    /**
     * Every {@code static final ThreadLocal} these classes own, emptied on the current thread.
     *
     * <p>This is the residue that a static-field sweep cannot see. The render thread is the *same*
     * Java thread across a reboot — the session only replaces its classloader — so a ThreadLocal
     * populated by the retiring session is still installed when the next one starts. {@code
     * MemoryStack} is the dangerous one: its thread stack holds native blocks allocated by the old
     * session, and the next session pushes onto them. {@code AL}/{@code ALC} keep their capability
     * caches the same way.
     *
     * <p>{@code remove()} only drops this thread's entry, which is exactly the scope that has to be
     * cleaned; the blocks become collectable and the next use allocates a fresh stack. The stack's
     * own top-of-stack pointer is rewound first, so nothing can be read out of it between the reset
     * and the collection: see {@link #rewindMemoryStack}.
     */
    private static int clearThreadLocals(ClassLoader loader) {
        int cleared = 0;
        for (String owner : THREAD_LOCAL_OWNERS) {
            Class<?> type = load(owner, loader);
            if (type == null) continue;
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) continue;
                if (!ThreadLocal.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(null) instanceof ThreadLocal<?> local && local.get() != null) {
                        rewindMemoryStack(local.get(), loader);
                        local.remove();
                        cleared++;
                    }
                } catch (Throwable failed) {
                    System.err.println("[OMLLwjgl] could not clear a thread-local on " + owner + ": " + failed);
                }
            }
        }
        return cleared;
    }

    /**
     * Puts a {@code MemoryStack} back to an empty stack before its thread-local entry is dropped.
     *
     * <p>The stack allocates downward from a fixed base: {@code pointer} tracks the free end and
     * {@code frameIndex} counts pushed frames. A session that dies inside a frame leaves both behind,
     * and {@code remove()} alone would leave the underlying buffer reachable from nothing while its
     * top pointer still names an allocation the next session never made. Rewinding first makes the
     * abandonment explicit: base address, zero frames, nothing to unwind.
     */
    private static void rewindMemoryStack(Object stack, ClassLoader loader) {
        if (!"org.lwjgl.system.MemoryStack".equals(stack.getClass().getName())) return;
        try {
            Field container = stack.getClass().getDeclaredField("container");
            container.setAccessible(true);
            Object buffer = container.get(stack);
            if (!(buffer instanceof ByteBuffer bytes)) return;
            long base = memAddress(bytes, loader);
            Field pointer = stack.getClass().getDeclaredField("pointer");
            pointer.setAccessible(true);
            pointer.setLong(stack, base);
            Field frameIndex = stack.getClass().getDeclaredField("frameIndex");
            frameIndex.setAccessible(true);
            frameIndex.setInt(stack, 0);
        } catch (Throwable failed) {
            System.err.println("[OMLLwjgl] could not rewind a MemoryStack: " + failed);
        }
    }

    /**
     * {@code MemoryUtil.memAddress(ByteBuffer)} — the buffer's native base address.
     *
     * <p>Resolved through the given loader, not the buffer's: a direct buffer's class belongs to the
     * bootstrap loader, which cannot see LWJGL at all.
     */
    private static long memAddress(ByteBuffer buffer, ClassLoader loader) throws Exception {
        Class<?> memoryUtil = Class.forName("org.lwjgl.system.MemoryUtil", false, loader);
        Method method = memoryUtil.getMethod("memAddress", ByteBuffer.class);
        return (Long) method.invoke(null, buffer);
    }

    /**
     * Zeroes the "function missing" abort table LWJGL installs in native thread-local storage.
     *
     * <p>{@code GL.create(FunctionProvider)} calls {@code setFunctionMissingAddresses(2270)} on the
     * calling thread; the table lives in native TLS, not on the Java heap, so it survives the
     * classloader swap that every other form of cleanup is scoped to. {@code GL.destroy()} zeroes it
     * and the next {@code create()} sets it again, so this only stops a retiring session's table from
     * being read in between — but that gap is exactly the one a second session's first frame sits in.
     */
    private static int releaseFunctionMissingTable(ClassLoader loader) {
        Class<?> util = load("org.lwjgl.system.ThreadLocalUtil", loader);
        if (util == null) return 0;
        try {
            util.getMethod("setFunctionMissingAddresses", Integer.TYPE).invoke(null, 0);
            return 1;
        } catch (Throwable failed) {
            System.err.println("[OMLLwjgl] could not reset the function-missing table: " + failed);
            return 0;
        }
    }

    private static Class<?> load(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException absent) {
            return null;
        }
    }
}
