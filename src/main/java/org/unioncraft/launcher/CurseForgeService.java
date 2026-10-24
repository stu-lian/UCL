package org.unioncraft.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** CurseForge API integration using the launcher's built-in API key. */
public final class CurseForgeService {
    private static final String API = "https://api.curseforge.com/v1";

    private final Config config;
    private final MinecraftMeta meta;
    private final InstanceService instances;
    private final Path dataDir;

    public CurseForgeService(Config config, MinecraftMeta meta, InstanceService instances, Path dataDir) {
        this.config = config;
        this.meta = meta;
        this.instances = instances;
        this.dataDir = dataDir;
    }

    private Map<String, String> headers() {
        return Map.of("x-api-key", config.curseforgeKey(), "Accept", "application/json");
    }

    private Map<String, Object> get(String path, String query) throws Exception {
        String url = API + path + (query == null || query.isBlank() ? "" : "?" + query);
        Http.Response response = Http.get(url, headers());
        if (!response.ok()) {
            throw new IOException("CurseForge API " + response.status() + "："
                    + (response.status() == 403 ? "API Key 无效或请求受限" : trim(response.body())));
        }
        return Json.parseObject(response.body());
    }

    public Map<String, Object> search(String type, String query, int offset, int limit,
                                      String gameVersion, String loader, String sort) throws Exception {
        List<String> params = new ArrayList<>();
        params.add("gameId=432");
        params.add("classId=" + classId(type));
        params.add("searchFilter=" + Http.encode(query == null ? "" : query));
        params.add("sortOrder=desc");
        params.add("sortField=" + switch (sort == null ? "" : sort) {
            case "newest" -> "3";
            case "updated" -> "3";
            default -> "6";
        });
        params.add("pageSize=" + Math.max(1, Math.min(40, limit)));
        params.add("index=" + Math.max(0, offset));
        if (gameVersion != null && !gameVersion.isBlank() && !"all".equals(gameVersion)) {
            params.add("gameVersion=" + Http.encode(gameVersion));
        }
        int loaderType = loaderType(loader);
        if (loaderType > 0 && ("mod".equals(type) || "modpack".equals(type))) {
            params.add("modLoaderType=" + loaderType);
        }
        Map<String, Object> response = get("/mods/search", String.join("&", params));
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Object value : Json.list(response.get("data"))) {
            Map<String, Object> raw = Json.map(value);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", "curseforge");
            item.put("id", Json.str(raw, "id", ""));
            item.put("slug", Json.str(raw, "slug", ""));
            item.put("title", Json.str(raw, "name", "未命名"));
            item.put("description", Json.str(raw, "summary", ""));
            item.put("author", firstAuthor(raw));
            item.put("icon", Json.str(Json.map(raw.get("logo")), "thumbnailUrl", ""));
            String cover = "";
            List<Object> screenshots = Json.list(raw.get("screenshots"));
            if (!screenshots.isEmpty()) cover = Json.str(Json.map(screenshots.get(0)), "thumbnailUrl", "");
            item.put("cover", cover);
            item.put("downloads", Json.longValue(raw, "downloadCount", 0));
            item.put("follows", Json.longValue(raw, "thumbsUpCount", 0));
            item.put("categories", categories(raw));
            item.put("versions", new ArrayList<>());
            item.put("type", type);
            item.put("updated", Json.str(raw, "dateModified", ""));
            item.put("url", Json.str(Json.map(raw.get("links")), "websiteUrl",
                    "https://www.curseforge.com/minecraft/" + cursePath(type) + "/" + Json.str(raw, "slug", "")));
            item.put("featured", false);
            hits.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", hits);
        result.put("total", Json.longValue(Json.map(response.get("pagination")), "totalCount", hits.size()));
        result.put("offset", offset);
        result.put("source", "curseforge");
        return result;
    }

    public Map<String, Object> project(String id) throws Exception {
        Map<String, Object> response = get("/mods/" + Http.encode(id), "");
        Map<String, Object> raw = Json.map(response.get("data"));
        Map<String, Object> project = new LinkedHashMap<>(raw);
        project.put("source", "curseforge");
        project.put("author", firstAuthor(raw));
        project.put("icon", Json.str(Json.map(raw.get("logo")), "thumbnailUrl", ""));
        project.put("url", Json.str(Json.map(raw.get("links")), "websiteUrl", ""));
        return project;
    }

    public List<Map<String, Object>> files(String projectId, String gameVersion, String loader) throws Exception {
        List<String> params = new ArrayList<>();
        params.add("pageSize=50");
        if (gameVersion != null && !gameVersion.isBlank() && !"all".equals(gameVersion)) {
            params.add("gameVersion=" + Http.encode(gameVersion));
        }
        int loaderType = loaderType(loader);
        if (loaderType > 0) params.add("modLoaderType=" + loaderType);
        Map<String, Object> response = get("/mods/" + Http.encode(projectId) + "/files", String.join("&", params));
        List<Map<String, Object>> files = new ArrayList<>();
        for (Object value : Json.list(response.get("data"))) files.add(Json.map(value));
        files.sort(Comparator.comparing((Map<String, Object> f) -> Json.str(f, "fileDate", "")).reversed());
        return files;
    }

    public Map<String, Object> installContent(String type, String projectId, String fileId,
                                               String instanceId, Jobs.Job job) throws Exception {
        if ("modpack".equals(type)) return installModpack(projectId, fileId, job);
        Map<String, Object> file = pickFile(projectId, fileId, null, null, job);
        String fileName = Util.safeName(Json.str(file, "fileName", projectId + ".jar"));
        String url = downloadUrl(file);
        if (url.isBlank()) throw new IOException("CurseForge 没有返回可下载地址");
        Path dir = instances.contentDir(instanceId, type);
        Path target = dir.resolve(fileName);
        long size = Json.longValue(file, "fileLength", 0);
        job.progress(0.05, "下载", fileName);
        Http.download(url, target, null, n -> job.progress(
                0.05 + 0.9 * (size > 0 ? Math.min(1.0, n / (double) size) : 0.5), "下载", Util.bytes(n)));
        job.log("已安装 " + fileName + " → " + dir.getFileName());
        if ("world".equals(type) || "map".equals(type)) {
            Path worldDir = dir.resolve(Util.slug(Json.str(file, "displayName", fileName)));
            Util.unzip(target, worldDir, null);
            Files.deleteIfExists(target);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", target.toString());
        result.put("instance", instanceId);
        result.put("type", type);
        result.put("version", Json.str(file, "displayName", ""));
        return result;
    }

    public Map<String, Object> installModpack(String projectId, String fileId, Jobs.Job job) throws Exception {
        Map<String, Object> project = project(projectId);
        Map<String, Object> file = pickFile(projectId, fileId, null, null, job);
        String downloadUrl = downloadUrl(file);
        if (downloadUrl.isBlank()) throw new IOException("整合包没有可下载文件");
        String fileName = Util.safeName(Json.str(file, "fileName", "pack.zip"));
        Path archive = dataDir.resolve("cache").resolve("packs").resolve("cf-" + Util.safeName(fileName));
        long size = Json.longValue(file, "fileLength", 0);
        job.progress(0.02, "整合包", "下载 " + fileName);
        Http.download(downloadUrl, archive, null,
                n -> job.progress(0.02 + 0.2 * (size > 0 ? Math.min(1.0, n / (double) size) : 0.5),
                        "整合包", Util.bytes(n)));
        Path temp = dataDir.resolve("cache").resolve("packs").resolve("cf-unpack-" + Util.uuid());
        try {
            Files.createDirectories(temp);
            Util.unzip(archive, temp, null);
            Path manifestFile = temp.resolve("manifest.json");
            if (!Files.isRegularFile(manifestFile)) throw new IOException("不是有效的 CurseForge 整合包（缺少 manifest.json）");
            Map<String, Object> manifest = Json.parseObject(Util.read(manifestFile, "{}"));
            String gameVersion = Json.str(Json.map(manifest.get("minecraft")), "version", "");
            String loader = "vanilla";
            String loaderVersion = "";
            List<Object> modLoaders = Json.list(Json.map(manifest.get("minecraft")).get("modLoaders"));
            for (Object value : modLoaders) {
                Map<String, Object> entry = Json.map(value);
                String id = Json.str(entry, "id", "");
                int dash = id.indexOf('-');
                String prefix = dash > 0 ? id.substring(0, dash) : id;
                if (prefix.equalsIgnoreCase("fabric")) { loader = "fabric"; loaderVersion = id.substring(dash + 1); break; }
                if (prefix.equalsIgnoreCase("quilt")) { loader = "quilt"; loaderVersion = id.substring(dash + 1); break; }
                if (prefix.equalsIgnoreCase("forge")) { loader = "forge"; loaderVersion = id.substring(dash + 1); break; }
                if (prefix.equalsIgnoreCase("neoforge")) {
                    throw new IOException("这个整合包需要 NeoForge " + (dash > 0 ? id.substring(dash + 1) : "")
                            + "，当前版本暂未支持 NeoForge 自动安装；请选择 Forge 或 Fabric 整合包");
                }
            }
            if (gameVersion.isBlank()) throw new IOException("整合包没有声明 Minecraft 版本");
            String name = Json.str(project, "name", "整合包");
            Map<String, Object> instance = instances.createFromModpack(name, gameVersion, loader, loaderVersion,
                    stripHtml(Json.str(project, "summary", "")), "curseforge", projectId);
            String instanceId = Json.str(instance, "id", "");
            Path gameDir = instances.instanceDir(instanceId);
            job.log("实例已创建 · " + name + " · " + gameVersion + " / " + loader);

            String overridesName = Json.str(manifest, "overrides", "overrides");
            Path overrides = temp.resolve(overridesName);
            if (Files.isDirectory(overrides)) Util.copyRecursive(overrides, gameDir);

            List<Object> entries = Json.list(manifest.get("files"));
            List<MinecraftMeta.Dl> downloads = new ArrayList<>();
            int index = 0;
            for (Object value : entries) {
                index++;
                Map<String, Object> entry = Json.map(value);
                if (!Json.bool(entry, "required", true)) continue;
                String fileProjectId = Json.str(entry, "projectID", "");
                String fileFileId = Json.str(entry, "fileID", "");
                if (fileProjectId.isBlank() || fileFileId.isBlank()) continue;
                try {
                    Map<String, Object> fileResponse = get("/mods/" + Http.encode(fileProjectId) + "/files/"
                            + Http.encode(fileFileId), "");
                    Map<String, Object> modFile = Json.map(fileResponse.get("data"));
                    String url = downloadUrl(modFile);
                    String modFileName = Util.safeName(Json.str(modFile, "fileName", fileProjectId + "-" + fileFileId + ".jar"));
                    if (!url.isBlank()) {
                        downloads.add(new MinecraftMeta.Dl(url, gameDir.resolve("mods").resolve(modFileName), null,
                                Json.longValue(modFile, "fileLength", 0), modFileName));
                    }
                } catch (Exception e) {
                    job.log("跳过文件 " + fileProjectId + "/" + fileFileId + " · " + e.getMessage());
                }
                if (index % 25 == 0) {
                    job.progress(0.24 + 0.1 * Math.min(1.0, index / (double) Math.max(1, entries.size())),
                            "整合包内容", "解析文件 " + index + "/" + entries.size());
                }
            }
            job.progress(0.36, "整合包内容", downloads.size() + " 个模组");
            meta.downloadAll(downloads, job, "整合包内容", 0.36, 0.9);

            String icon = Json.str(Json.map(project.get("logo")), "thumbnailUrl", "");
            if (!icon.isBlank()) {
                try { Http.download(icon, gameDir.resolve("icon.png"), null, null); } catch (Exception ignored) { }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("instance", instanceId);
            result.put("name", name);
            result.put("version", gameVersion);
            result.put("loader", loader);
            result.put("files", downloads.size());
            return result;
        } finally {
            try { Util.deleteRecursive(temp); } catch (Exception ignored) { }
        }
    }

    private Map<String, Object> pickFile(String projectId, String fileId, String gameVersion,
                                          String loader, Jobs.Job job) throws Exception {
        if (fileId != null && !fileId.isBlank()) {
            Map<String, Object> response = get("/mods/" + Http.encode(projectId) + "/files/" + Http.encode(fileId), "");
            return Json.map(response.get("data"));
        }
        List<Map<String, Object>> files = files(projectId, gameVersion, loader);
        if (files.isEmpty()) files = files(projectId, null, null);
        if (files.isEmpty()) throw new IOException("项目没有可用文件");
        for (Map<String, Object> file : files) {
            if (Json.integer(file, "releaseType", 1) == 1) return file;
        }
        return files.get(0);
    }

    private String downloadUrl(Map<String, Object> file) {
        String url = Json.str(file, "downloadUrl", "");
        if (!url.isBlank()) return url;
        long id = Json.longValue(file, "id", 0);
        String name = Json.str(file, "fileName", "");
        if (id <= 0 || name.isBlank()) return "";
        return "https://edge.forgecdn.net/files/" + (id / 1000) + "/" + String.format("%03d", id % 1000) + "/"
                + Http.encode(name).replace("+", "%20");
    }

    private static int classId(String type) {
        if (type == null) return 6;
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "modpack", "modpacks" -> 4471;
            case "resourcepack", "resourcepacks" -> 12;
            case "world", "worlds", "map", "maps" -> 17;
            case "shader", "shaders" -> 6552;
            case "datapack", "datapacks" -> 6945;
            default -> 6;
        };
    }

    private static String cursePath(String type) {
        if (type == null) return "mc-mods";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "modpack", "modpacks" -> "modpacks";
            case "resourcepack", "resourcepacks" -> "texture-packs";
            case "world", "worlds", "map", "maps" -> "worlds";
            case "shader", "shaders" -> "shaders";
            case "datapack", "datapacks" -> "data-packs";
            default -> "mc-mods";
        };
    }

    private static int loaderType(String loader) {
        if (loader == null) return 0;
        return switch (loader.toLowerCase(Locale.ROOT)) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> 0;
        };
    }

    private static String firstAuthor(Map<String, Object> raw) {
        List<Object> authors = Json.list(raw.get("authors"));
        if (!authors.isEmpty()) return Json.str(Json.map(authors.get(0)), "name", "CurseForge");
        return "CurseForge";
    }

    private static List<Object> categories(Map<String, Object> raw) {
        List<Object> result = new ArrayList<>();
        for (Object value : Json.list(raw.get("categories"))) {
            result.add(Json.str(Json.map(value), "name", ""));
        }
        return result;
    }

    private static String stripHtml(String value) {
        return value == null ? "" : value.replaceAll("<[^>]+>", " ").replace("&nbsp;", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replaceAll("\\s+", " ").trim();
    }

    private static String trim(String value) {
        if (value == null) return "";
        return value.length() > 240 ? value.substring(0, 240) + "..." : value;
    }
}
