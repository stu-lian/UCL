package org.unioncraft.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Manages registrations and isolated game directories. An instance may either use
 * the launcher's default Minecraft root or reference an existing client directory
 * added by the user.
 */
public final class InstanceService {
    private final Config config;

    public InstanceService(Config config) {
        this.config = config;
    }

    public Path instancesDir() throws IOException {
        Path dir = config.instancesDir();
        Files.createDirectories(dir);
        return dir;
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            Path base = instancesDir();
            migrateImportedLayout(base);
            try (var stream = Files.list(base)) {
                for (Path dir : stream.toList()) {
                    if (!Files.isDirectory(dir)) continue;
                    Map<String, Object> instance = read(dir);
                    if (instance != null) result.add(instance);
                }
            }
        } catch (IOException ignored) { }
        result.sort(Comparator.comparing((Map<String, Object> m) -> Json.longValue(m, "lastPlayed", 0)).reversed());
        return result;
    }

    public synchronized Map<String, Object> get(String id) throws IOException {
        Path dir = instanceDir(id);
        Map<String, Object> instance = read(dir);
        if (instance == null) throw new IOException("实例不存在：" + id);
        return instance;
    }

    public synchronized Map<String, Object> create(String name, String version, String loader,
                                                    String loaderVersion, String description) throws IOException {
        String cleanName = Util.safeName(name);
        if (cleanName.isBlank()) cleanName = "新的世界";
        if (version == null || version.isBlank()) throw new IOException("请选择 Minecraft 版本");
        String id = uniqueId(cleanName);
        Path dir = instancesDir().resolve(id);
        Files.createDirectories(dir);
        createGameFolders(dir);
        Map<String, Object> instance = baseInstance(id, cleanName, version, loader,
                loaderVersion, description);
        instance.put("root", "");
        instance.put("gameDir", dir.toString());
        instance.put("isolated", true);
        write(dir, instance);
        config.set("lastInstance", id);
        return enrich(instance, dir);
    }

    public synchronized Map<String, Object> importExternal(String name, String rootPath, String version,
                                                           String loader, boolean isolated) throws IOException {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("客户端目录不存在：" + root);
        if (!Files.isDirectory(root.resolve("versions"))) {
            throw new IOException("目录中没有 versions 文件夹，可能不是 Minecraft 客户端目录");
        }
        if (version == null || version.isBlank()) throw new IOException("请选择客户端版本");
        String cleanName = Util.safeName(name);
        if (cleanName.isBlank()) cleanName = version;
        String id = uniqueId(cleanName);
        Path registry = instancesDir().resolve(id);
        Files.createDirectories(registry);
        Path versionDir = findVersionDir(root, version);
        Path gameDir;
        if (isolated) {
            if (versionDir == null) throw new IOException("找不到版本文件夹：" + version);
            gameDir = versionDir;
            // PCL2-style isolation keeps game data beside the version JSON/jar.
            createGameFolders(gameDir);
        } else {
            gameDir = root;
        }
        // Imported clients are complete version folders; never re-run a loader installer on them.
        Map<String, Object> instance = baseInstance(id, cleanName, version, "custom", "", "");
        instance.put("detectedLoader", loader == null || loader.isBlank() ? "vanilla" : loader);
        instance.put("root", root.toString());
        instance.put("gameDir", gameDir.toString());
        instance.put("gameDirMode", isolated ? "version" : "root");
        instance.put("isolated", isolated);
        instance.put("source", "imported");
        write(registry, instance);
        config.set("lastInstance", id);
        return enrich(instance, registry);
    }

    private Map<String, Object> baseInstance(String id, String name, String version, String loader,
                                             String loaderVersion, String description) {
        Map<String, Object> instance = new LinkedHashMap<>();
        instance.put("id", id);
        instance.put("name", name);
        instance.put("version", version);
        instance.put("loader", loader == null || loader.isBlank() ? "vanilla" : loader);
        instance.put("loaderVersion", loaderVersion == null ? "" : loaderVersion);
        instance.put("description", description == null ? "" : description);
        instance.put("createdAt", System.currentTimeMillis());
        instance.put("lastPlayed", 0);
        instance.put("icon", "");
        instance.put("source", "local");
        instance.put("modpack", "");
        instance.put("javaPath", "");
        instance.put("memoryMb", 0);
        return instance;
    }

    private void createGameFolders(Path dir) throws IOException {
        for (String folder : new String[]{"mods", "resourcepacks", "shaderpacks", "datapacks", "saves", "config", "logs"}) {
            Files.createDirectories(dir.resolve(folder));
        }
    }

    private Path findVersionDir(Path root, String version) {
        Path versions = root.resolve("versions");
        Path direct = versions.resolve(version).normalize();
        if (direct.startsWith(versions.normalize()) && Files.isRegularFile(direct.resolve(version + ".json"))) {
            return direct;
        }
        if (Files.isDirectory(versions)) {
            try (var stream = Files.list(versions)) {
                for (Path dir : stream.toList()) {
                    if (!Files.isDirectory(dir)) continue;
                    Path file = dir.resolve(dir.getFileName().toString() + ".json");
                    if (!Files.isRegularFile(file)) continue;
                    try {
                        String id = Json.str(Json.parseObject(Util.read(file, "{}")), "id", "");
                        if (id.equals(version)) return dir;
                    } catch (Exception ignored) { }
                }
            } catch (IOException ignored) { }
        }
        return null;
    }

    private String uniqueId(String name) throws IOException {
        String id = Util.slug(name);
        if (id.isBlank() || id.equals("instance")) {
            id = "instance-" + Integer.toHexString(name == null ? 0 : name.hashCode());
        }
        if (id.length() > 40) id = id.substring(0, 40);
        String candidate = id;
        int suffix = 1;
        while (Files.exists(instancesDir().resolve(candidate))) candidate = id + "-" + (++suffix);
        return candidate;
    }

    public synchronized Map<String, Object> createFromModpack(String name, String version, String loader,
                                                               String loaderVersion, String description,
                                                               String source, String packId) throws IOException {
        Map<String, Object> instance = create(name, version, loader, loaderVersion, description);
        String id = Json.str(instance, "id", "");
        Path dir = instanceDir(id);
        Map<String, Object> raw = readRaw(dir);
        if (raw != null) {
            raw.put("source", source == null ? "modrinth" : source);
            raw.put("modpack", packId == null ? "" : packId);
            write(dir, raw);
        }
        return get(id);
    }

    public synchronized Map<String, Object> update(String id, Map<String, Object> changes) throws IOException {
        Path dir = instanceDir(id);
        Map<String, Object> instance = readRaw(dir);
        if (instance == null) throw new IOException("实例不存在：" + id);
        for (String key : new String[]{"name", "description", "icon", "javaPath", "memoryMb"}) {
            if (changes.containsKey(key)) instance.put(key, changes.get(key));
        }
        if (changes.containsKey("lastPlayed")) instance.put("lastPlayed", changes.get("lastPlayed"));
        write(dir, instance);
        return enrich(instance, dir);
    }

    public synchronized void delete(String id, boolean deleteFiles) throws IOException {
        Path registry = instanceDir(id);
        Map<String, Object> raw = readRaw(registry);
        if (raw == null) throw new IOException("实例不存在：" + id);
        Path gameDir = gameDir(raw, registry);
        boolean isolated = Json.bool(raw, "isolated", true);
        if (deleteFiles) {
            deleteWithRetry(registry);
            boolean versionDir = "version".equals(Json.str(raw, "gameDirMode", ""));
            boolean ownedGameDir = !gameDir.equals(registry) && isolated && !versionDir
                    && Files.isRegularFile(gameDir.resolve(".ucl-instance"));
            if (ownedGameDir) deleteWithRetry(gameDir);
        } else {
            Path backup = instancesDir().resolve(".trash-" + id + "-" + System.currentTimeMillis());
            Files.move(registry, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        if (id.equals(config.getString("lastInstance", ""))) config.set("lastInstance", "");
    }

    private void deleteWithRetry(Path dir) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            try {
                Util.deleteRecursive(dir);
                return;
            } catch (IOException e) {
                last = e;
                try { Thread.sleep(500); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        if (last != null) throw last;
    }

    public Path instanceDir(String id) throws IOException {
        Path base = instancesDir().normalize();
        Path dir = base.resolve(Util.safeName(id)).normalize();
        if (!dir.startsWith(base)) throw new IOException("非法实例路径");
        return dir;
    }

    public Path gameDir(String id) throws IOException {
        Path registry = instanceDir(id);
        Map<String, Object> raw = readRaw(registry);
        if (raw == null) throw new IOException("实例不存在：" + id);
        return gameDir(raw, registry);
    }

    public Path gameDir(Map<String, Object> instance, Path registry) {
        String configured = Json.str(instance, "gameDir", "");
        if (!configured.isBlank()) {
            try { return Path.of(configured).toAbsolutePath().normalize(); } catch (Exception ignored) { }
        }
        String root = Json.str(instance, "root", "");
        if (root.isBlank()) return registry;
        Path base = Path.of(root).toAbsolutePath().normalize();
        if ("imported".equals(Json.str(instance, "source", "")) && Json.bool(instance, "isolated", false)) {
            String version = Json.str(instance, "version", "");
            if (!version.isBlank()) {
                Path found = findVersionDir(base, version);
                if (found != null) return found;
                return base.resolve("versions").resolve(version);
            }
        }
        return Json.bool(instance, "isolated", false) ? base.resolve("instances").resolve(Json.str(instance, "id", "instance")) : base;
    }

    public Path rootDir(Map<String, Object> instance) {
        String root = Json.str(instance, "root", "");
        if (root.isBlank()) return config.minecraftDir();
        return Path.of(root).toAbsolutePath().normalize();
    }

    public Path contentDir(String instanceId, String type) throws IOException {
        Path dir = gameDir(instanceId);
        String folder = switch (type) {
            case "resourcepack", "resourcepacks" -> "resourcepacks";
            case "shader", "shaders", "shaderpack" -> "shaderpacks";
            case "datapack", "datapacks" -> "datapacks";
            case "world", "map", "worlds" -> "saves";
            default -> "mods";
        };
        Path target = dir.resolve(folder);
        Files.createDirectories(target);
        return target;
    }

    public List<Map<String, Object>> content(String instanceId, String type) throws IOException {
        Path dir = contentDir(instanceId, type);
        List<Map<String, Object>> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                if (!Files.isRegularFile(file)) continue;
                String name = file.getFileName().toString();
                boolean enabled = !name.endsWith(".disabled");
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", name);
                item.put("displayName", enabled ? name : name.substring(0, name.length() - 9));
                item.put("size", Files.size(file));
                item.put("enabled", enabled);
                item.put("modified", Files.getLastModifiedTime(file).toMillis());
                files.add(item);
            }
        }
        files.sort(Comparator.comparing((Map<String, Object> m) -> Json.str(m, "name", "").toLowerCase(Locale.ROOT)));
        return files;
    }

    public void toggleContent(String instanceId, String type, String name) throws IOException {
        Path dir = contentDir(instanceId, type);
        Path file = dir.resolve(Util.safeName(name)).normalize();
        if (!file.startsWith(dir.normalize()) || !Files.isRegularFile(file)) throw new IOException("文件不存在");
        String fileName = file.getFileName().toString();
        Path target;
        if (fileName.endsWith(".disabled")) {
            target = file.resolveSibling(fileName.substring(0, fileName.length() - 9));
        } else {
            target = file.resolveSibling(fileName + ".disabled");
        }
        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void removeContent(String instanceId, String type, String name) throws IOException {
        Path dir = contentDir(instanceId, type);
        Path file = dir.resolve(Util.safeName(name)).normalize();
        if (!file.startsWith(dir.normalize())) throw new IOException("非法路径");
        Files.deleteIfExists(file);
    }

    public Map<String, Object> read(Path dir) {
        Map<String, Object> raw = readRaw(dir);
        if (raw == null) return null;
        migrateLegacyImport(raw, dir);
        mergeLegacyData(raw, dir);
        return enrich(raw, dir);
    }

    /** Copies user-created files out of the old auto-isolated folder without overwriting. */
    private void mergeLegacyData(Map<String, Object> instance, Path registry) {
        if (!"imported".equals(Json.str(instance, "source", ""))) return;
        if (Json.bool(instance, "legacyMerged", false)) return;
        String rootValue = Json.str(instance, "root", "");
        if (rootValue.isBlank()) return;
        Path root;
        try { root = Path.of(rootValue).toAbsolutePath().normalize(); } catch (Exception e) { return; }
        Path legacy = root.resolve("instances").resolve(root.getFileName() == null ? "" : root.getFileName().toString())
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(legacy) || !Files.isDirectory(root)) return;
        for (String folder : new String[]{"saves", "mods", "resourcepacks", "shaderpacks", "datapacks", "config"}) {
            copyMissing(legacy.resolve(folder), root.resolve(folder));
        }
        for (String file : new String[]{"servers.dat", "options.txt"}) {
            Path source = legacy.resolve(file);
            Path target = root.resolve(file);
            try {
                if (Files.isRegularFile(source) && !Files.exists(target)) Files.copy(source, target);
            } catch (IOException ignored) { }
        }
        instance.put("legacyMerged", true);
        try { write(registry, instance); } catch (IOException ignored) { }
    }

    private void copyMissing(Path source, Path target) {
        if (!Files.isDirectory(source)) return;
        try (var walk = Files.walk(source)) {
            for (Path path : walk.toList()) {
                Path relative = source.relativize(path);
                Path destination = target.resolve(relative);
                try {
                    if (Files.isDirectory(path)) Files.createDirectories(destination);
                    else if (Files.isRegularFile(path) && !Files.exists(destination)) Files.copy(path, destination);
                } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    /**
     * Imported clients now live in their own {@code versions/<version>} folder. This migration
     * moves old shared/instances layouts onto that folder and copies user game data once.
     */
    private void migrateImportedLayout(Path base) {
        if (config.getBool("instanceLayoutV3", false)) return;
        List<Path> registries = new ArrayList<>();
        try (var stream = Files.list(base)) {
            for (Path dir : stream.toList()) if (Files.isDirectory(dir)) registries.add(dir);
        } catch (IOException ignored) { }
        Path rootDataOwner = null;
        long latest = Long.MIN_VALUE;
        for (Path registry : registries) {
            Map<String, Object> instance = readRaw(registry);
            if (instance == null || !"imported".equals(Json.str(instance, "source", ""))) continue;
            String rootValue = Json.str(instance, "root", "");
            if (rootValue.isBlank()) continue;
            Path root;
            Path old;
            try {
                root = Path.of(rootValue).toAbsolutePath().normalize();
                old = gameDir(instance, registry);
            } catch (Exception e) { continue; }
            long played = Json.longValue(instance, "lastPlayed", 0);
            if (old.equals(root) && played >= latest) {
                latest = played;
                rootDataOwner = registry;
            }
        }
        for (Path registry : registries) {
            Map<String, Object> instance = readRaw(registry);
            if (instance == null || !"imported".equals(Json.str(instance, "source", ""))) continue;
            String rootValue = Json.str(instance, "root", "");
            String version = Json.str(instance, "version", "");
            if (rootValue.isBlank() || version.isBlank()) continue;
            Path root;
            try { root = Path.of(rootValue).toAbsolutePath().normalize(); } catch (Exception e) { continue; }
            Path versionDir = findVersionDir(root, version);
            if (versionDir == null) continue;
            Path old;
            try { old = gameDir(instance, registry); } catch (Exception e) { continue; }
            if (!old.equals(versionDir)) {
                Path source = null;
                if (!old.equals(root)) {
                    Path instances = root.resolve("instances").toAbsolutePath().normalize();
                    if (old.startsWith(instances)) source = old;
                } else if (registry.equals(rootDataOwner)) {
                    source = root;
                }
                if (source != null && !source.equals(versionDir)) copyGameData(source, versionDir);
            }
            instance.put("gameDir", versionDir.toString());
            instance.put("gameDirMode", "version");
            instance.put("isolated", true);
            instance.put("legacyMerged", true);
            try { write(registry, instance); } catch (IOException ignored) { }
        }
        config.set("instanceLayoutV3", true);
    }

    private void copyGameData(Path source, Path target) {
        for (String folder : new String[]{"saves", "mods", "config", "defaultconfigs", "resourcepacks",
                "shaderpacks", "datapacks", "scripts", "tacz", "xaero", "screenshots", "schematics",
                "server-resource-packs", "data"}) {
            copyMissing(source.resolve(folder), target.resolve(folder));
        }
        for (String file : new String[]{"options.txt", "servers.dat"}) {
            Path from = source.resolve(file);
            Path to = target.resolve(file);
            try {
                if (Files.isRegularFile(from) && !Files.exists(to)) Files.copy(from, to);
            } catch (IOException ignored) { }
        }
    }

    /**
     * Old imported clients were isolated under {@code root/instances/<root-folder>}, which
     * produced paths such as {@code .minecraft/instances/.minecraft}. Imported clients should
     * use the existing .minecraft directly unless the user explicitly opted into isolation.
     */
    private void migrateLegacyImport(Map<String, Object> instance, Path registry) {
        if (!"imported".equals(Json.str(instance, "source", ""))) return;
        if (!Json.bool(instance, "isolated", false)) return;
        String rootValue = Json.str(instance, "root", "");
        if (rootValue.isBlank()) return;
        Path root;
        try { root = Path.of(rootValue).toAbsolutePath().normalize(); } catch (Exception e) { return; }
        Path legacy = root.resolve("instances").resolve(root.getFileName() == null ? "" : root.getFileName().toString())
                .toAbsolutePath().normalize();
        String configured = Json.str(instance, "gameDir", "");
        Path gameDir;
        try { gameDir = configured.isBlank() ? legacy : Path.of(configured).toAbsolutePath().normalize(); }
        catch (Exception e) { return; }
        if (!gameDir.equals(legacy)) return;
        // This exact layout is the legacy auto-isolation of an imported client. It is safe to
        // point at the existing .minecraft again; the old generated folder is left untouched.
        instance.put("gameDir", root.toString());
        instance.put("isolated", false);
        try { write(registry, instance); } catch (IOException ignored) { }
    }

    public Map<String, Object> readRaw(Path dir) {
        Path file = dir.resolve("instance.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            Map<String, Object> instance = Json.parseObject(Util.read(file, "{}"));
            if (Json.str(instance, "id", "").isBlank()) instance.put("id", dir.getFileName().toString());
            return instance;
        } catch (Exception e) {
            return null;
        }
    }

    private void write(Path dir, Map<String, Object> instance) throws IOException {
        Util.write(dir.resolve("instance.json"), Json.write(instance));
    }

    private Map<String, Object> enrich(Map<String, Object> raw, Path registry) {
        Map<String, Object> instance = new LinkedHashMap<>(raw);
        Path gameDir = gameDir(raw, registry);
        if ("imported".equals(Json.str(raw, "source", ""))) {
            String name = Json.str(raw, "name", "");
            Path root = rootDir(raw);
            String folder = root.getFileName() == null ? "" : root.getFileName().toString();
            if (name.isBlank() || name.equals(".minecraft") || name.equalsIgnoreCase(folder)) {
                String version = Json.str(raw, "version", "");
                if (!version.isBlank()) instance.put("name", version);
            }
            if (Json.str(raw, "detectedLoader", "").isBlank()) {
                instance.put("detectedLoader", Json.str(raw, "loader", "vanilla"));
            }
        }
        instance.put("loaderLabel", loaderLabel(instance));
        instance.put("dir", registry.toString());
        instance.put("gameDir", gameDir.toString());
        instance.put("root", rootDir(raw).toString());
        instance.put("isolated", Json.bool(raw, "isolated", true));
        instance.put("modsCount", count(gameDir.resolve("mods"), ".jar"));
        instance.put("resourcePacksCount", count(gameDir.resolve("resourcepacks"), ".zip"));
        instance.put("shaderPacksCount", count(gameDir.resolve("shaderpacks"), ".zip"));
        instance.put("worldsCount", directories(gameDir.resolve("saves")));
        instance.put("lastPlayedLabel", Json.longValue(raw, "lastPlayed", 0) == 0 ? "从未启动"
                : new java.text.SimpleDateFormat("MM-dd HH:mm").format(new java.util.Date(Json.longValue(raw, "lastPlayed", 0))));
        Path icon = registry.resolve("icon.png");
        if (Files.isRegularFile(icon)) instance.put("icon", "local");
        return instance;
    }

    private static String loaderLabel(Map<String, Object> instance) {
        String loader = Json.str(instance, "detectedLoader", Json.str(instance, "loader", "vanilla"));
        return switch (loader.toLowerCase()) {
            case "fabric" -> "Fabric";
            case "quilt" -> "Quilt";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            case "custom" -> "本地版本";
            default -> "Vanilla";
        };
    }

    private int count(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) return 0;
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private int directories(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (var stream = Files.list(dir)) {
            return (int) stream.filter(Files::isDirectory).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
