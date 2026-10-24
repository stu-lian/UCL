package org.unioncraft.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Detects local Java runtimes and can provision Mojang's official Java runtime when needed. */
public final class JavaService {
    private static final String RUNTIME_INDEX =
            "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

    private final Config config;
    private final Path dataDir;
    private volatile List<JavaInfo> cached;

    public record JavaInfo(Path path, int major, String version, String vendor, boolean managed) {
        public Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("path", path.toString());
            map.put("major", major);
            map.put("version", version);
            map.put("vendor", vendor);
            map.put("managed", managed);
            return map;
        }
    }

    public JavaService(Config config, Path dataDir) {
        this.config = config;
        this.dataDir = dataDir;
    }

    public synchronized List<JavaInfo> detect(boolean refresh) {
        if (cached != null && !refresh) return cached;
        Set<Path> candidates = new LinkedHashSet<>();
        String configured = config.getString("javaPath", "");
        if (!configured.isBlank()) candidates.add(Path.of(configured));
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) candidates.add(Path.of(javaHome, "bin", Util.WINDOWS ? "java.exe" : "java"));
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path p = Path.of(dir, Util.WINDOWS ? "java.exe" : "java");
                if (Files.isRegularFile(p)) candidates.add(p);
            }
        }
        if (Util.WINDOWS) {
            String pf = System.getenv("ProgramFiles");
            String pf86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LOCALAPPDATA");
            String user = System.getenv("USERPROFILE");
            String appData = System.getenv("APPDATA");
            for (String base : new String[]{pf, pf86}) {
                if (base == null) continue;
                for (String vendor : new String[]{"Java", "Zulu", "Eclipse Adoptium", "Microsoft", "BellSoft", "Amazon Corretto", "Semeru"}) {
                    scanVersionDirs(Path.of(base, vendor), candidates);
                }
            }
            if (local != null) scanVersionDirs(Path.of(local, "Programs", "Eclipse Adoptium"), candidates);
            if (user != null) {
                scanVersionDirs(Path.of(user, ".jdks"), candidates);
                scanVersionDirs(Path.of(user, "scoop", "apps", "temurin"), candidates);
            }
            if (appData != null) scanMinecraftRuntimes(Path.of(appData, ".minecraft", "runtime"), candidates);
        }
        Path managedRoot = dataDir.resolve("java");
        if (Files.isDirectory(managedRoot)) {
            try (var stream = Files.list(managedRoot)) {
                for (Path component : stream.toList()) {
                    Path direct = component.resolve("bin").resolve(Util.WINDOWS ? "java.exe" : "java");
                    if (Files.isRegularFile(direct)) candidates.add(direct);
                    Path nested = component.resolve("windows-x64").resolve(component.getFileName())
                            .resolve("bin").resolve("java.exe");
                    if (Files.isRegularFile(nested)) candidates.add(nested);
                }
            } catch (IOException ignored) { }
        }

        List<JavaInfo> found = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            if (candidate == null || !Files.isRegularFile(candidate)) continue;
            Path normalized;
            try {
                normalized = candidate.toAbsolutePath().normalize();
                // Oracle's javapath shim spawns the real JVM as a child. Resolve it so stopping
                // the game terminates the actual process tree.
                normalized = normalized.toRealPath();
            } catch (Exception e) {
                continue;
            }
            if (!seen.add(normalized.toString().toLowerCase(Locale.ROOT))) continue;
            JavaInfo info = inspect(normalized);
            if (info != null && seen.add("resolved:" + info.path().toString().toLowerCase(Locale.ROOT))) {
                found.add(info);
            }
        }
        found.sort(Comparator.comparingInt(JavaInfo::major).thenComparing(i -> i.path().toString()));
        cached = found;
        return found;
    }

    private void scanVersionDirs(Path base, Set<Path> out) {
        if (base == null || !Files.isDirectory(base)) return;
        try (var stream = Files.list(base)) {
            for (Path child : stream.toList()) {
                Path java = child.resolve("bin").resolve(Util.WINDOWS ? "java.exe" : "java");
                if (Files.isRegularFile(java)) out.add(java);
            }
        } catch (IOException ignored) { }
    }

    private void scanMinecraftRuntimes(Path runtime, Set<Path> out) {
        if (runtime == null || !Files.isDirectory(runtime)) return;
        try (var components = Files.list(runtime)) {
            for (Path component : components.toList()) {
                try (var platforms = Files.list(component)) {
                    for (Path platform : platforms.toList()) {
                        try (var builds = Files.list(platform)) {
                            for (Path build : builds.toList()) {
                                Path java = build.resolve("bin").resolve("java.exe");
                                if (Files.isRegularFile(java)) out.add(java);
                            }
                        } catch (IOException ignored) { }
                    }
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    public JavaInfo inspect(Path java) {
        try {
            Process process = new ProcessBuilder(java.toString(), "-XshowSettings:properties", "-version")
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int count = 0;
                while ((line = reader.readLine()) != null && count++ < 120) output.append(line).append('\n');
            }
            if (!process.waitFor(6, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            String text = output.toString();
            String version = property(text, "java.version");
            String vendor = property(text, "java.vendor");
            if (version.isBlank()) {
                int idx = text.indexOf("version \"");
                if (idx >= 0) {
                    int end = text.indexOf('"', idx + 9);
                    if (end > idx) version = text.substring(idx + 9, end);
                }
            }
            if (version.isBlank()) return null;
            int major = majorOf(version);
            if (major <= 0) return null;
            Path executable = java;
            String javaHome = property(text, "java.home");
            if (!javaHome.isBlank()) {
                Path fromHome = Path.of(javaHome, "bin", Util.WINDOWS ? "java.exe" : "java");
                if (Files.isRegularFile(fromHome)) {
                    try { executable = fromHome.toRealPath(); } catch (IOException ignored) { executable = fromHome; }
                }
            }
            boolean managed = executable.toAbsolutePath().normalize()
                    .startsWith(dataDir.toAbsolutePath().normalize());
            return new JavaInfo(executable, major, version, vendor.isBlank() ? "Unknown" : vendor, managed);
        } catch (Exception e) {
            return null;
        }
    }

    private static String property(String text, String key) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith(key + " =")) return trimmed.substring(key.length() + 2).trim();
        }
        return "";
    }

    public static int majorOf(String version) {
        if (version == null || version.isBlank()) return 0;
        if (version.startsWith("1.")) {
            String rest = version.substring(2);
            int dot = rest.indexOf('.');
            String part = dot >= 0 ? rest.substring(0, dot) : rest;
            try { return Integer.parseInt(part); } catch (NumberFormatException e) { return 8; }
        }
        String digits = version.split("[^0-9]")[0];
        try { return Integer.parseInt(digits); } catch (NumberFormatException e) { return 0; }
    }

    /** Selects the best available runtime for a required feature version. */
    public JavaInfo choose(int requiredMajor, String versionId) {
        List<JavaInfo> all = detect(false);
        String configured = config.getString("javaPath", "");
        if (!configured.isBlank()) {
            for (JavaInfo info : all) {
                try {
                    if (Files.isSameFile(Path.of(configured), info.path())) return info;
                } catch (Exception ignored) {
                    if (info.path().toString().equalsIgnoreCase(Path.of(configured).toAbsolutePath().toString())) return info;
                }
            }
        }
        if (all.isEmpty()) return null;
        // Old versions are happiest on 8; modern versions prefer the exact LTS they target.
        if (requiredMajor <= 8) {
            for (JavaInfo info : all) if (info.major() == 8) return info;
        }
        for (JavaInfo info : all) if (info.major() == requiredMajor) return info;
        JavaInfo best = null;
        for (JavaInfo info : all) {
            if (info.major() >= requiredMajor && (best == null || info.major() < best.major())) best = info;
        }
        if (best != null) return best;
        // No compatible runtime: use the newest available and let the launch report any mismatch.
        return all.get(all.size() - 1);
    }

    public Path ensureRuntime(String component, int major, Jobs.Job job) throws Exception {
        String selected = component;
        if (selected == null || selected.isBlank()) selected = componentFor(major);
        final String componentName = selected;
        Path target = dataDir.resolve("java").resolve(selected);
        Path javaExe = target.resolve("bin").resolve(Util.WINDOWS ? "java.exe" : "java");
        if (Files.isRegularFile(javaExe)) {
            JavaInfo info = inspect(javaExe);
            if (info != null && info.major() >= major) return javaExe;
        }
        if (job != null) job.progress(0.02, "Java 运行时", "下载官方 " + selected + "（" + major + "）");
        Path cache = dataDir.resolve("cache").resolve("java-runtime-index.json");
        if (!Files.exists(cache) || Files.size(cache) < 100) {
            String body = Http.getJson(RUNTIME_INDEX).body();
            Util.write(cache, body);
        }
        Map<String, Object> index = Json.parseObject(Util.read(cache, "{}"));
        String platform = platformKey();
        Map<String, Object> platformMap = Json.map(index.get(platform));
        List<Object> candidates = Json.list(platformMap.get(selected));
        if (candidates.isEmpty()) throw new IOException("Mojang 没有为当前平台提供 Java 运行时 " + selected);
        Map<String, Object> entry = Json.map(Json.map(candidates.get(0)).get("manifest"));
        String manifestUrl = Json.str(entry, "url", "");
        if (manifestUrl.isBlank()) throw new IOException("Java 运行时清单不可用");
        Map<String, Object> root = Json.parseObject(Http.getJson(manifestUrl).body());
        Map<String, Object> files = Json.map(root.get("files"));
        long totalBytes = 0;
        for (Object value : files.values()) {
            Map<String, Object> f = Json.map(value);
            if ("file".equals(Json.str(f, "type", ""))) {
                totalBytes += Json.longValue(Json.map(Json.map(f.get("downloads")).get("raw")), "size", 0);
            }
        }
        final long total = Math.max(1, totalBytes);
        long[] done = {0};
        int[] count = {0};
        int totalCount = files.size();
        for (Map.Entry<String, Object> e : files.entrySet()) {
            String relative = e.getKey().replace('\\', '/');
            Path out = target.resolve(relative).normalize();
            if (!out.startsWith(target.normalize())) continue;
            Map<String, Object> f = Json.map(e.getValue());
            String type = Json.str(f, "type", "file");
            if ("directory".equals(type)) {
                Files.createDirectories(out);
                continue;
            }
            if ("link".equals(type)) continue;
            Map<String, Object> raw = Json.map(Json.map(f.get("downloads")).get("raw"));
            String url = Json.str(raw, "url", "");
            if (url.isBlank()) continue;
            long size = Json.longValue(raw, "size", 0);
            String sha1 = Json.str(raw, "sha1", "");
            long[] last = {0};
            Http.download(url, out, sha1, n -> {
                if (job != null) {
                    long current = done[0] + Math.max(0, n - last[0]);
                    last[0] = Math.max(last[0], n);
                    job.progress(0.05 + 0.9 * Math.min(1.0, current / (double) total),
                            "Java 运行时", "解压 " + componentName + " · " + Util.bytes(current) + " / " + Util.bytes(total));
                }
            });
            done[0] += size;
            count[0]++;
            if (job != null && count[0] % 12 == 0) {
                job.log("Java " + selected + " · " + count[0] + "/" + totalCount + " 个文件");
            }
        }
        if (!Files.isRegularFile(javaExe)) throw new IOException("Java 运行时安装不完整：" + javaExe);
        if (job != null) {
            job.log("官方 Java " + major + " 已就绪");
            job.progress(0.97, "Java 运行时", "校验完成");
        }
        return javaExe;
    }

    public static String componentFor(int major) {
        if (major <= 8) return "jre-legacy";
        if (major <= 16) return "java-runtime-alpha";
        if (major <= 17) return "java-runtime-gamma";
        if (major <= 21) return "java-runtime-delta";
        return "java-runtime-epsilon";
    }

    private static String platformKey() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return arch.contains("aarch64") ? "windows-arm64" : "windows-x64";
        if (os.contains("mac")) return arch.contains("aarch64") ? "mac-os-arm64" : "mac-os";
        return arch.contains("aarch64") ? "linux" : "linux";
    }
}
