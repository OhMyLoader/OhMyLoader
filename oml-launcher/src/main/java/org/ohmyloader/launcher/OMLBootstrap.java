package org.ohmyloader.launcher;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Process entry point, designed to be run as `java -jar OhMyLoader.jar`. Under `java -jar` the
 * classpath holds only this jar, so the OML layer is discovered in {@code lib/} next to this jar and
 * handed to {@link OMLSupervisor}, which owns the Supervisor/Session boundary. The game layer is
 * never downloaded here — the installer lays it out and passes its paths via system properties
 * ({@code oml.library.dir} feeds {@code OMLCore.buildRuntimeUrls}).
 *
 * <p>This class only resolves the launcher layout and the {@code launch.properties} overrides. It
 * deliberately does not load {@code oml-core}: that module is session-scoped and must disappear
 * together with the game when the session is replaced.
 *
 * <p>Two launch forms: <b>server</b> — the installer wrote a {@code launch.properties} next to the
 * jar carrying side / version / paths; <b>launcher</b> — PCL2 / HMCL / official put this jar on the
 * game classpath and pass each path as a {@code -D} from the version JSON, where the classpath
 * directory is not a game directory, so nothing may be derived from it. The side is never guessed:
 * it arrives from {@code launch.properties} or {@code -D}.
 */
public final class OMLBootstrap {

    private static final String LAYER_DIR = "lib";
    private static final String CONFIG_FILE = "launch.properties";

    public static void main(String[] args) throws Exception {
        System.out.println("[OMLBootstrap] JVM version: " + Runtime.version());
        File baseDir = resolveBaseDir();
        System.out.println("[OMLBootstrap] Runtime directory: " + baseDir);

        Properties cfg = loadConfig(baseDir);
        if (cfg != null) {
            configureRuntime(baseDir, cfg);
        } else {
            System.out.println("[OMLBootstrap] launcher form: no " + CONFIG_FILE + " next to the jar");
            System.out.println("[OMLBootstrap] oml.side=" + System.getProperty("oml.side", "client (default)"));
            System.out.println("[OMLBootstrap] oml.game.jar=" + System.getProperty("oml.game.jar", "(unset)"));
        }

        URL[] layer = discoverLayerJars(baseDir);
        URL self = OMLBootstrap.class.getProtectionDomain().getCodeSource().getLocation();
        LayerSplit split = split(layer, self);
        System.out.println(
            "[OMLBootstrap] supervisor jars: " + split.supervisor.length + ", session jars: " + split.session.length);

        preloadNatives(baseDir);

        // The supervisor loader lives for the whole process: the window lease, the L1 transform
        // cache and the reboot signal are statics on classes it defines, and a reboot must not
        // recreate them. Sessions are children of this loader.
        URLClassLoader supervisor = new URLClassLoader(split.supervisor, ClassLoader.getPlatformClassLoader());
        Class<?> supervisorClass = Class.forName("org.ohmyloader.launcher.OMLSupervisor", true, supervisor);
        Method run = supervisorClass.getMethod("run", String[].class, URL[].class);
        run.invoke(null, (Object) args, (Object) split.session);
    }

    /**
     * The directory that contains this jar (and lib/, libraries/, minecraft/, mods/, ...).
     */
    static File resolveBaseDir() {
        // Under `java -jar` the first classpath entry is this jar; use it, falling back to cwd.
        try {
            String cp = System.getProperty("java.class.path", "");
            String first = cp.split(File.pathSeparator, 2)[0].trim();
            if (!first.isEmpty()) {
                Path p = Path.of(first);
                return p.toAbsolutePath().getParent().toFile();
            }
        } catch (RuntimeException ignored) {
            // fall through to cwd
        }
        return new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
    }

    /**
     * Reads launch.properties next to the base directory, or returns {@code null} when the file is
     * absent. That absence is what tells the two launch forms apart, so it must not be flattened into
     * an empty config — an empty config is "the server form with defaults", which is a different thing.
     */
    static Properties loadConfig(File baseDir) {
        Properties p = new Properties();
        File f = new File(baseDir, CONFIG_FILE);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = Files.newInputStream(f.toPath())) {
            p.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            System.out.println("[OMLBootstrap] loaded " + f.getName());
        } catch (Exception e) {
            System.err.println("[OMLBootstrap] failed reading " + f.getName() + ": " + e.getMessage());
        }
        return p;
    }

    /**
     * Fills in the OML runtime system properties from launch.properties when a property was not
     * already provided on the command line. Server form only — see {@link #main}. The installer is
     * responsible for laying out downloads; this loader only points at ready paths.
     */
    static void configureRuntime(File baseDir, Properties cfg) {
        String side = cfg.getProperty("side", "server");
        // side is not read back from -D at this stage (OMLCore reads its own default); forward it.
        setIfAbsent("oml.side", side);

        String version = cfg.getProperty("version", "");
        File gameJar = new File(baseDir, "minecraft/" + version + "/server.jar");
        setIfAbsent("oml.game.jar", gameJar.getAbsolutePath());
        setIfAbsent("oml.library.dir", new File(baseDir, "libraries").getAbsolutePath());
        setIfAbsent("oml.mods.dir", cfg.getProperty("mods.dir", new File(baseDir, "mods").getAbsolutePath()));

        // NativeManager resolves the natives/ directory from java.library.path — the same directory
        // the installer put the bare oml-native library in, next to the vanilla natives. Gradle dev
        // launches pass the -D themselves; an installer-built server reaches this code instead.
        // Prepended, not set-if-absent: the JVM always defines java.library.path at startup (its first
        // entry is the JDK's own bin directory), so set-if-absent would keep that bin directory and the
        // bare library sitting in natives/ would never be reached.
        prependLibraryPath(new File(baseDir, "natives").getAbsolutePath());

        // The loader builds the game classpath from java.class.path + oml.library.dir. Under `java -jar`
        // java.class.path holds only this shell, so the game jar itself would never reach the game
        // classpath. Append it here so OMLCore.buildRuntimeUrls picks it up together with the libraries.
        appendToClassPath(gameJar);

        System.out.println("[OMLBootstrap] oml.side=" + side);
        System.out.println("[OMLBootstrap] oml.game.jar=" + System.getProperty("oml.game.jar"));
        System.out.println("[OMLBootstrap] oml.library.dir=" + System.getProperty("oml.library.dir"));
    }

    /**
     * Loads the game's native libraries from this persistent loader before any session starts.
     *
     * <p>{@code System.load} associates a library with the calling classloader for the life of the
     * process. When the first session loads LWJGL's natives, its disposable loader becomes a GC root
     * and can never be collected after a reboot. Loading them here (the app loader lives as long as
     * the process) keeps that association off the session; the game's own load attempt then reports
     * "already loaded in another classloader", which it tolerates, and continues with the same
     * process-wide image. This also points LWJGL at the unified {@code natives/} directory instead
     * of its private temp extraction.
     */
    static void preloadNatives(File baseDir) {
        File dir = resolveNativesDir(baseDir);
        if (dir == null) return;
        setIfAbsent("org.lwjgl.librarypath", dir.getAbsolutePath());
        File[] files = dir.listFiles((d, n) -> {
            String lower = n.toLowerCase(Locale.ROOT);
            boolean nativeFile = lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib");
            // oml-native is opened through FFM's SymbolLookup, which carries no classloader pin.
            return nativeFile && !lower.startsWith("oml-native");
        });
        if (files == null) return;
        int loaded = 0;
        for (File file : files) {
            try {
                System.load(file.getAbsolutePath());
                loaded++;
            } catch (Throwable ignored) {
                // A missing transitive dependency is the game's to report when it loads its own set.
            }
        }
        System.out.println(
            "[OMLBootstrap] preloaded " + loaded + "/" + files.length + " native library file(s) from " + dir);
    }

    private static File resolveNativesDir(File baseDir) {
        for (String entry : System.getProperty("java.library.path", "").split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            File dir = new File(entry);
            if (dir.isDirectory() && "natives".equals(dir.getName())) return dir;
        }
        File fallback = new File(baseDir, "natives");
        return fallback.isDirectory() ? fallback : null;
    }

    /**
     * Adds a jar to java.class.path unless it is already present (called before OMLCore reads that property).
     */
    private static void appendToClassPath(File jar) {
        if (jar == null || !jar.isFile()) return;
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(jar.getPath().isEmpty() ? "\0" : File.pathSeparator)) {
            if (!entry.isEmpty() && new File(entry).getAbsolutePath().equalsIgnoreCase(jar.getAbsolutePath())) return;
        }
        System.setProperty("java.class.path", cp + File.pathSeparator + jar.getAbsolutePath());
    }

    /**
     * Puts {@code dir} at the front of java.library.path unless it is already on it: a repeat launch, or
     * an operator who passed their own {@code -D}, must not accumulate duplicate entries. Front, not
     * back, so the natives directory wins over whatever the JVM put there by default.
     */
    private static void prependLibraryPath(String dir) {
        String current = System.getProperty("java.library.path", "");
        for (String entry : current.split(File.pathSeparator)) {
            if (entry.equalsIgnoreCase(dir)) return;
        }
        System.setProperty(
            "java.library.path", current.isEmpty() ? dir : dir + File.pathSeparator + current);
    }

    private static void setIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    /** Package-private for the layer-split contract test. */
    static LayerSplit split(URL[] urls) {
        List<URL> session = new ArrayList<>();
        List<URL> supervisor = new ArrayList<>();
        for (URL url : urls) {
            String name = fileName(url);
            if (isDirectory(url)) {
                // Development class directories may hold any of the modules; the session loader's
                // child-first rule keeps OML classes out of the parent.
                session.add(url);
                continue;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("oml-launcher")) {
                // The shell is the app loader's entry point; a second copy in the session would
                // only create a duplicate bootstrap.
                continue;
            }
            if (lower.startsWith("oml-native")) {
                // Native libraries cannot be unloaded with a classloader; keep their loader and the
                // already-loaded native image in the supervisor.
                supervisor.add(url);
                continue;
            }
            if (lower.startsWith("oml-")) {
                session.add(url);
            } else {
                supervisor.add(url);
            }
        }
        return new LayerSplit(session.toArray(new URL[0]), supervisor.toArray(new URL[0]));
    }

    /** The real launch uses this overload: the shell itself must stay visible to the supervisor. */
    static LayerSplit split(URL[] urls, URL extraSupervisor) {
        LayerSplit base = split(urls);
        List<URL> supervisor = new ArrayList<>(Arrays.asList(base.supervisor));
        if (extraSupervisor != null && !supervisor.contains(extraSupervisor)) {
            supervisor.add(extraSupervisor);
        }
        return new LayerSplit(base.session, supervisor.toArray(new URL[0]));
    }

    private static String fileName(URL url) {
        try {
            return new File(url.toURI()).getName();
        } catch (Exception e) {
            return url.getPath();
        }
    }

    private static boolean isDirectory(URL url) {
        try {
            return new File(url.toURI()).isDirectory();
        } catch (Exception e) {
            return false;
        }
    }

    static final class LayerSplit {
        final URL[] session;
        final URL[] supervisor;

        LayerSplit(URL[] session, URL[] supervisor) {
            this.session = session;
            this.supervisor = supervisor;
        }
    }

    /**
     * All jars directly under {@code lib/}, used as the launcher layer. In a launcher-installed form
     * there is no {@code lib/}; the version JSON puts the same jars on the classpath, so the fallback
     * returns the app classpath and the split classifies it the same way.
     */
    static URL[] discoverLayerJars(File baseDir) throws Exception {
        File libDir = new File(baseDir, LAYER_DIR);
        if (!libDir.isDirectory()) {
            // No layer directory: fall back to the current classpath so pure-IDEA / test runs still
            // work (the module runtime classpath already carries the dependencies there).
            return classPathUrls();
        }
        File[] jars = libDir.listFiles((d, n) -> n.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            return classPathUrls();
        }
        List<URL> urls = new ArrayList<>();
        for (File jar : jars) urls.add(jar.toURI().toURL());
        System.out.println("[OMLBootstrap] layer: " + urls.size() + " jar(s) from " + libDir);
        return urls.toArray(new URL[0]);
    }

    private static URL[] classPathUrls() throws Exception {
        String[] cp = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[cp.length];
        for (int i = 0; i < cp.length; i++) urls[i] = Path.of(cp[i]).toUri().toURL();
        return urls;
    }

    private OMLBootstrap() {}
}
