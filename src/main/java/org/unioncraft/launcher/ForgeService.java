package org.unioncraft.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Provisions Forge with the official installer's headless --installClient mode.
 * The installer writes a normal version profile into the shared versions folder;
 * the launch code then merges that profile with the vanilla base version.
 */
public final class ForgeService {
    private static final String PROMOTIONS =
            "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    private static final String MAVEN =
            "https://maven.minecraftforge.net/net/minecraftforge/forge/";

    private final Config config;
    private final MinecraftMeta meta;
    private final JavaService javaService;
    private final Path dataDir;
    private final Mirror mirror;
    private final Map<String, Object> locks = new ConcurrentHashMap<>();

    public ForgeService(Config config, MinecraftMeta meta, JavaService javaService, Path dataDir) {
        this.config = config;
        this.meta = meta;
        this.javaService = javaService;
        this.dataDir = dataDir;
        this.mirror = new Mirror(config);
    }

    public Map<String, Object> ensureForge(Map<String, Object> instance, Jobs.Job job) throws Exception {
        String mcVersion = Json.str(instance, "version", "");
        String loaderVersion = Json.str(instance, "loaderVersion", "");
        if (loaderVersion.isBlank()) loaderVersion = recommendedVersion(mcVersion);
        String profileId = mcVersion + "-forge-" + loaderVersion;
        Path profileJson = meta.versionsDir().resolve(profileId).resolve(profileId + ".json");
        if (Files.isRegularFile(profileJson)) {
            try {
                return Json.parseObject(Util.read(profileJson, "{}"));
            } catch (Exception ignored) { }
        }
        Object lock = locks.computeIfAbsent(profileId, k -> new Object());
        synchronized (lock) {
            if (Files.isRegularFile(profileJson)) {
                return Json.parseObject(Util.read(profileJson, "{}"));
            }
            if (job != null) job.progress(0.04, "Forge", "准备 Forge " + loaderVersion);
            Path installer = dataDir.resolve("cache").resolve("installers")
                    .resolve("forge-" + mcVersion + "-" + loaderVersion + "-installer.jar");
            String url = mirror.forgeInstallerUrl(mcVersion, loaderVersion);
            if (!Files.isRegularFile(installer) || Files.size(installer) < 10_000) {
                if (job != null) job.log("下载 Forge 安装器 · " + loaderVersion);
                mirror.download(url, installer, null, n -> {
                    if (job != null) job.progress(0.06, "Forge", "下载安装器 · " + Util.bytes(n));
                });
            }
            Path root = meta.root();
            Files.createDirectories(root);
            Path profiles = root.resolve("launcher_profiles.json");
            if (!Files.isRegularFile(profiles)) {
                Util.write(profiles, Json.write(Json.obj(
                        "profiles", new LinkedHashMap<>(),
                        "settings", new LinkedHashMap<>(),
                        "version", 3)));
            }
            Map<String, Object> base = meta.detail(mcVersion);
            int requiredJava = Json.integer(Json.map(base.get("javaVersion")), "majorVersion", 17);
            JavaService.JavaInfo java = javaService.choose(requiredJava, mcVersion);
            if (java == null) {
                Path javaExe = javaService.ensureRuntime(JavaService.componentFor(requiredJava), requiredJava, job);
                java = javaService.inspect(javaExe);
            }
            if (java == null) throw new IOException("找不到可用于安装 Forge 的 Java");
            Path log = dataDir.resolve("cache").resolve("installers")
                    .resolve("forge-" + mcVersion + "-" + loaderVersion + ".log");
            if (job != null) {
                job.progress(0.09, "Forge", "运行官方安装器 · 这只会发生一次");
                job.log("Forge 安装器 · Java " + java.major() + " · " + root);
            }
            ProcessBuilder builder = new ProcessBuilder(java.path().toString(), "-jar", installer.toString(),
                    "--installClient", root.toString());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            Process process = builder.start();
            if (!process.waitFor(12, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IOException("Forge 安装超时，请检查网络后重试");
            }
            if (process.exitValue() != 0) {
                throw new IOException("Forge 安装失败（退出码 " + process.exitValue() + "）\n" + tail(log, 40));
            }
            if (Files.isRegularFile(profileJson)) {
                if (job != null) {
                    job.progress(0.17, "Forge", "Forge " + loaderVersion + " 已安装");
                    job.log("Forge 版本配置已写入 · " + profileId);
                }
                return Json.parseObject(Util.read(profileJson, "{}"));
            }
            Map<String, Object> found = meta.findLocalLoaderProfile(mcVersion, "forge", loaderVersion);
            if (job != null) job.log("Forge 版本配置 · " + Json.str(found, "id", profileId));
            return found;
        }
    }

    public String recommendedVersion(String mcVersion) throws Exception {
        return mirror.forgeVersion(mcVersion);
    }

    public String installerUrl(String mcVersion, String forgeVersion) {
        return mirror.forgeInstallerUrl(mcVersion, forgeVersion);
    }

    private static String tail(Path file, int lines) {
        if (!Files.isRegularFile(file)) return "";
        try {
            List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
            int start = Math.max(0, all.size() - lines);
            return String.join("\n", all.subList(start, all.size()));
        } catch (IOException e) {
            return "";
        }
    }
}
