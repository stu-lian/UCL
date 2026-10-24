package org.unioncraft.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves versions, builds the Minecraft command line and starts the game process. */
public final class Launcher {
    private final Config config;
    private final MinecraftMeta meta;
    private final InstanceService instances;
    private final AuthService auth;
    private final JavaService javaService;
    private final ForgeService forgeService;
    private final Path dataDir;
    private final Map<String, Process> running = new ConcurrentHashMap<>();
    private final Map<Path, MinecraftMeta> metaCache = new ConcurrentHashMap<>();
    private final Map<Path, ForgeService> forgeCache = new ConcurrentHashMap<>();

    private final Mirror mirror;

    public Launcher(Config config, MinecraftMeta meta, InstanceService instances, AuthService auth,
                    JavaService javaService, ForgeService forgeService, Path dataDir) {
        this.config = config;
        this.meta = meta;
        this.instances = instances;
        this.auth = auth;
        this.javaService = javaService;
        this.forgeService = forgeService;
        this.dataDir = dataDir;
        this.mirror = new Mirror(config);
    }

    private MinecraftMeta metaFor(Map<String, Object> instance) {
        Path root = instances.rootDir(instance);
        if (root.equals(meta.root())) return meta;
        return metaCache.computeIfAbsent(root, r -> new MinecraftMeta(config, dataDir, r));
    }

    private ForgeService forgeFor(MinecraftMeta target) {
        if (target == meta) return forgeService;
        return forgeCache.computeIfAbsent(target.root(),
                r -> new ForgeService(config, target, javaService, dataDir));
    }

    public Map<String, Object> running() {
        Map<String, Object> out = new LinkedHashMap<>();
        running.entrySet().removeIf(e -> !e.getValue().isAlive());
        for (Map.Entry<String, Process> e : running.entrySet()) {
            out.put(e.getKey(), e.getValue().pid());
        }
        return out;
    }

    public void stop(String instanceId) {
        Process process = running.get(instanceId);
        if (process == null || !process.isAlive()) {
            running.remove(instanceId);
            return;
        }
        try {
            // Ask the game to close, then make sure the whole tree is gone so logs are unlocked.
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                if (Util.WINDOWS) {
                    Process killer = new ProcessBuilder("taskkill", "/PID", String.valueOf(process.pid()), "/T", "/F")
                            .redirectErrorStream(true).start();
                    killer.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                } else {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
                process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (!process.isAlive()) running.remove(instanceId);
        }
    }

    public Map<String, Object> launch(String instanceId, Jobs.Job job) throws Exception {
        return launchInternal(instanceId, job, false);
    }

    public Map<String, Object> prepare(String instanceId, Jobs.Job job) throws Exception {
        return launchInternal(instanceId, job, true);
    }

    private Map<String, Object> launchInternal(String instanceId, Jobs.Job job, boolean prepareOnly) throws Exception {
        Map<String, Object> instance = instances.get(instanceId);
        String gameVersion = Json.str(instance, "version", "");
        String loader = Json.str(instance, "loader", "vanilla");
        String loaderVersion = Json.str(instance, "loaderVersion", "");
        Path gameDir = instances.gameDir(instanceId);
        Files.createDirectories(gameDir.resolve("logs"));
        MinecraftMeta localMeta = metaFor(instance);

        job.progress(0.02, "版本元数据", "读取 " + gameVersion + " 版本信息");
        boolean resolvedProfile = false;
        Map<String, Object> base;
        try {
            Map<String, Object> local = localMeta.localDetail(gameVersion);
            String localMain = Json.str(local, "mainClass", "");
            if (!Json.str(local, "inheritsFrom", "").isBlank()) {
                base = localMeta.resolveProfile(gameVersion);
                resolvedProfile = true;
            } else {
                base = local;
                // Imported / hand-built profiles are already complete. Fabric or Forge version
                // folders have their own main class instead of net.minecraft.client.main.Main.
                boolean imported = "imported".equals(Json.str(instance, "source", ""));
                if (imported || (!localMain.isBlank() && !localMain.equals("net.minecraft.client.main.Main"))) {
                    resolvedProfile = true;
                }
            }
        } catch (Exception notLocal) {
            base = localMeta.detail(gameVersion);
        }

        String mainClass = Json.str(base, "mainClass", "");
        List<Map<String, Object>> libraries = new ArrayList<>();
        for (Object value : Json.list(base.get("libraries"))) libraries.add(Json.map(value));
        Map<String, Object> arguments = Json.map(base.get("arguments"));
        List<Object> jvmArgs = new ArrayList<>(Json.list(arguments.get("jvm")));
        List<Object> gameArgs = new ArrayList<>(Json.list(arguments.get("game")));
        String legacyArgs = Json.str(base, "minecraftArguments", "");
        String profileId = Json.str(base, "id", gameVersion);

        if (!resolvedProfile && ("fabric".equalsIgnoreCase(loader) || "quilt".equalsIgnoreCase(loader))) {
            job.progress(0.04, "模组加载器", "解析 " + loader + " 配置");
            if (loaderVersion.isBlank()) loaderVersion = latestLoader(loader, gameVersion);
            String profileUrl;
            String officialProfileUrl;
            if ("quilt".equalsIgnoreCase(loader)) {
                profileUrl = "https://meta.quiltmc.org/v3/versions/loader/" + Http.encode(gameVersion) + "/"
                        + Http.encode(loaderVersion) + "/profile/json";
                officialProfileUrl = profileUrl;
            } else {
                officialProfileUrl = "https://meta.fabricmc.net/v2/versions/loader/" + Http.encode(gameVersion) + "/"
                        + Http.encode(loaderVersion) + "/profile/json";
                profileUrl = mirror.useBmcl()
                        ? Mirror.BMCLAPI + "/fabric-meta/v2/versions/loader/" + Http.encode(gameVersion) + "/"
                                + Http.encode(loaderVersion) + "/profile/json"
                        : officialProfileUrl;
            }
            Map<String, Object> profile;
            try {
                profile = Json.parseObject(Http.getJson(profileUrl).body());
            } catch (Exception e) {
                if (!profileUrl.equals(officialProfileUrl)) {
                    try {
                        profile = Json.parseObject(Http.getJson(officialProfileUrl).body());
                    } catch (Exception fallback) {
                        throw new IOException("无法获取 " + loader + " " + loaderVersion + " 配置：" + e.getMessage());
                    }
                } else {
                    throw new IOException("无法获取 " + loader + " " + loaderVersion + " 配置：" + e.getMessage());
                }
            }
            profileId = Json.str(profile, "id", loader + "-" + loaderVersion + "-" + gameVersion);
            mainClass = Json.str(profile, "mainClass", mainClass);
            List<Map<String, Object>> loaderLibraries = new ArrayList<>();
            for (Object value : Json.list(profile.get("libraries"))) loaderLibraries.add(Json.map(value));
            // Loader libraries (ASM, Mixin, intermediary) must win over Minecraft's older copies.
            libraries.addAll(0, loaderLibraries);
            Map<String, Object> profileArgs = Json.map(profile.get("arguments"));
            jvmArgs.addAll(Json.list(profileArgs.get("jvm")));
            gameArgs.addAll(Json.list(profileArgs.get("game")));
            job.log(loader + " " + loaderVersion + " · " + profileId);
            if (!loaderVersion.equals(Json.str(instance, "loaderVersion", ""))) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("loaderVersion", loaderVersion);
                instances.update(instanceId, change);
            }
        } else if (!resolvedProfile && "forge".equalsIgnoreCase(loader)) {
            job.progress(0.03, "Forge", "检查 Forge 运行时");
            Map<String, Object> profile = forgeFor(localMeta).ensureForge(instance, job);
            profileId = Json.str(profile, "id", gameVersion + "-forge-" + loaderVersion);
            String inherits = Json.str(profile, "inheritsFrom", gameVersion);
            if (!inherits.isBlank() && !inherits.equals(gameVersion)) base = localMeta.detail(inherits);
            mainClass = Json.str(profile, "mainClass", Json.str(base, "mainClass", ""));
            libraries.clear();
            for (Object value : Json.list(base.get("libraries"))) libraries.add(Json.map(value));
            Map<String, Object> baseArguments = Json.map(base.get("arguments"));
            jvmArgs.clear();
            jvmArgs.addAll(Json.list(baseArguments.get("jvm")));
            gameArgs.clear();
            gameArgs.addAll(Json.list(baseArguments.get("game")));
            legacyArgs = Json.str(base, "minecraftArguments", "");
            List<Map<String, Object>> loaderLibraries = new ArrayList<>();
            for (Object value : Json.list(profile.get("libraries"))) loaderLibraries.add(Json.map(value));
            libraries.addAll(0, loaderLibraries);
            Map<String, Object> forgeArgs = Json.map(profile.get("arguments"));
            jvmArgs.addAll(Json.list(forgeArgs.get("jvm")));
            gameArgs.addAll(Json.list(forgeArgs.get("game")));
            String forgeLegacy = Json.str(profile, "minecraftArguments", "");
            if (!forgeLegacy.isBlank()) legacyArgs = (legacyArgs + " " + forgeLegacy).trim();
            int marker = profileId.indexOf("-forge-");
            if (marker >= 0) {
                loaderVersion = profileId.substring(marker + "-forge-".length());
                if (!loaderVersion.equals(Json.str(instance, "loaderVersion", ""))) {
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("loaderVersion", loaderVersion);
                    instances.update(instanceId, change);
                }
            }
            job.log("Forge " + loaderVersion + " · " + profileId);
        } else if (!resolvedProfile && !"vanilla".equalsIgnoreCase(loader) && !loader.isBlank()) {
            throw new IOException("当前版本支持 Vanilla / Fabric / Quilt / Forge，"
                    + loader + " 实例需要先手动安装加载器");
        }

        if (prepareOnly) {
            Path nativesDir = gameDir.resolve("natives").resolve(Util.safeName(profileId));
            double filesStart = "forge".equalsIgnoreCase(loader) ? 0.2 : 0.08;
            Path clientJar = localMeta.ensureClient(base, job, filesStart, 0.11);
            job.progress(filesStart + 0.11, "依赖库", "校验 Minecraft " + gameVersion + " 依赖");
            List<Path> classpath = localMeta.prepareLibraries(libraries, job, filesStart + 0.12, 0.3, nativesDir);
            localMeta.ensureLogging(base, job);
            Path assetIndex = localMeta.ensureAssetIndex(base, job, 0.58, 0.03);
            job.progress(0.62, "资源文件", "检查游戏资源");
            localMeta.ensureAssets(assetIndex, job, 0.62, 0.36);
            job.progress(0.99, "完成", "游戏文件已就绪");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("prepared", true);
            result.put("instance", instanceId);
            result.put("libraries", classpath.size());
            result.put("client", clientJar.toString());
            return result;
        }

        Map<String, Object> account = auth.ensureFresh(job);
        String playerName = Json.str(account, "name", "");
        String uuid = Json.str(account, "id", "").replace("-", "");
        String accessToken = Json.str(account, "accessToken", "");
        String accountType = Json.str(account, "type", "microsoft");
        String userType = "offline".equals(accountType) ? "legacy"
                : "thirdparty".equals(accountType) ? "mojang" : "msa";
        String authlibAgent = "";
        if ("thirdparty".equals(accountType)) {
            Path agent = auth.ensureAuthlibInjector(dataDir, job);
            authlibAgent = "-javaagent:" + agent + "=" + Json.str(account, "server", "");
        }
        if (playerName.isBlank() || accessToken.isBlank()) throw new IOException("登录状态不可用，请重新登录");

        int requiredJava = Json.integer(Json.map(base.get("javaVersion")), "majorVersion", 17);
        String component = Json.str(Json.map(base.get("javaVersion")), "component", JavaService.componentFor(requiredJava));
        JavaService.JavaInfo java = javaService.choose(requiredJava, gameVersion);
        String instanceJava = Json.str(instance, "javaPath", "");
        if (!instanceJava.isBlank() && Files.isRegularFile(Path.of(instanceJava))) {
            JavaService.JavaInfo selected = javaService.inspect(Path.of(instanceJava));
            if (selected != null) java = selected;
        }
        if (java == null || java.major() < requiredJava
                || (requiredJava <= 8 && java.major() != 8)) {
            job.progress(0.06, "Java 运行时", "需要 Java " + requiredJava + " · 准备官方运行时");
            Path javaExe = javaService.ensureRuntime(component, requiredJava, job);
            JavaService.JavaInfo inspected = javaService.inspect(javaExe);
            if (inspected == null) throw new IOException("Java 运行时不可用：" + javaExe);
            java = inspected;
            javaService.detect(true);
        }
        job.log("Java " + java.version() + " · " + java.path());
        if (config.getBool("highPerformanceGpu", true)) GpuPreference.apply(java.path(), job);

        Path nativesDir = gameDir.resolve("natives").resolve(Util.safeName(profileId));
        double filesStart = "forge".equalsIgnoreCase(loader) ? 0.2 : 0.08;
        Path clientJar = localMeta.ensureClient(base, job, filesStart, 0.11);
        job.progress(filesStart + 0.11, "依赖库", "校验 Minecraft " + gameVersion + " 依赖");
        List<Path> classpath = localMeta.prepareLibraries(libraries, job, filesStart + 0.12, 0.3, nativesDir);
        // Forge's bootstrap loads the patched client-extra module itself. Adding the vanilla
        // client jar as well creates two modules that export net.minecraft.obfuscate.
        boolean forgeBootstrap = mainClass.toLowerCase(Locale.ROOT).contains("bootstraplauncher")
                || profileId.toLowerCase(Locale.ROOT).contains("forge");
        if (!"forge".equalsIgnoreCase(loader) && !forgeBootstrap) classpath.add(clientJar);

        Path logConfig = localMeta.ensureLogging(base, job);
        Path assetIndex = localMeta.ensureAssetIndex(base, job, 0.58, 0.03);
        job.progress(0.62, "资源文件", "检查游戏资源");
        localMeta.ensureAssets(assetIndex, job, 0.62, 0.26);

        String classpathString = String.join(File.pathSeparator,
                classpath.stream().map(Path::toString).toList());
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("auth_player_name", playerName);
        vars.put("version_name", profileId);
        vars.put("game_directory", gameDir.toString());
        vars.put("assets_root", localMeta.assetsDir().toString());
        vars.put("game_assets", localMeta.assetsDir().resolve("virtual")
                .resolve(Json.str(base, "assets", "legacy")).toString());
        vars.put("assets_index_name", Json.str(base, "assets", "legacy"));
        vars.put("auth_uuid", uuid);
        vars.put("auth_access_token", accessToken);
        vars.put("auth_session", accessToken);
        vars.put("clientid", "microsoft".equals(accountType) ? AuthService.CLIENT_ID : "");
        vars.put("auth_xuid", "");
        vars.put("user_type", userType);
        vars.put("version_type", Json.str(base, "type", "release"));
        vars.put("user_properties", "{}");
        vars.put("natives_directory", nativesDir.toString());
        vars.put("launcher_name", "UnionCraftLauncher");
        vars.put("launcher_version", Util.VERSION);
        vars.put("classpath", classpathString);
        vars.put("classpath_separator", File.pathSeparator);
        vars.put("library_directory", localMeta.librariesDir().toString());
        if (logConfig != null) vars.put("path", logConfig.toString());

        List<String> command = new ArrayList<>();
        command.add(java.path().toString());
        int instanceMemory = Json.integer(instance, "memoryMb", 0);
        int memory = Math.max(1024, instanceMemory > 0 ? instanceMemory : config.getInt("memoryMb", 4096));
        int minMemory = Math.max(512, Math.min(memory, config.getInt("minMemoryMb", 1024)));
        command.add("-Xms" + minMemory + "M");
        command.add("-Xmx" + memory + "M");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Dminecraft.launcher.brand=UnionCraftLauncher");
        command.add("-Dminecraft.launcher.version=" + Util.VERSION);
        command.add("-Dlog4j2.formatMsgNoLookups=true");
        if (java.major() >= 9) {
            command.add("-XX:+UseG1GC");
            command.add("-XX:+ParallelRefProcEnabled");
            command.add("-XX:MaxGCPauseMillis=200");
        }
        command.add("-Djava.library.path=" + nativesDir);
        if (!authlibAgent.isBlank()) command.add(authlibAgent);

        boolean hasClasspathArg = false;
        for (Object raw : jvmArgs) {
            List<String> values = argumentValues(raw);
            for (String value : values) {
                String resolved = substitute(value, vars);
                if (resolved.startsWith("-DFabricMcEmu=")) {
                    resolved = "-DFabricMcEmu=" + resolved.substring("-DFabricMcEmu=".length()).trim();
                }
                if (resolved.equals("-cp") || resolved.equals("-classpath")) hasClasspathArg = true;
                command.add(resolved);
            }
        }
        if (!hasClasspathArg) {
            command.add("-cp");
            command.add(classpathString);
        }
        command.add(mainClass);

        if (legacyArgs != null && !legacyArgs.isBlank()) {
            for (String piece : legacyArgs.trim().split("\\s+")) {
                command.add(substitute(piece, vars));
            }
        } else {
            for (Object raw : gameArgs) {
                command.addAll(argumentValues(raw).stream().map(value -> substitute(value, vars)).toList());
            }
        }
        if (!command.contains("--gameDir")) {
            command.add("--gameDir");
            command.add(gameDir.toString());
        }
        if (!command.contains("--assetsDir") && !command.contains("${assets_root}")) {
            command.add("--assetsDir");
            command.add(localMeta.assetsDir().toString());
        }
        if (!command.contains("--assetIndex")) {
            command.add("--assetIndex");
            command.add(Json.str(base, "assets", "legacy"));
        }
        if (legacyArgs != null && !legacyArgs.isBlank()) {
            command.add("--userType");
            command.add("msa");
            command.add("--versionType");
            command.add(Json.str(base, "type", "release"));
        }

        Path logFile = gameDir.resolve("logs").resolve("launcher-" + Util.nowTime().replace(':', '-') + ".log");
        String printable = redact(String.join(" ", quoteForLog(command)), accessToken);
        job.log("启动命令 · " + printable);
        job.progress(0.93, "启动游戏", "创建游戏进程");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(gameDir.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        Map<String, String> env = builder.environment();
        env.put("MCLC_GAME_DIR", gameDir.toString());
        env.put("MCLC_VERSION", gameVersion);
        Process process = builder.start();
        running.put(instanceId, process);
        try {
            Thread.sleep(1400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!process.isAlive()) {
            String tail = tail(logFile, 80);
            running.remove(instanceId);
            throw new IOException("Minecraft 进程提前退出（代码 " + process.exitValue() + "）\n" + tail);
        }
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("lastPlayed", System.currentTimeMillis());
        instances.update(instanceId, change);
        Thread watcher = new Thread(() -> {
            try {
                int code = process.waitFor();
                running.remove(instanceId);
                try {
                    Files.writeString(logFile.resolveSibling("last-exit.txt"),
                            "exit=" + code + " at " + Instant.now(), StandardCharsets.UTF_8);
                } catch (IOException ignored) { }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "ucl-game-watcher");
        watcher.setDaemon(true);
        watcher.start();
        job.log("游戏进程已启动 · PID " + process.pid() + " · 日志 " + logFile);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", process.pid());
        result.put("log", logFile.toString());
        result.put("instance", instanceId);
        result.put("version", gameVersion);
        result.put("loader", loader);
        return result;
    }

    private String latestLoader(String loader, String gameVersion) throws Exception {
        if ("quilt".equalsIgnoreCase(loader)) {
            List<Object> list = Json.list(Json.parse(Http.getJson(
                    "https://meta.quiltmc.org/v3/versions/loader/" + Http.encode(gameVersion)).body()));
            for (Object value : list) {
                Map<String, Object> entry = Json.map(value);
                String version = Json.str(Json.map(entry.get("loader")), "version", "");
                if (!version.isBlank()) return version;
            }
        } else {
            String official = "https://meta.fabricmc.net/v2/versions/loader/" + Http.encode(gameVersion);
            String url = mirror.useBmcl()
                    ? Mirror.BMCLAPI + "/fabric-meta/v2/versions/loader/" + Http.encode(gameVersion)
                    : official;
            List<Object> list;
            try {
                list = Json.list(Json.parse(Http.getJson(url).body()));
            } catch (Exception e) {
                if (url.equals(official)) throw e;
                list = Json.list(Json.parse(Http.getJson(official).body()));
            }
            for (Object value : list) {
                Map<String, Object> entry = Json.map(value);
                Map<String, Object> loaderInfo = Json.map(entry.get("loader"));
                if (Json.bool(loaderInfo, "stable", false)) return Json.str(loaderInfo, "version", "");
            }
            if (!list.isEmpty()) return Json.str(Json.map(Json.map(list.get(0)).get("loader")), "version", "");
        }
        throw new IOException("找不到 " + loader + " 的加载器版本");
    }

    private List<String> argumentValues(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof String s) {
            out.add(s);
        } else if (raw instanceof List<?> list) {
            for (Object item : list) out.addAll(argumentValues(item));
        } else {
            Map<String, Object> map = Json.map(raw);
            if (MinecraftMeta.rulesAllow(Json.list(map.get("rules")))) {
                out.addAll(argumentValues(map.get("value")));
            }
        }
        return out;
    }

    public static String substitute(String value, Map<String, String> vars) {
        String out = value;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static List<String> quoteForLog(List<String> command) {
        List<String> out = new ArrayList<>();
        for (String arg : command) {
            if (arg.contains(" ") || arg.contains("\"")) {
                out.add("\"" + arg.replace("\"", "\\\"") + "\"");
            } else {
                out.add(arg);
            }
        }
        return out;
    }

    private static String redact(String command, String secret) {
        if (secret == null || secret.length() < 8) return command;
        return command.replace(secret, "***");
    }

    public String tailLog(String instanceId, int lines) {
        try {
            Path dir = instances.gameDir(instanceId).resolve("logs");
            if (!Files.isDirectory(dir)) return "";
            java.util.List<Path> files;
            try (var stream = Files.list(dir)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".log"))
                        .sorted(java.util.Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed())
                        .toList();
            }
            for (Path file : files) {
                if (file.getFileName().toString().startsWith("launcher-")
                        && file.toFile().length() == 0) continue;
                String text = tail(file, lines);
                if (!text.isBlank()) return text;
            }
            return "";
        } catch (Exception e) {
            return "";
        }
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

    public static String platformLabel() {
        String os = System.getProperty("os.name", "Unknown");
        String arch = System.getProperty("os.arch", "");
        return os + " · " + arch;
    }
}

