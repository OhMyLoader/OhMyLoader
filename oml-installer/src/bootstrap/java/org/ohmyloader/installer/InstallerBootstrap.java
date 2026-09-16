package org.ohmyloader.installer;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;

/**
 * Entry point of the installer jar — deliberately compiled with {@code --release 8}: the installer
 * itself is Java 27 bytecode, and on Java 8/17/21 the JVM would abort with
 * {@code UnsupportedClassVersionError} before any of our code ran (and double-clicking a jar on
 * Windows goes through {@code javaw.exe}, which has no console — the user sees nothing). So the
 * version check lives in a class any JRE since 8 can run — Java 8 APIs only, and no static reference
 * to the Java 27 installer classes, which are reached by name only after the check has passed.
 *
 * <p>Dispatch: with arguments it is the CLI ({@link org.ohmyloader.installer.Installer}); with no
 * arguments on a graphical machine it is the Swing GUI; with no arguments and no display it prints
 * usage instead of throwing {@code HeadlessException}.
 */
public final class InstallerBootstrap {

    /**
     * Java major version OML requires. Kept in sync with the build (see oml-installer/build.gradle.kts).
     */
    private static final int REQUIRED_JAVA_MAJOR = 27;

    private static final String JAVA_DOWNLOAD_PAGE = "https://www.azul.com/downloads/?version=java-27-sts#zulu";

    private static final String GUI_CLASS = "org.ohmyloader.installer.SwingInstaller";
    private static final String CLI_CLASS = "org.ohmyloader.installer.Installer";

    private InstallerBootstrap() {
    }

    public static void main(String[] args) {
        int major = detectJavaMajor();
        if (major < REQUIRED_JAVA_MAJOR) {
            reportTooOldJava(major);
            return;
        }

        // GUI only when there is nothing to parse *and* a display to draw on.
        boolean cliMode = args != null && args.length > 0;
        if (!cliMode && GraphicsEnvironment.isHeadless()) {
            System.err.println(usageText(major));
            System.err.println("[installer] 当前环境没有图形界面（headless），请改用命令行参数安装。");
            return;
        }

        String target = cliMode ? CLI_CLASS : GUI_CLASS;
        try {
            Class<?> entry = Class.forName(target, true, InstallerBootstrap.class.getClassLoader());
            Method main = entry.getMethod("main", String[].class);
            main.invoke(null, (Object) (args == null ? new String[0] : args));
        } catch (InvocationTargetException e) {
            // The real installer failed. Its exception has already been reported with a full message by
            // its own top-level handler; only unwrap it here so the user never sees a bare
            // InvocationTargetException.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            System.err.println("[installer] 启动失败：" + cause);
            cause.printStackTrace();
            exitWith(1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("[installer] 无法启动安装程序：" + target);
            System.err.println("[installer] " + e);
            e.printStackTrace();
            exitWith(1);
        }
    }

    /**
     * Reads the runtime's major version using Java 8 APIs only.
     *
     * <p>{@code java.specification.version} is {@code "1.8"} on Java 8 and {@code "9"}…{@code "27"} from
     * Java 9 on, so both forms have to be handled. Returns {@code -1} when the property is absent or
     * unparsable, which callers treat as "too old" (fail safe, with a message rather than a crash).
     */
    private static int detectJavaMajor() {
        String spec = System.getProperty("java.specification.version", "");
        try {
            if (spec.startsWith("1.")) {
                return Integer.parseInt(spec.substring(2));
            }
            return Integer.parseInt(spec.trim());
        } catch (NumberFormatException e) {
            // Fall back to parsing java.version ("1.8.0_392" / "26.0.2.1+1") before giving up
            String version = System.getProperty("java.version", "");
            try {
                if (version.startsWith("1.")) {
                    return Integer.parseInt(version.substring(2, 3));
                }
                int dot = version.indexOf('.');
                return Integer.parseInt(dot < 0 ? version : version.substring(0, dot));
            } catch (RuntimeException ignored) {
                return -1;
            }
        }
    }

    /**
     * Tells the user, in a dialog they cannot miss, that the runtime is too old — with a button that
     * opens the download page. Never exits silently.
     */
    private static void reportTooOldJava(int detected) {
        boolean headless;
        try {
            headless = GraphicsEnvironment.isHeadless();
        } catch (Throwable t) {
            headless = true;
        }
        if (headless) {
            System.err.println(messageText(detected));
            System.err.println("[installer] 请从 " + JAVA_DOWNLOAD_PAGE + " 下载 Java 27 或更高版本。");
            exitWith(1);
            return;
        }

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // keep the default look and feel
        }

        final Object[] options = {"前往下载 Java 27", "退出"};
        int choice = JOptionPane.showOptionDialog(
            null,
            messageText(detected) + "\n\n是否现在打开下载页面？",
            "OhMyLoader 安装器",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.ERROR_MESSAGE,
            null,
            options,
            options[0]);

        if (choice == 0) {
            openDownloadPage();
        }
        exitWith(1);
    }

    private static String messageText(int detected) {
        String shown = detected < 0 ? "未知版本" : "Java " + detected;
        return "运行 OhMyLoader 安装器需要 Java 27 或更高版本！\n"
            + "当前检测到的运行环境是：" + shown + "\n\n"
            + "Java 27：" + JAVA_DOWNLOAD_PAGE;
    }

    private static void openDownloadPage() {
        try {
            if (!Desktop.isDesktopSupported()) {
                return;
            }
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI.create(JAVA_DOWNLOAD_PAGE));
            }
        } catch (Exception e) {
            // A failed browse must not swallow the dialog the user just read; the URL is on screen.
            System.err.println("[installer] 无法自动打开浏览器，请手动访问：" + JAVA_DOWNLOAD_PAGE);
        }
    }

    private static String usageText(int major) {
        return "OhMyLoader 安装器（命令行模式，当前 Java " + major + "）\n"
            + "用法：java -jar oml-installer.jar [选项]\n"
            + "  --target <standard|prism|server>   安装形态（默认 standard：装进启动器的 .minecraft）\n"
            + "  --version <26.3>                   游戏版本\n"
            + "  --dir <目录>                       目标目录（游戏目录 / Prism 实例目录 / 服务端目录）\n"
            + "  --id <安装 id>                     安装 id，默认 <版本>-OML；多实例共存的隔离键\n"
            + "  --isolation <true|false>           版本隔离（standard 形态必填：true=mods 在版本目录内）\n"
            + "  --accept-eula                      服务端安装时同意 Minecraft EULA（不传则不写 eula.txt）\n"
            + "  --accept-shared-mods               显式确认「共享 mods 目录」的风险\n"
            + "  --help                             显示本帮助\n"
            + "不带参数且存在图形界面时，将启动图形安装程序。";
    }

    private static void exitWith(int code) {
        // System.exit is only ever called from this Java 8 class (never from inside the installer), so
        // that a failure inside the installer can still surface as a dialog rather than a dead window.
        System.exit(code);
    }
}
