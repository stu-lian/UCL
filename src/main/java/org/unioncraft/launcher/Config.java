package org.unioncraft.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persistent launcher settings, stored in %APPDATA%\\UnionCraftLauncher\\config.json. */
public final class Config {
    public static final String DEFAULT_CURSEFORGE_KEY =
            "$2a$10$wcxb6g8qbu4lmWUikOupleOiIr67dPW3xELAPC091oKJiDgsVYzx6";

    private final Path file;
    private final Map<String, Object> data = new LinkedHashMap<>();

    public Config(Path dataDir) {
        this.file = dataDir.resolve("config.json");
        Map<String, Object> loaded = new LinkedHashMap<>();
        if (Files.exists(file)) {
            try {
                loaded = Json.parseObject(Util.read(file, "{}"));
            } catch (Exception ignored) {
                // A malformed config should never prevent the launcher from starting.
            }
        }
        data.putAll(loaded);
        defaults();
        saveQuietly();
    }

    private void defaults() {
        putIfAbsent("minecraftDir", Util.dataDir().resolve("minecraft").toString());
        putIfAbsent("memoryMb", 4096);
        putIfAbsent("minMemoryMb", 1024);
        putIfAbsent("javaPath", "");
        putIfAbsent("curseforgeKey", DEFAULT_CURSEFORGE_KEY);
        putIfAbsent("closeOnLaunch", false);
        putIfAbsent("highPerformanceGpu", true);
        putIfAbsent("showSnapshots", false);
        putIfAbsent("downloadConcurrency", 64);
        putIfAbsent("downloadSource", "auto");
        putIfAbsent("language", "zh-CN");
        putIfAbsent("accent", "blue");
        putIfAbsent("theme", "light");
        putIfAbsent("serverPort", 52114);
        putIfAbsent("reduceMotion", false);
        putIfAbsent("lastInstance", "");
        putIfAbsent("windowWidth", 1080);
        putIfAbsent("windowHeight", 720);
        putIfAbsent("acceptedTerms", false);
    }

    private void putIfAbsent(String key, Object value) {
        if (!data.containsKey(key) || data.get(key) == null) data.put(key, value);
    }

    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> copy = new LinkedHashMap<>(data);
        String key = String.valueOf(copy.getOrDefault("curseforgeKey", ""));
        copy.put("curseforgeKey", key);
        copy.put("curseforgeKeySet", !key.isBlank());
        return copy;
    }

    public synchronized String getString(String key, String fallback) {
        return Json.str(data.get(key), fallback);
    }

    public synchronized int getInt(String key, int fallback) {
        return Json.integer(data.get(key), fallback);
    }

    public synchronized boolean getBool(String key, boolean fallback) {
        return Json.bool(data.get(key), fallback);
    }

    public synchronized void set(String key, Object value) {
        data.put(key, value);
        saveQuietly();
    }

    public synchronized void setAll(Map<String, Object> values) {
        data.putAll(values);
        saveQuietly();
    }

    public boolean portable() {
        return "true".equalsIgnoreCase(System.getProperty("ucl.portable", ""))
                || (System.getenv("UCL_GAME_DIR") != null && !System.getenv("UCL_GAME_DIR").isBlank());
    }

    public synchronized Path minecraftDir() {
        String portable = System.getenv("UCL_GAME_DIR");
        if (portable != null && !portable.isBlank()) return Path.of(portable).toAbsolutePath();
        if ("true".equalsIgnoreCase(System.getProperty("ucl.portable", ""))) {
            return Util.appDir().resolve("minecraft").toAbsolutePath();
        }
        return Path.of(getString("minecraftDir", Util.dataDir().resolve("minecraft").toString())).toAbsolutePath();
    }

    public synchronized Path instancesDir() {
        return minecraftDir().resolve("instances");
    }

    public synchronized String curseforgeKey() {
        return getString("curseforgeKey", DEFAULT_CURSEFORGE_KEY);
    }

    public synchronized void save() throws IOException {
        Map<String, Object> copy;
        synchronized (this) {
            copy = new LinkedHashMap<>(data);
        }
        Util.write(file, Json.write(copy));
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException ignored) { }
    }
}
