package org.ohmyloader.launcher;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Self-bootstrapping entry point, designed to be run as `java -jar OhMyLoader.jar`. Under `java -jar`
 * the classpath holds only this jar, so the OML layer (core / api / adapter / Kotlin / ASM / Gson) is
 * loaded from {@code lib/} next to this jar through a dedicated {@link URLClassLoader} whose parent
 * is the platform loader. The game layer is never downloaded here — the installer lays it out and
 * passes its paths via system properties ({@code oml.library.dir} feeds {@code OMLCore.buildRuntimeUrls}).
 *
 * <p>Two launch forms: <b>server</b> — the installer wrote a {@code launch.properties} next to the jar
 * carrying side / version / paths; <b>launcher</b> — PCL2 / HMCL / official put this jar on the game
 * classpath and pass each path as a {@code -D} from the version JSON, where the classpath directory
 * is not a game directory, so nothing may be derived from it. The side is never guessed: it arrives
 * from {@code launch.properties} or {@code -D}. OMLCore is invoked reflectively ({@code compileOnly}).
 */
public final class OMLBootstrap {

    private static final String LAYER_DIR = "lib";
    private static final String CONFIG_FILE = "launch.properties";

    public static void main(String[] args) throws Exception {
        System.out.println("[OMLBootstrap] JVM version: " + Runtime.version());
        System.out.println("[OMLBootstrap] Bootstrapping modern runtime...");

        File baseDir = resolveBaseDir();
        System.out.println("[OMLBootstrap] Runtime directory: " + baseDir);

        // 1. Settings: launch.properties when the installer wrote this directory; otherwise this is the
        //    launcher form and every -D property is already correct, so nothing may be derived here.
        //    An absent config is not an empty config: an empty config defaults to side "server", which
        //    would hand a client launch to the dedicated-server main class (net.minecraft.server.Main)
        //    and die on the client's arguments ("username is not a recognized option"). baseDir is also
        //    the wrong root to derive from — it is the folder of whichever classpath entry a launcher
        //    happened to list first, not a game directory.
        Properties cfg = loadConfig(baseDir);
        if (cfg != null) {
            configureRuntime(baseDir, cfg);
        } else {
            System.out.println("[OMLBootstrap] launcher form: no " + CONFIG_FILE + " next to the jar");
            System.out.println("[OMLBootstrap] oml.side=" + System.getProperty("oml.side", "client (default)"));
            System.out.println("[OMLBootstrap] oml.game.jar=" + System.getProperty("oml.game.jar", "(unset)"));
        }

        // 2. Load the OML layer (core / api / adapters / dependencies) from lib/.
        URL[] layerJars = discoverLayerJars(baseDir);
        URLClassLoader omlClassLoader = new URLClassLoader(layerJars, ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(omlClassLoader);

        // 3. Hand over to the OML core entry point (reflective, so the launcher needs no compile-time
        //    dependency on the core layer).
        Class<?> coreClass = Class.forName("org.ohmyloader.core.OMLCore", true, omlClassLoader);
        Method initMethod = coreClass.getMethod("start", String[].class);
        initMethod.invoke(null, (Object) args);
    }

    /**
     * The directory that contains this jar (and lib/, libraries/, minecraft/, mods/, ...).
     */
    private static File resolveBaseDir() {
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
     * absent. That absence is what tells the two launch forms apart, so it must not be flattened into an
     * empty config — an empty config is "the server form with defaults", which is a different thing.
     */
    private static Properties loadConfig(File baseDir) {
        Properties p = new Properties();
        File f = new File(baseDir, CONFIG_FILE);
        if (!f.isFile()) {
            return null;
        }
        try (InputStream in = java.nio.file.Files.newInputStream(f.toPath())) {
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
    private static void configureRuntime(File baseDir, Properties cfg) {
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
        //
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

    /**
     * All jars directly under {@code lib/}, used as the OML layer classpath (parent loader of the game loader).
     */
    private static URL[] discoverLayerJars(File baseDir) throws Exception {
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
}
