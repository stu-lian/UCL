package org.unioncraft.launcher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.LongConsumer;

/**
 * Download channel selection. "bmclapi" mirrors Mojang / Forge files through
 * BMCLAPI for networks where the official hosts are slow or unreachable.
 */
public final class Mirror {
    public static final String BMCLAPI = "https://bmclapi2.bangbang93.com";
    public static final String OFFICIAL_MANIFEST =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";

    private final Config config;

    public Mirror(Config config) {
        this.config = config;
    }

    public String source() {
        String value = config.getString("downloadSource", "auto").toLowerCase(Locale.ROOT);
        if (!value.equals("official") && !value.equals("bmclapi") && !value.equals("auto")) value = "auto";
        return value;
    }

    public boolean useBmcl() {
        return !"official".equals(source());
    }

    public String label() {
        return switch (source()) {
            case "official" -> "Mojang 官方源";
            case "bmclapi" -> "BMCLAPI 镜像";
            default -> "自动（BMCLAPI 优先）";
        };
    }

    public String manifestUrl() {
        return useBmcl() ? BMCLAPI + "/mc/game/version_manifest_v2.json" : OFFICIAL_MANIFEST;
    }

    public String versionJsonUrl(String id, String officialUrl) {
        return useBmcl() ? BMCLAPI + "/version/" + Http.encode(id) + "/json" : officialUrl;
    }

    public String clientUrl(String id, String officialUrl) {
        return useBmcl() ? BMCLAPI + "/version/" + Http.encode(id) + "/client" : officialUrl;
    }

    public String forgeListUrl(String minecraftVersion) {
        return useBmcl()
                ? BMCLAPI + "/forge/minecraft/" + Http.encode(minecraftVersion)
                : "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
    }

    public String forgeInstallerUrl(String mcVersion, String forgeVersion) {
        String path = "net/minecraftforge/forge/" + mcVersion + "-" + forgeVersion
                + "/forge-" + mcVersion + "-" + forgeVersion + "-installer.jar";
        return useBmcl()
                ? BMCLAPI + "/maven/" + path
                : "https://maven.minecraftforge.net/" + path;
    }

    public String rewrite(String url) {
        if (!useBmcl() || url == null || url.isBlank()) return url;
        String clean = url;
        if (clean.startsWith("https://piston-meta.mojang.com/")) {
            return BMCLAPI + clean.substring("https://piston-meta.mojang.com".length());
        }
        if (clean.startsWith("https://piston-data.mojang.com/")) {
            return BMCLAPI + clean.substring("https://piston-data.mojang.com".length());
        }
        if (clean.startsWith("https://launchermeta.mojang.com/")) {
            return BMCLAPI + clean.substring("https://launchermeta.mojang.com".length());
        }
        if (clean.startsWith("https://launcher.mojang.com/")) {
            return BMCLAPI + clean.substring("https://launcher.mojang.com".length());
        }
        if (clean.startsWith("https://libraries.minecraft.net/")) {
            return BMCLAPI + "/maven/" + clean.substring("https://libraries.minecraft.net/".length());
        }
        if (clean.startsWith("https://resources.download.minecraft.net/")) {
            return BMCLAPI + "/assets/" + clean.substring("https://resources.download.minecraft.net/".length());
        }
        if (clean.startsWith("https://files.minecraftforge.net/maven/")) {
            return BMCLAPI + "/maven/" + clean.substring("https://files.minecraftforge.net/maven/".length());
        }
        if (clean.startsWith("https://maven.minecraftforge.net/")) {
            return BMCLAPI + "/maven/" + clean.substring("https://maven.minecraftforge.net/".length());
        }
        return clean;
    }

    public String official(String url) {
        return url == null ? "" : url;
    }

    public void download(String url, Path target, String sha1, LongConsumer progress)
            throws IOException, InterruptedException {
        download(url, target, sha1, progress, progress);
    }

    public void download(String url, Path target, String sha1, LongConsumer progress, LongConsumer networkProgress)
            throws IOException, InterruptedException {
        String primary = rewrite(url);
        try {
            // BMCLAPI serves many objects as 302 redirects to its CDN. Following the redirect
            // is required; if that CDN is unreachable we still fall back to the official host.
            Http.download(primary, target, sha1, progress, networkProgress, true);
        } catch (IOException | InterruptedException first) {
            // Some BMCL objects are redirected to mirrors that occasionally reject the JVM's
            // TLS handshake; the official host is a useful last resort.
            if (!"official".equals(source()) && !primary.equals(url)) {
                Http.download(url, target, sha1, progress, networkProgress, true);
            } else {
                throw first;
            }
        }
    }

    public String getJsonBody(String url) throws IOException, InterruptedException {
        String primary = rewrite(url);
        try {
            return Http.getJson(primary).body();
        } catch (IOException | InterruptedException first) {
            if (!"official".equals(source()) && !primary.equals(url)) {
                return Http.getJson(url).body();
            }
            throw first;
        }
    }

    public String forgeVersion(String minecraftVersion) throws Exception {
        if (!useBmcl()) {
            java.util.Map<String, Object> promotions = Json.parseObject(getJsonBody(
                    "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"));
            java.util.Map<String, Object> promos = Json.map(promotions.get("promos"));
            String version = Json.str(promos, minecraftVersion + "-recommended", "");
            if (version.isBlank()) version = Json.str(promos, minecraftVersion + "-latest", "");
            if (version.isBlank()) throw new IOException("Forge 没有适用于 Minecraft " + minecraftVersion + " 的构建");
            return version;
        }
        Object parsed = Json.parse(getJsonBody(BMCLAPI + "/forge/minecraft/" + Http.encode(minecraftVersion)));
        java.util.List<Object> list = Json.list(parsed);
        String best = "";
        for (Object value : list) {
            String version = Json.str(Json.map(value), "version", "");
            if (compareVersions(version, best) > 0) best = version;
        }
        if (best.isBlank()) throw new IOException("BMCLAPI 没有适用于 Minecraft " + minecraftVersion + " 的 Forge 构建");
        return best;
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("[^0-9]");
        String[] b = right.split("[^0-9]");
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length && !a[i].isBlank() ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && !b[i].isBlank() ? Integer.parseInt(b[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return left.compareTo(right);
    }
}
