package org.unioncraft.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class Util {
    private Util() {}

    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    public static final String VERSION = "0.2.0";
    public static final String APP_NAME = "Union Craft Launcher";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneOffset.systemDefault());

    /** Directory containing the launcher exe (or the project when run from source). */
    public static Path appDir() {
        try {
            Path code = Path.of(Util.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(code) && code.getFileName().toString().endsWith(".jar")) {
                Path app = code.getParent();
                if (app != null && "app".equals(app.getFileName().toString()) && app.getParent() != null) {
                    return app.getParent().toAbsolutePath().normalize();
                }
                if (app != null) return app.toAbsolutePath().normalize();
            }
        } catch (Exception ignored) { }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    public static Path dataDir() {
        String override = System.getenv("UCL_DATA_DIR");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath();
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) return Path.of(appData, "UnionCraftLauncher");
        return Path.of(System.getProperty("user.home"), ".unioncraftlauncher");
    }

    public static String nowTime() { return TIME.format(Instant.now()); }

    public static String uuid() { return UUID.randomUUID().toString().replace("-", ""); }

    public static String sha1(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha1(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            return hex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static String read(Path path, String fallback) {
        try {
            return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : fallback;
        } catch (IOException e) {
            return fallback;
        }
    }

    public static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void copyRecursive(Path source, Path target) throws IOException {
        if (Files.isDirectory(source)) {
            Files.createDirectories(target);
            try (var stream = Files.list(source)) {
                for (Path child : stream.toList()) copyRecursive(child, target.resolve(child.getFileName().toString()));
            }
        } else {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Safely extracts a zip, stripping a leading directory when requested. */
    public static List<String> unzip(Path zip, Path destination, String stripPrefix) throws IOException {
        List<String> extracted = new ArrayList<>();
        Files.createDirectories(destination);
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (stripPrefix != null && !stripPrefix.isBlank()) {
                    if (!name.startsWith(stripPrefix)) continue;
                    name = name.substring(stripPrefix.length());
                }
                if (name.isBlank()) continue;
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination.normalize())) {
                    throw new IOException("Unsafe zip entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                        in.transferTo(out);
                    }
                    extracted.add(name);
                }
                in.closeEntry();
            }
        }
        return extracted;
    }

    public static String bytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        for (String unit : units) {
            value /= 1024.0;
            if (value < 1024) {
                return String.format(Locale.ROOT, value < 10 ? "%.1f %s" : "%.0f %s", value, unit);
            }
        }
        return String.format(Locale.ROOT, "%.1f PB", value / 1024.0);
    }

    public static String compactNumber(long value) {
        if (value < 1000) return String.valueOf(value);
        if (value < 1_000_000) return String.format(Locale.ROOT, "%.1fK", value / 1000.0);
        if (value < 1_000_000_000) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000.0);
        return String.format(Locale.ROOT, "%.1fB", value / 1_000_000_000.0);
    }

    public static String safeName(String value) {
        if (value == null || value.isBlank()) return "untitled";
        return value.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "-").replaceAll("\\s+", " ").trim();
    }

    public static String slug(String value) {
        String slug = safeName(value).toLowerCase(Locale.ROOT).replace(' ', '-');
        return slug.replaceAll("[^a-z0-9._-]", "").replaceAll("-+", "-");
    }

    public static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return result;
        for (String part : rawQuery.split("&")) {
            int eq = part.indexOf('=');
            String key = eq >= 0 ? part.substring(0, eq) : part;
            String value = eq >= 0 ? part.substring(eq + 1) : "";
            result.put(urlDecode(key), urlDecode(value));
        }
        return result;
    }

    public static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return value;
        }
    }

    public static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static void openFolder(Path path) {
        try {
            Files.createDirectories(path);
            if (WINDOWS) {
                new ProcessBuilder("explorer.exe", path.toAbsolutePath().toString()).start();
            } else if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
                new ProcessBuilder("open", path.toAbsolutePath().toString()).start();
            } else {
                new ProcessBuilder("xdg-open", path.toAbsolutePath().toString()).start();
            }
        } catch (IOException ignored) {
            // Opening a folder is a convenience; a failure is surfaced by the UI path instead.
        }
    }
}
