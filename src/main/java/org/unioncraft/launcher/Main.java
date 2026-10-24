package org.unioncraft.launcher;

import com.sun.net.httpserver.HttpServer;

import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;

/** Entry point: starts the local API, then opens the UI in a Chromium app window. */
public final class Main {
    private static volatile CountDownLatch shutdown = new CountDownLatch(1);
    private static volatile HttpServer server;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.CHINA);
        boolean noBrowser = false;
        boolean dev = false;
        int port = 0;
        for (String arg : args) {
            if ("--no-browser".equalsIgnoreCase(arg)) noBrowser = true;
            if ("--dev".equalsIgnoreCase(arg)) dev = true;
            if (arg.startsWith("--port=")) {
                try { port = Integer.parseInt(arg.substring("--port=".length())); } catch (NumberFormatException ignored) { }
            }
        }
        String envPort = System.getenv("UCL_PORT");
        if (port == 0 && envPort != null) {
            try { port = Integer.parseInt(envPort); } catch (NumberFormatException ignored) { }
        }

        Path dataDir = Util.dataDir();
        Files.createDirectories(dataDir);
        Files.createDirectories(dataDir.resolve("cache"));
        Files.createDirectories(dataDir.resolve("java"));

        Config config = new Config(dataDir);
        AuthService auth = new AuthService(dataDir);
        MinecraftMeta meta = new MinecraftMeta(config, dataDir);
        InstanceService instances = new InstanceService(config);
        JavaService javaService = new JavaService(config, dataDir);
        ForgeService forgeService = new ForgeService(config, meta, javaService, dataDir);
        ModrinthService modrinth = new ModrinthService(meta, instances, dataDir);
        CurseForgeService curseForge = new CurseForgeService(config, meta, instances, dataDir);
        Launcher launcher = new Launcher(config, meta, instances, auth, javaService, forgeService, dataDir);

        String token = dev ? "dev" : HexFormat.of().formatHex(randomBytes(24));
        Path webDir = resolveWebDir();
        ApiServer api = new ApiServer(token, dataDir, webDir, config, auth, meta, instances,
                javaService, modrinth, curseForge, launcher);
        boolean autoPort = port == 0;
        if (autoPort) port = config.getInt("serverPort", 52114);
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        } catch (IOException busy) {
            if (!autoPort) throw busy;
            port = 0;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        server.createContext("/", api);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(12, r -> {
            Thread thread = new Thread(r, "ucl-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        int actualPort = server.getAddress().getPort();
        String url = "http://127.0.0.1:" + actualPort + "/?token=" + token;
        System.out.println("============================================================");
        System.out.println("  Union Craft Launcher " + Util.VERSION);
        System.out.println("  UI: " + url);
        System.out.println("  Data: " + dataDir);
        System.out.println("============================================================");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { server.stop(0); } catch (Exception ignored) { }
        }, "ucl-shutdown"));

        boolean browserOpened = false;
        if (!noBrowser) {
            browserOpened = openBrowser(url, dataDir);
        }
        if (browserOpened) {
            String theme = config.getString("theme", "light");
            if ("dark".equals(theme) || "light".equals(theme)) WindowTheme.applyAsync("dark".equals(theme));
        }
        if (browserOpened && !dev) startUiWatchdog();
        shutdown.await();
        try { server.stop(0); } catch (Exception ignored) { }
        System.exit(0);
    }

    /** Closes the local service if the browser window disappears without using the quit button. */
    private static void startUiWatchdog() {
        Thread thread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(20_000);
                    if (System.currentTimeMillis() - ApiServer.lastHeartbeat() > 180_000) {
                        System.out.println("  UI heartbeat lost; closing launcher service.");
                        requestShutdown();
                        return;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "ucl-ui-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    public static void requestShutdown() {
        shutdown.countDown();
    }

    private static Path resolveWebDir() throws IOException {
        String override = System.getProperty("ucl.webDir");
        if (override == null || override.isBlank()) override = System.getenv("UCL_WEB_DIR");
        if (override != null && !override.isBlank()) {
            Path path = Path.of(override).toAbsolutePath();
            if (Files.isDirectory(path)) return path;
        }
        Path cwd = Path.of("web").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("index.html"))) return cwd;
        try {
            Path code = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path base = Files.isDirectory(code) ? code : code.getParent();
            for (Path candidate : List.of(base.resolve("web"), base.getParent() == null ? base : base.getParent().resolve("web"))) {
                if (Files.isRegularFile(candidate.resolve("index.html"))) return candidate.toAbsolutePath();
            }
        } catch (Exception ignored) { }
        throw new IOException("找不到 web 界面目录，请设置 UCL_WEB_DIR");
    }

    private static byte[] randomBytes(int count) {
        byte[] bytes = new byte[count];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static boolean openBrowser(String url, Path dataDir) {
        Path browser = appBrowser();
        if (browser == null) {
            System.out.println("  未找到 Chrome / Edge / Brave，改用默认浏览器");
            return openExternal(url);
        }
        int width = 1120;
        int height = 740;
        int x = 80;
        int y = 50;
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                width = Math.min(1120, Math.max(980, (int) (screen.width * 0.76)));
                height = Math.min(760, Math.max(680, (int) (screen.height * 0.84)));
                x = Math.max(0, (screen.width - width) / 2);
                y = Math.max(0, (screen.height - height) / 2 - 12);
            }
        } catch (Exception ignored) { }
        try {
            Path profile = dataDir.resolve("browser");
            Files.createDirectories(profile);
            Path log = dataDir.resolve("browser.log");
            ProcessBuilder builder = new ProcessBuilder(
                    browser.toString(),
                    "--app=" + url,
                    "--user-data-dir=" + profile.toAbsolutePath(),
                    "--no-first-run",
                    "--no-default-browser-check",
                    "--disable-background-mode",
                    "--force_high_performance_gpu",
                    "--enable-gpu-rasterization",
                    "--disable-features=Translate,TranslateUI,MediaRouter",
                    "--window-size=" + width + "," + height,
                    "--window-position=" + x + "," + y);
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            builder.start();
            System.out.println("  App window: " + browser);
            return true;
        } catch (Exception e) {
            System.out.println("  App 窗口打开失败：" + e.getMessage());
            return openExternal(url);
        }
    }

    /** Opens an external page (Microsoft login) in the system default browser. */
    public static boolean openExternal(String url) {
        if (!url.startsWith("https://")) return false;
        if (Util.WINDOWS) {
            try {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                return true;
            } catch (Exception ignored) { }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static Path appBrowser() {
        String override = System.getenv("UCL_BROWSER");
        if (override != null && Files.isRegularFile(Path.of(override))) return Path.of(override);
        String pf = System.getenv("ProgramFiles");
        String pf86 = System.getenv("ProgramFiles(x86)");
        String local = System.getenv("LOCALAPPDATA");
        Path[] candidates = {
                pf == null ? null : Path.of(pf, "Google", "Chrome", "Application", "chrome.exe"),
                pf86 == null ? null : Path.of(pf86, "Google", "Chrome", "Application", "chrome.exe"),
                local == null ? null : Path.of(local, "Google", "Chrome", "Application", "chrome.exe"),
                pf == null ? null : Path.of(pf, "Microsoft", "Edge", "Application", "msedge.exe"),
                pf86 == null ? null : Path.of(pf86, "Microsoft", "Edge", "Application", "msedge.exe"),
                local == null ? null : Path.of(local, "Microsoft", "Edge", "Application", "msedge.exe"),
                pf == null ? null : Path.of(pf, "BraveSoftware", "Brave-Browser", "Application", "brave.exe"),
                local == null ? null : Path.of(local, "BraveSoftware", "Brave-Browser", "Application", "brave.exe")
        };
        for (Path candidate : candidates) {
            if (candidate != null && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }
}
