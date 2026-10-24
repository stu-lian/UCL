package org.unioncraft.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads and caches Mojang version metadata, libraries, assets and natives. */
public final class MinecraftMeta {
    public static final String VERSION_MANIFEST = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final Config config;
    private final Path dataDir;
    private final Path root;
    private final Path versionsDir;
    private final Path librariesDir;
    private final Path assetsDir;
    private final Path cacheDir;
    private final Mirror mirror;
    private volatile Map<String, Object> manifest;

    public MinecraftMeta(Config config, Path dataDir) {
        this(config, dataDir, config.minecraftDir());
    }

    public MinecraftMeta(Config config, Path dataDir, Path root) {
        this.config = config;
        this.dataDir = dataDir;
        this.root = root.toAbsolutePath().normalize();
        this.versionsDir = this.root.resolve("versions");
        this.librariesDir = this.root.resolve("libraries");
        this.assetsDir = this.root.resolve("assets");
        this.cacheDir = dataDir.resolve("cache");
        this.mirror = new Mirror(config);
    }

    public Path root() { return root; }
    public Path librariesDir() { return librariesDir; }
    public Path assetsDir() { return assetsDir; }
    public Path versionsDir() { return versionsDir; }

    public synchronized Map<String, Object> manifest() throws Exception {
        if (manifest != null) return manifest;
        Path cached = cacheDir.resolve("version_manifest_v2.json");
        Map<String, Object> parsed = null;
        if (Files.isRegularFile(cached)) {
            try {
                parsed = Json.parseObject(Util.read(cached, "{}"));
            } catch (Exception ignored) { }
        }
        boolean stale = !Files.isRegularFile(cached)
                || System.currentTimeMillis() - Files.getLastModifiedTime(cached).toMillis() > 6L * 3600_000;
        if (parsed == null || stale) {
            try {
                String body = mirror.getJsonBody(mirror.manifestUrl());
                Map<String, Object> fresh = Json.parseObject(body);
                if (!Json.list(fresh.get("versions")).isEmpty()) {
                    Util.write(cached, body);
                    parsed = fresh;
                }
            } catch (Exception e) {
                if (parsed == null) throw e;
            }
        }
        if (parsed == null) throw new IOException("无法获取 Minecraft 版本清单");
        manifest = parsed;
        return parsed;
    }

    public List<Map<String, Object>> versionList() throws Exception {
        Map<String, Object> latest = Json.map(manifest().get("latest"));
        String latestRelease = Json.str(latest, "release", "");
        String latestSnapshot = Json.str(latest, "snapshot", "");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object value : Json.list(manifest().get("versions"))) {
            Map<String, Object> raw = Json.map(value);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", Json.str(raw, "id", ""));
            item.put("type", Json.str(raw, "type", "release"));
            item.put("url", Json.str(raw, "url", ""));
            item.put("releaseTime", Json.str(raw, "releaseTime", ""));
            item.put("latestRelease", Json.str(raw, "id", "").equals(latestRelease));
            item.put("latestSnapshot", Json.str(raw, "id", "").equals(latestSnapshot));
            result.add(item);
        }
        result.sort(Comparator.comparing((Map<String, Object> m) -> Json.str(m, "releaseTime", "")).reversed());
        return result;
    }

    public String latestRelease() throws Exception {
        return Json.str(Json.map(manifest().get("latest")), "release", "");
    }

    public Map<String, Object> detail(String id) throws Exception {
        Path localJson = versionsDir.resolve(Util.safeName(id)).resolve(Util.safeName(id) + ".json");
        if (Files.isRegularFile(localJson)) {
            try {
                Map<String, Object> parsed = Json.parseObject(Util.read(localJson, "{}"));
                if (!Json.str(parsed, "id", "").isBlank()) return parsed;
            } catch (Exception ignored) { }
        }
        Path file = cacheDir.resolve("versions").resolve(Util.safeName(id) + ".json");
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> parsed = Json.parseObject(Util.read(file, "{}"));
                if (Json.str(parsed, "id", "").equals(id)) return parsed;
            } catch (Exception ignored) { }
        }
        String url = "";
        for (Object value : Json.list(manifest().get("versions"))) {
            Map<String, Object> item = Json.map(value);
            if (id.equals(Json.str(item, "id", ""))) {
                url = Json.str(item, "url", "");
                break;
            }
        }
        if (url.isBlank()) throw new IOException("版本不存在：" + id);
        String mirrorUrl = mirror.versionJsonUrl(id, url);
        Map<String, Object> parsed = Json.parseObject(mirror.getJsonBody(mirrorUrl));
        Util.write(file, Json.write(parsed));
        return parsed;
    }

    public List<Map<String, Object>> localVersions() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (!Files.isDirectory(versionsDir)) return result;
        try (var stream = Files.list(versionsDir)) {
            for (Path dir : stream.toList()) {
                if (!Files.isDirectory(dir)) continue;
                String name = dir.getFileName().toString();
                Path json = dir.resolve(name + ".json");
                if (!Files.isRegularFile(json)) continue;
                try {
                    Map<String, Object> profile = Json.parseObject(Util.read(json, "{}"));
                    String id = Json.str(profile, "id", name);
                    if (id.isBlank()) id = name;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", id);
                    item.put("type", Json.str(profile, "type", "release"));
                    item.put("releaseTime", Json.str(profile, "releaseTime", ""));
                    item.put("latestRelease", false);
                    item.put("latestSnapshot", false);
                    item.put("local", true);
                    item.put("installed", true);
                    item.put("loader", detectLoader(id, profile));
                    result.add(item);
                } catch (Exception ignored) { }
            }
        } catch (IOException ignored) { }
        result.sort(Comparator.comparing((Map<String, Object> m) -> Json.str(m, "releaseTime", "")).reversed());
        return result;
    }

    public static String detectLoader(String id, Map<String, Object> profile) {
        String lower = id.toLowerCase(Locale.ROOT);
        String main = Json.str(profile, "mainClass", "").toLowerCase(Locale.ROOT);
        if (lower.contains("neoforge") || main.contains("neoforge")) return "neoforge";
        if (lower.contains("forge") || main.contains("bootstraplauncher")) return "forge";
        if (lower.contains("quilt")) return "quilt";
        if (lower.contains("fabric") || main.contains("fabricmc")) return "fabric";
        return "vanilla";
    }

    /**
     * Resolves a local or remote version profile, merging inheritsFrom chains.
     * This is what makes imported clients (Forge/Fabric/vanilla) launchable.
     */
    public Map<String, Object> resolveProfile(String id) throws Exception {
        Map<String, Object> child = detail(id);
        return mergeProfile(child, 0);
    }

    private Map<String, Object> mergeProfile(Map<String, Object> child, int depth) throws Exception {
        if (depth > 6) throw new IOException("版本继承层级过深：" + Json.str(child, "id", ""));
        Map<String, Object> merged = new LinkedHashMap<>(child);
        String parentId = Json.str(child, "inheritsFrom", "");
        if (parentId.isBlank()) return merged;
        Map<String, Object> parent = mergeProfile(detail(parentId), depth + 1);
        merged.putAll(parent);
        merged.put("id", Json.str(child, "id", parentId));
        merged.put("inheritsFrom", parentId);
        String mainClass = Json.str(child, "mainClass", "");
        if (!mainClass.isBlank()) merged.put("mainClass", mainClass);
        merged.put("type", Json.str(child, "type", Json.str(parent, "type", "release")));
        merged.put("releaseTime", Json.str(child, "releaseTime", Json.str(parent, "releaseTime", "")));
        merged.put("libraries", mergeLibraries(Json.list(child.get("libraries")), Json.list(parent.get("libraries"))));
        Map<String, Object> childArgs = Json.map(child.get("arguments"));
        Map<String, Object> parentArgs = Json.map(parent.get("arguments"));
        merged.put("arguments", Json.obj(
                "game", concat(Json.list(parentArgs.get("game")), Json.list(childArgs.get("game"))),
                "jvm", concat(Json.list(parentArgs.get("jvm")), Json.list(childArgs.get("jvm")))));
        String childLegacy = Json.str(child, "minecraftArguments", "");
        String parentLegacy = Json.str(parent, "minecraftArguments", "");
        if (!childLegacy.isBlank() || !parentLegacy.isBlank()) {
            merged.put("minecraftArguments", (parentLegacy + " " + childLegacy).trim());
        }
        if (Json.map(child.get("logging")).isEmpty()) merged.put("logging", parent.get("logging"));
        if (Json.map(child.get("downloads")).isEmpty()) merged.put("downloads", parent.get("downloads"));
        if (Json.map(child.get("assetIndex")).isEmpty()) merged.put("assetIndex", parent.get("assetIndex"));
        if (Json.str(child, "assets", "").isBlank()) merged.put("assets", Json.str(parent, "assets", "legacy"));
        if (Json.map(child.get("javaVersion")).isEmpty()) merged.put("javaVersion", parent.get("javaVersion"));
        return merged;
    }

    private static List<Object> mergeLibraries(List<Object> child, List<Object> parent) {
        List<Object> all = new ArrayList<>();
        all.addAll(child);
        all.addAll(parent);
        return all;
    }

    private static List<Object> concat(List<Object> first, List<Object> second) {
        List<Object> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    /** Reads a version profile installed by Forge/NeoForge into the shared versions directory. */
    public Map<String, Object> localDetail(String id) throws IOException {
        Path file = versionsDir.resolve(Util.safeName(id)).resolve(Util.safeName(id) + ".json");
        if (!Files.isRegularFile(file)) throw new IOException("本地加载器版本不存在：" + id);
        return Json.parseObject(Util.read(file, "{}"));
    }

    public Map<String, Object> findLocalLoaderProfile(String mcVersion, String loader, String loaderVersion)
            throws IOException {
        if (!Files.isDirectory(versionsDir)) throw new IOException("没有已安装的加载器");
        try (var stream = Files.list(versionsDir)) {
            for (Path dir : stream.toList()) {
                String name = dir.getFileName().toString();
                if (!name.toLowerCase(Locale.ROOT).contains(loader.toLowerCase(Locale.ROOT))) continue;
                if (!loaderVersion.isBlank() && !name.contains(loaderVersion)) continue;
                Path json = dir.resolve(name + ".json");
                if (!Files.isRegularFile(json)) continue;
                try {
                    Map<String, Object> profile = Json.parseObject(Util.read(json, "{}"));
                    if (mcVersion.equals(Json.str(profile, "inheritsFrom", ""))) return profile;
                } catch (Exception ignored) { }
            }
        }
        throw new IOException("加载器安装完成，但没有找到对应的版本配置");
    }

    public Path ensureClient(Map<String, Object> detail, Jobs.Job job, double start, double span) throws Exception {
        String id = Json.str(detail, "id", "unknown");
        Map<String, Object> client = Json.map(Json.map(detail.get("downloads")).get("client"));
        String url = Json.str(client, "url", "");
        if (url.isBlank()) throw new IOException("版本缺少客户端下载地址");
        Path jar = versionsDir.resolve(id).resolve(id + ".jar");
        long clientSize = Math.max(1, Json.longValue(client, "size", 1));
        job.details(0, 1, 0, clientSize, 0);
        mirror.download(url, jar, Json.str(client, "sha1", ""), n ->
                job.progress(start + span * Math.min(1.0, n / (double) Math.max(1, clientSize)), "客户端", "下载 " + id + ".jar"));
        job.details(1, 1, 0, clientSize, 0);
        Path localJson = jar.getParent().resolve(id + ".json");
        if (!Files.isRegularFile(localJson)) {
            Util.write(localJson, Json.write(detail));
        }
        job.log("客户端核心已就绪 · " + Util.bytes(Files.size(jar)));
        return jar;
    }

    public Path ensureLogging(Map<String, Object> detail, Jobs.Job job) throws Exception {
        Map<String, Object> logging = Json.map(Json.map(detail.get("logging")).get("client"));
        if (logging.isEmpty()) return null;
        Map<String, Object> file = Json.map(logging.get("file"));
        String url = Json.str(file, "url", "");
        if (url.isBlank()) return null;
        String id = Json.str(file, "id", "client");
        Path target = root.resolve("logging").resolve(Util.safeName(id));
        mirror.download(url, target, Json.str(file, "sha1", ""), null);
        job.log("日志配置已就绪 · " + Json.str(logging, "argument", ""));
        return target;
    }

    public Path ensureAssetIndex(Map<String, Object> detail, Jobs.Job job, double start, double span) throws Exception {
        Map<String, Object> index = Json.map(detail.get("assetIndex"));
        String id = Json.str(index, "id", Json.str(detail, "assets", "legacy"));
        String url = Json.str(index, "url", "");
        Path target = assetsDir.resolve("indexes").resolve(id + ".json");
        if (url.isBlank()) {
            if (Files.isRegularFile(target)) return target;
            throw new IOException("版本缺少资源索引");
        }
        long indexSize = Math.max(1, Json.longValue(index, "size", 1));
        job.details(0, 1, 0, indexSize, 0);
        mirror.download(url, target, Json.str(index, "sha1", ""), n ->
                job.progress(start + span * Math.min(1.0, n / (double) Math.max(1, indexSize)), "资源索引", "下载 " + id + ".json"));
        job.details(1, 1, 0, indexSize, 0);
        job.log("资源索引已就绪 · " + id);
        return target;
    }

    public void ensureAssets(Path indexFile, Jobs.Job job, double start, double span) throws Exception {
        Map<String, Object> index = Json.parseObject(Util.read(indexFile, "{}"));
        Map<String, Object> objects = Json.map(index.get("objects"));
        List<Dl> tasks = new ArrayList<>();
        for (Map.Entry<String, Object> e : objects.entrySet()) {
            Map<String, Object> object = Json.map(e.getValue());
            String hash = Json.str(object, "hash", "");
            if (hash.isBlank()) continue;
            Path target = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
            tasks.add(new Dl(url, target, hash, Json.longValue(object, "size", 0), "资源"));
        }
        job.log("资源文件 " + tasks.size() + " 个 · 开始校验与下载");
        downloadAll(tasks, job, "资源文件", start, span);
        // Legacy versions expect a virtual asset tree instead of the hash store.
        if (Json.bool(index, "virtual", false) || Json.bool(index, "map_to_resources", false)) {
            String assetId = indexFile.getFileName().toString().replaceFirst("\\.json$", "");
            Path virtualRoot = Json.bool(index, "virtual", false)
                    ? assetsDir.resolve("virtual").resolve(assetId)
                    : root.resolve("resources");
            long copied = 0;
            for (Map.Entry<String, Object> e : objects.entrySet()) {
                Map<String, Object> object = Json.map(e.getValue());
                String hash = Json.str(object, "hash", "");
                Path source = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                Path target = virtualRoot.resolve(e.getKey()).normalize();
                if (!target.startsWith(virtualRoot.normalize())) continue;
                if (Files.isRegularFile(source) && !Files.exists(target)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                }
            }
            job.log("旧版资源映射完成 · " + copied + " 个文件");
        }
    }

    public List<Path> prepareLibraries(List<Map<String, Object>> libraries, Jobs.Job job,
                                       double start, double span, Path nativesDir) throws Exception {
        List<Dl> downloads = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        List<Map<String, Object>> nativeJars = new ArrayList<>();
        List<Path> classpath = new ArrayList<>();
        for (Object value : libraries) {
            Map<String, Object> lib = Json.map(value);
            if (!rulesAllow(Json.list(lib.get("rules")))) continue;
            Map<String, Object> downloadsMap = Json.map(lib.get("downloads"));
            Map<String, Object> artifact = Json.map(downloadsMap.get("artifact"));
            String name = Json.str(lib, "name", "");
            String artifactUrl = Json.str(artifact, "url", "");
            String artifactPath = Json.str(artifact, "path", "");
            if (artifactPath.isBlank()) {
                String[] coord = mavenPath(name);
                if (coord != null) {
                    artifactPath = coord[0];
                    if (artifactUrl.isBlank()) artifactUrl = mavenBase(lib) + coord[0];
                }
            }
            boolean nativeArtifact = artifactPath.contains("-natives-");
            if (nativeArtifact && !nativeVariantAllowed(artifactPath)) continue;
            if (!artifactPath.isBlank() && !artifactUrl.isBlank()) {
                Path target = librariesDir.resolve(artifactPath);
                String sha1 = Json.str(artifact, "sha1", Json.str(lib, "sha1", ""));
                long size = Json.longValue(artifact, "size", Json.longValue(lib, "size", 0));
                String key = target.toString();
                if (dedupe.add(key)) downloads.add(new Dl(artifactUrl, target, sha1, size, "依赖库"));
                if (nativeArtifact) {
                    Map<String, Object> holder = new LinkedHashMap<>();
                    holder.put("path", target);
                    holder.put("extract", Json.map(lib.get("extract")));
                    nativeJars.add(holder);
                } else {
                    classpath.add(target);
                }
            }
            if (hasNatives(lib)) {
                String classifier = nativeClassifier(lib);
                if (classifier == null) continue;
                Map<String, Object> classifierArtifact = Json.map(Json.map(downloadsMap.get("classifiers")).get(classifier));
                String nativeUrl = Json.str(classifierArtifact, "url", "");
                String nativePath = Json.str(classifierArtifact, "path", "");
                if (nativePath.isBlank() && !name.isBlank()) {
                    String[] coord = mavenPath(name);
                    if (coord != null) {
                        String basePath = coord[0];
                        String ext = basePath.endsWith(".jar") ? basePath.substring(0, basePath.length() - 4) : basePath;
                        nativePath = ext + "-" + classifier + ".jar";
                        if (nativeUrl.isBlank()) nativeUrl = mavenBase(lib) + nativePath;
                    }
                }
                if (!nativePath.isBlank() && !nativeUrl.isBlank()) {
                    Path target = librariesDir.resolve(nativePath);
                    String key = target.toString();
                    if (dedupe.add(key)) {
                        downloads.add(new Dl(nativeUrl, target, Json.str(classifierArtifact, "sha1", ""),
                                Json.longValue(classifierArtifact, "size", 0), "原生库"));
                    }
                    Map<String, Object> holder = new LinkedHashMap<>();
                    holder.put("path", target);
                    holder.put("extract", Json.map(lib.get("extract")));
                    nativeJars.add(holder);
                } else if (Files.isRegularFile(librariesDir.resolve(Json.str(Json.map(downloadsMap.get("artifact")), "path", "")))) {
                    // Some old versions put natives inside the main artifact.
                    Map<String, Object> holder = new LinkedHashMap<>();
                    holder.put("path", librariesDir.resolve(Json.str(Json.map(downloadsMap.get("artifact")), "path", "")));
                    holder.put("extract", Json.map(lib.get("extract")));
                    nativeJars.add(holder);
                }
            }
        }
        job.log("Minecraft 依赖库 " + downloads.size() + " 个");
        downloadAll(downloads, job, "依赖库", start, start == 0 ? span : span * 0.85);
        Files.createDirectories(nativesDir);
        for (Map<String, Object> nativeJar : nativeJars) {
            Path jar = (Path) nativeJar.get("path");
            if (jar != null && Files.isRegularFile(jar)) {
                extractNatives(jar, nativesDir, Json.map(nativeJar.get("extract")));
            }
        }
        prepareNativeLayout(nativesDir);
        return classpath;
    }

    /**
     * Modern versions (1.21.9+) load LWJGL from ${natives_directory}/java rather than the
     * natives root. Mirror the extracted native files there while keeping the old root layout.
     */
    private void prepareNativeLayout(Path nativesDir) throws IOException {
        Path javaDir = nativesDir.resolve("java");
        Files.createDirectories(javaDir);
        Files.createDirectories(nativesDir.resolve("lwjgl"));
        Files.createDirectories(nativesDir.resolve("jna"));
        Files.createDirectories(nativesDir.resolve("netty"));
        try (var stream = Files.walk(nativesDir, 4)) {
            for (Path source : stream.toList()) {
                if (!Files.isRegularFile(source) || source.startsWith(javaDir)) continue;
                String name = source.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!(name.endsWith(".dll") || name.endsWith(".so") || name.endsWith(".dylib")
                        || name.endsWith(".jnilib"))) continue;
                Files.copy(source, javaDir.resolve(source.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public void extractNatives(Path jar, Path nativesDir, Map<String, Object> extract) throws IOException {
        List<Pattern> excludes = new ArrayList<>();
        for (Object value : Json.list(extract.get("exclude"))) {
            excludes.add(Pattern.compile(globToRegex(String.valueOf(value))));
        }
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("META-INF/")) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (!(lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")
                        || lower.endsWith(".jnilib") || lower.endsWith(".pdb"))) continue;
                boolean skip = false;
                for (Pattern pattern : excludes) {
                    if (pattern.matcher(name).matches()) { skip = true; break; }
                }
                if (skip) continue;
                Path target = nativesDir.resolve(Path.of(name).getFileName().toString());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder("^");
        for (char c : glob.toCharArray()) {
            if (c == '*') sb.append(".*");
            else if (c == '?') sb.append('.');
            else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) sb.append('\\').append(c);
            else sb.append(c);
        }
        return sb.append('$').toString();
    }

    public static boolean rulesAllow(List<Object> rules) {
        if (rules == null || rules.isEmpty()) return true;
        boolean allowed = false;
        for (Object value : rules) {
            Map<String, Object> rule = Json.map(value);
            if (!ruleMatches(rule)) continue;
            allowed = "allow".equals(Json.str(rule, "action", "allow"));
        }
        return allowed;
    }

    private static boolean ruleMatches(Map<String, Object> rule) {
        Map<String, Object> os = Json.map(rule.get("os"));
        if (!os.isEmpty()) {
            String name = Json.str(os, "name", "");
            if (!name.isBlank()) {
                String actual = osName();
                if (!name.equals(actual)) return false;
            }
            String arch = Json.str(os, "arch", "");
            if (!arch.isBlank()) {
                String actualArch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
                boolean arm = actualArch.contains("aarch64") || actualArch.contains("arm64");
                if (arch.equals("arm64") && !arm) return false;
                if (arch.equals("x86") && arm) return false;
                if (arch.equals("x86") && actualArch.contains("64")) return false;
            }
            String version = Json.str(os, "version", "");
            if (!version.isBlank()) {
                try {
                    if (!Pattern.compile(version).matcher(System.getProperty("os.version", "")).find()) return false;
                } catch (Exception ignored) { }
            }
        }
        Map<String, Object> features = Json.map(rule.get("features"));
        for (Map.Entry<String, Object> e : features.entrySet()) {
            // Launcher features such as demo mode / quick play are never enabled in a normal launch.
            if (Json.bool(e.getValue(), false)) return false;
        }
        return true;
    }

    public static String osName() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "osx";
        return "linux";
    }

    private static boolean hasNatives(Map<String, Object> lib) {
        Map<String, Object> natives = Json.map(lib.get("natives"));
        return !natives.isEmpty();
    }

    private static String nativeClassifier(Map<String, Object> lib) {
        Map<String, Object> natives = Json.map(lib.get("natives"));
        String classifier = Json.str(natives, osName(), "");
        if (classifier.isBlank()) return null;
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        classifier = classifier.replace("${arch}", arch.contains("64") ? "64" : "32");
        return classifier;
    }

    private static boolean nativeVariantAllowed(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        boolean x86 = (arch.contains("x86") || arch.contains("i386") || arch.contains("i686"))
                && !arch.contains("64") && !arch.contains("amd64");
        if (lower.contains("natives-windows")) {
            if (lower.contains("arm64")) return arm64;
            if (lower.contains("x86")) return x86;
            return !arm64 && !x86;
        }
        if (lower.contains("natives-macos") || lower.contains("natives-osx")) {
            if (lower.contains("arm64")) return arm64;
            return !arm64;
        }
        if (lower.contains("natives-linux")) {
            if (lower.contains("arm64")) return arm64;
            return !arm64;
        }
        return true;
    }

    public static String[] mavenPath(String coordinate) {
        if (coordinate == null || coordinate.isBlank()) return null;
        String extension = "jar";
        String coord = coordinate;
        int at = coord.indexOf('@');
        if (at > 0) {
            extension = coord.substring(at + 1);
            coord = coord.substring(0, at);
        }
        String[] parts = coord.split(":");
        if (parts.length < 3) return null;
        String group = parts[0];
        String artifact = parts[1];
        String version = parts[2];
        String classifier = parts.length > 3 ? parts[3] : "";
        String file = artifact + "-" + version + (classifier.isBlank() ? "" : "-" + classifier) + "." + extension;
        String path = group.replace('.', '/') + "/" + artifact + "/" + version + "/" + file;
        return new String[]{path, file};
    }

    private static String mavenBase(Map<String, Object> lib) {
        String url = Json.str(lib, "url", "");
        if (url.isBlank()) return "https://libraries.minecraft.net/";
        return url.endsWith("/") ? url : url + "/";
    }

    public void downloadAll(List<Dl> tasks, Jobs.Job job, String stage, double start, double span) throws Exception {
        if (tasks.isEmpty()) {
            job.progress(start + span, stage, "已是最新");
            return;
        }
        long totalBytes = 0;
        for (Dl task : tasks) totalBytes += Math.max(0, task.size);
        final long total = Math.max(1, totalBytes);
        AtomicLong done = new AtomicLong();
        AtomicLong networkDone = new AtomicLong();
        AtomicLong networkSample = new AtomicLong();
        AtomicLong networkSampleAt = new AtomicLong(System.currentTimeMillis());
        AtomicLong networkSpeed = new AtomicLong();
        AtomicInteger files = new AtomicInteger();
        AtomicLong lastUpdate = new AtomicLong();
        job.details(0, tasks.size(), 0, totalBytes, 0);
        int configuredThreads = Math.max(1, Math.min(64, config.getInt("downloadConcurrency", 64)));
        int threads = configuredThreads;
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ucl-download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (Dl task : tasks) {
                futures.add(pool.submit((java.util.concurrent.Callable<Void>) () -> {
                    AtomicLong perFile = new AtomicLong();
                    AtomicLong perNetwork = new AtomicLong();
                    try {
                        mirror.download(task.url, task.path, task.sha1, n -> {
                            long previous = perFile.getAndSet(n);
                            long now = done.addAndGet(Math.max(0, n - previous));
                            long stamp = System.currentTimeMillis();
                            if (stamp - lastUpdate.get() > 90) {
                                lastUpdate.set(stamp);
                                synchronized (this) {
                                    job.progress(start + span * Math.min(1.0, now / (double) total), stage,
                                            task.label + " · 网络 " + Util.bytes(networkDone.get())
                                                    + " / " + Util.bytes(total));
                                }
                            }
                        }, n -> {
                            long delta = Math.max(0, n - perNetwork.getAndSet(n));
                            long network = networkDone.addAndGet(delta);
                            long stamp = System.currentTimeMillis();
                            long previousAt = networkSampleAt.get();
                            if (stamp - previousAt >= 450) {
                                long previous = networkSample.getAndSet(network);
                                long speed = (network - previous) * 1000L / Math.max(1, stamp - previousAt);
                                networkSpeed.set(Math.max(0, speed));
                                networkSampleAt.set(stamp);
                                synchronized (this) {
                                    job.details(files.get(), tasks.size(), network, total,
                                            networkSpeed.get());
                                }
                            }
                        });
                    } catch (Exception e) {
                        job.log("下载失败 · " + task.path.getFileName() + " · " + e.getMessage());
                        throw e;
                    }
                    files.incrementAndGet();
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception ex) throw ex;
                    throw new IOException(cause);
                }
            }
            job.progress(start + span, stage, tasks.size() + " 个文件就绪");
            job.details(tasks.size(), tasks.size(), networkDone.get(), total, 0);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    public record Dl(String url, Path path, String sha1, long size, String label) {}
}
