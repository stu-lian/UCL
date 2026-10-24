package org.unioncraft.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Modrinth v2 integration: search, project metadata and one-click installation. */
public final class ModrinthService {
    private static final String API = "https://api.modrinth.com/v2";

    private final MinecraftMeta meta;
    private final InstanceService instances;
    private final Path dataDir;

    public ModrinthService(MinecraftMeta meta, InstanceService instances, Path dataDir) {
        this.meta = meta;
        this.instances = instances;
        this.dataDir = dataDir;
    }

    public Map<String, Object> search(String type, String query, int offset, int limit,
                                      String gameVersion, String loader, String sort) throws Exception {
        List<Object> facets = new ArrayList<>();
        facets.add(Json.arr("project_type:" + modrinthType(type)));
        if (gameVersion != null && !gameVersion.isBlank() && !"all".equals(gameVersion)) {
            facets.add(Json.arr("versions:" + gameVersion));
        }
        if (loader != null && !loader.isBlank() && !"all".equals(loader)) {
            facets.add(Json.arr("categories:" + loader));
        }
        String index = switch (sort == null ? "" : sort) {
            case "downloads" -> "downloads";
            case "newest" -> "newest";
            case "updated" -> "updated";
            case "follows" -> "follows";
            default -> "relevance";
        };
        String url = API + "/search?query=" + Http.encode(query == null ? "" : query)
                + "&offset=" + Math.max(0, offset)
                + "&limit=" + Math.max(1, Math.min(40, limit))
                + "&index=" + index
                + "&facets=" + Http.encode(Json.write(facets));
        Map<String, Object> response = Json.parseObject(Http.getJson(url).body());
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Object value : Json.list(response.get("hits"))) {
            Map<String, Object> hit = Json.map(value);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("source", "modrinth");
            item.put("id", Json.str(hit, "project_id", ""));
            item.put("slug", Json.str(hit, "slug", ""));
            item.put("title", Json.str(hit, "title", "未命名"));
            item.put("description", Json.str(hit, "description", ""));
            item.put("author", Json.str(hit, "author", "Modrinth"));
            item.put("icon", Json.str(hit, "icon_url", ""));
            String cover = Json.str(hit, "featured_gallery", "");
            if (cover.isBlank()) {
                List<Object> gallery = Json.list(hit.get("gallery"));
                if (!gallery.isEmpty()) cover = String.valueOf(gallery.get(0));
            }
            item.put("cover", cover);
            item.put("color", Json.longValue(hit, "color", 0));
            item.put("downloads", Json.longValue(hit, "downloads", 0));
            item.put("follows", Json.longValue(hit, "follows", 0));
            item.put("categories", Json.list(hit.get("categories")));
            item.put("versions", Json.list(hit.get("versions")));
            item.put("type", type);
            item.put("updated", Json.str(hit, "date_modified", ""));
            item.put("url", "https://modrinth.com/" + modrinthType(type) + "/" + Json.str(hit, "slug", ""));
            item.put("featured", false);
            hits.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hits", hits);
        result.put("total", Json.longValue(response, "total_hits", hits.size()));
        result.put("offset", offset);
        result.put("source", "modrinth");
        return result;
    }

    public List<Map<String, Object>> featuredModpacks() throws Exception {
        Map<String, Object> result = search("modpack", "", 0, 6, "", "", "downloads");
        List<Map<String, Object>> hits = new ArrayList<>();
        for (Object value : Json.list(result.get("hits"))) hits.add(Json.map(value));
        return hits;
    }

    public Map<String, Object> project(String idOrSlug) throws Exception {
        Map<String, Object> project = Json.parseObject(Http.getJson(API + "/project/" + Http.encode(idOrSlug)).body());
        Map<String, Object> out = new LinkedHashMap<>(project);
        out.put("source", "modrinth");
        out.put("url", "https://modrinth.com/project/" + Json.str(project, "slug", idOrSlug));
        try {
            List<Object> members = Json.list(Json.parse(Http.getJson(API + "/project/" + Http.encode(idOrSlug)
                    + "/members").body()));
            if (!members.isEmpty()) out.put("author", Json.str(Json.map(members.get(0)), "username", "Modrinth"));
        } catch (Exception ignored) { }
        return out;
    }

    public List<Map<String, Object>> versions(String projectId, String gameVersion, String loader) throws Exception {
        String url = API + "/project/" + Http.encode(projectId) + "/version";
        List<String> params = new ArrayList<>();
        if (gameVersion != null && !gameVersion.isBlank() && !"all".equals(gameVersion)) {
            params.add("game_versions=" + Http.encode(Json.write(Json.arr(gameVersion))));
        }
        if (loader != null && !loader.isBlank() && !"all".equals(loader)) {
            params.add("loaders=" + Http.encode(Json.write(Json.arr(loader))));
        }
        if (!params.isEmpty()) url += "?" + String.join("&", params);
        List<Object> raw = Json.list(Json.parse(Http.getJson(url).body()));
        List<Map<String, Object>> versions = new ArrayList<>();
        for (Object value : raw) versions.add(Json.map(value));
        return versions;
    }

    public Map<String, Object> installFabricApi(String instanceId, Jobs.Job job) throws Exception {
        Map<String, Object> instance = instances.get(instanceId);
        String gameVersion = Json.str(instance, "version", "");
        Path mods = instances.contentDir(instanceId, "mod");
        try (var stream = Files.list(mods)) {
            boolean present = stream.anyMatch(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)
                    .startsWith("fabric-api"));
            if (present) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("instance", instanceId);
                result.put("skipped", true);
                return result;
            }
        }
        List<Map<String, Object>> choices = versions("P7dR8mSH", gameVersion, "fabric");
        if (choices.isEmpty()) throw new IOException("找不到适用于 " + gameVersion + " 的 Fabric API");
        choices.sort((a, b) -> Json.str(b, "date_published", "").compareTo(Json.str(a, "date_published", "")));
        return installContent("mod", "P7dR8mSH", Json.str(choices.get(0), "id", ""), instanceId, job);
    }

    public Map<String, Object> installContent(String type, String projectId, String versionId,
                                              String instanceId, Jobs.Job job) throws Exception {
        Map<String, Object> project = project(projectId);
        Map<String, Object> version = pickVersion(projectId, versionId, null, null, job);
        Map<String, Object> file = primaryFile(version);
        String fileName = Util.safeName(Json.str(file, "filename", Json.str(project, "slug", "content") + ".jar"));
        String url = Json.str(file, "url", "");
        if (url.isBlank()) throw new IOException("该项目没有可下载文件");
        String sha1 = Json.str(Json.map(file.get("hashes")), "sha1", "");
        long size = Json.longValue(file, "size", 0);
        Path dir = instances.contentDir(instanceId, type);
        Path target = dir.resolve(fileName);
        job.progress(0.08, "下载", fileName);
        job.details(0, 1, 0, Math.max(0, size), 0);
        Http.download(url, target, sha1, n -> {
            job.progress(0.08 + 0.86 * (size > 0 ? Math.min(1.0, n / (double) size) : 0.5),
                    "下载", Util.bytes(n) + (size > 0 ? " / " + Util.bytes(size) : ""));
            job.details(0, 1, n, Math.max(0, size), 0);
        });
        job.details(1, 1, size, Math.max(1, size), 0);
        job.log("已安装 " + fileName + " → " + dir.getFileName());
        if ("world".equals(type) || "map".equals(type)) {
            Path worldDir = dir.resolve(Util.slug(Json.str(project, "title", fileName)));
            Util.unzip(target, worldDir, null);
            Files.deleteIfExists(target);
            job.log("地图已解压至 " + worldDir.getFileName());
        }
        int dependencies = 0;
        if ("mod".equals(type)) {
            Set<String> visited = new LinkedHashSet<>();
            visited.add(projectId);
            dependencies = installDependencies(version, instanceId, job, visited, 0);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", target.toString());
        result.put("instance", instanceId);
        result.put("type", type);
        result.put("version", Json.str(version, "version_number", ""));
        result.put("dependencies", dependencies);
        return result;
    }

    private int installDependencies(Map<String, Object> version, String instanceId, Jobs.Job job,
                                    Set<String> visited, int depth) throws Exception {
        if (depth > 5) return 0;
        Map<String, Object> instance = instances.get(instanceId);
        String gameVersion = Json.str(instance, "version", "");
        String loader = Json.str(instance, "loader", "fabric");
        int installed = 0;
        for (Object value : Json.list(version.get("dependencies"))) {
            Map<String, Object> dependency = Json.map(value);
            if (!"required".equals(Json.str(dependency, "dependency_type", ""))) continue;
            String projectId = Json.str(dependency, "project_id", "");
            if (projectId.isBlank() || !visited.add(projectId)) continue;
            try {
                Map<String, Object> depVersion = null;
                String depVersionId = Json.str(dependency, "version_id", "");
                if (!depVersionId.isBlank()) {
                    depVersion = Json.parseObject(Http.getJson(API + "/version/" + Http.encode(depVersionId)).body());
                }
                if (depVersion == null) {
                    List<Map<String, Object>> choices = versions(projectId, gameVersion, loader);
                    if (choices.isEmpty()) choices = versions(projectId, gameVersion, null);
                    if (choices.isEmpty()) continue;
                    choices.sort((a, b) -> Json.str(b, "date_published", "").compareTo(Json.str(a, "date_published", "")));
                    depVersion = choices.get(0);
                }
                Map<String, Object> depFile = primaryFile(depVersion);
                String fileName = Util.safeName(Json.str(depFile, "filename", projectId + ".jar"));
                Path dir = instances.contentDir(instanceId, "mod");
                Path target = dir.resolve(fileName);
                if (Files.exists(target)) continue;
                String url = Json.str(depFile, "url", "");
                if (url.isBlank()) continue;
                job.progress(0.94, "依赖模组", "下载 " + fileName);
                Http.download(url, target, Json.str(Json.map(depFile.get("hashes")), "sha1", ""), null);
                job.log("自动安装依赖 · " + fileName);
                installed++;
                installed += installDependencies(depVersion, instanceId, job, visited, depth + 1);
            } catch (Exception e) {
                job.log("依赖安装跳过 · " + projectId + " · " + e.getMessage());
            }
        }
        return installed;
    }

    public Map<String, Object> installModpack(String projectId, String versionId, Jobs.Job job) throws Exception {
        Map<String, Object> project = project(projectId);
        Map<String, Object> version = pickVersion(projectId, versionId, null, null, job);
        Map<String, Object> file = primaryFile(version);
        String url = Json.str(file, "url", "");
        String fileName = Json.str(file, "filename", "pack.mrpack");
        if (url.isBlank()) throw new IOException("整合包没有可下载文件");
        Path archive = dataDir.resolve("cache").resolve("packs").resolve(Util.safeName(fileName));
        job.progress(0.02, "整合包", "下载 " + fileName);
        long size = Json.longValue(file, "size", 0);
        Http.download(url, archive, Json.str(Json.map(file.get("hashes")), "sha1", ""),
                n -> job.progress(0.02 + 0.22 * (size > 0 ? Math.min(1.0, n / (double) size) : 0.5),
                        "整合包", Util.bytes(n)));
        Path temp = dataDir.resolve("cache").resolve("packs").resolve("unpack-" + Util.uuid());
        try {
            Files.createDirectories(temp);
            Util.unzip(archive, temp, null);
            Path indexFile = temp.resolve("modrinth.index.json");
            if (!Files.isRegularFile(indexFile)) throw new IOException("不是有效的 Modrinth 整合包（缺少 modrinth.index.json）");
            Map<String, Object> index = Json.parseObject(Util.read(indexFile, "{}"));
            Map<String, Object> dependencies = Json.map(index.get("dependencies"));
            String gameVersion = Json.str(dependencies, "minecraft", "");
            String loader = "vanilla";
            String loaderVersion = "";
            if (!Json.str(dependencies, "fabric-loader", "").isBlank()) {
                loader = "fabric";
                loaderVersion = Json.str(dependencies, "fabric-loader", "");
            } else if (!Json.str(dependencies, "quilt-loader", "").isBlank()) {
                loader = "quilt";
                loaderVersion = Json.str(dependencies, "quilt-loader", "");
            } else if (!Json.str(dependencies, "forge", "").isBlank()) {
                loader = "forge";
                loaderVersion = Json.str(dependencies, "forge", "");
            } else if (!Json.str(dependencies, "neoforge", "").isBlank()) {
                throw new IOException("这个整合包需要 NeoForge " + Json.str(dependencies, "neoforge", "")
                        + "，当前版本暂未支持 NeoForge 自动安装；请选择 Fabric 整合包");
            }
            if (gameVersion.isBlank()) throw new IOException("整合包没有声明 Minecraft 版本");
            String name = Json.str(project, "title", Json.str(index, "name", "整合包"));
            Map<String, Object> instance = instances.createFromModpack(name, gameVersion, loader, loaderVersion,
                    Json.str(project, "description", ""), "modrinth", projectId);
            String instanceId = Json.str(instance, "id", "");
            Path gameDir = instances.instanceDir(instanceId);
            job.log("实例已创建 · " + name + " · " + gameVersion + " / " + loader);

            List<MinecraftMeta.Dl> downloads = new ArrayList<>();
            for (Object value : Json.list(index.get("files"))) {
                Map<String, Object> entry = Json.map(value);
                Map<String, Object> env = Json.map(entry.get("env"));
                if ("unsupported".equalsIgnoreCase(Json.str(env, "client", "required"))) continue;
                String path = Json.str(entry, "path", "");
                if (path.isBlank()) continue;
                Path target = gameDir.resolve(path).normalize();
                if (!target.startsWith(gameDir.normalize())) continue;
                List<Object> urls = Json.list(entry.get("downloads"));
                if (urls.isEmpty()) continue;
                String downloadUrl = String.valueOf(urls.get(0));
                String sha1 = Json.str(Json.map(entry.get("hashes")), "sha1", "");
                downloads.add(new MinecraftMeta.Dl(downloadUrl, target, sha1, Json.longValue(entry, "fileSize", 0),
                        target.getFileName().toString()));
            }
            job.progress(0.28, "整合包内容", downloads.size() + " 个文件");
            meta.downloadAll(downloads, job, "整合包内容", 0.28, 0.58);

            job.progress(0.88, "覆盖配置", "写入 overrides");
            for (String override : new String[]{"overrides", "client-overrides"}) {
                Path source = temp.resolve(override);
                if (Files.isDirectory(source)) {
                    Util.copyRecursive(source, gameDir);
                    job.log("已应用 " + override);
                }
            }
            String icon = Json.str(project, "icon_url", "");
            if (!icon.isBlank()) {
                try {
                    Http.download(icon, gameDir.resolve("icon.png"), null, null);
                } catch (Exception ignored) { }
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

    private Map<String, Object> pickVersion(String projectId, String versionId, String gameVersion,
                                            String loader, Jobs.Job job) throws Exception {
        if (versionId != null && !versionId.isBlank()) {
            return Json.parseObject(Http.getJson(API + "/version/" + Http.encode(versionId)).body());
        }
        List<Map<String, Object>> versions = versions(projectId, gameVersion, loader);
        if (versions.isEmpty()) versions = versions(projectId, null, null);
        if (versions.isEmpty()) throw new IOException("该项目没有可用版本");
        versions.sort((a, b) -> Json.str(b, "date_published", "").compareTo(Json.str(a, "date_published", "")));
        return versions.get(0);
    }

    private Map<String, Object> primaryFile(Map<String, Object> version) throws IOException {
        List<Object> files = Json.list(version.get("files"));
        if (files.isEmpty()) throw new IOException("版本没有文件");
        for (Object value : files) {
            Map<String, Object> file = Json.map(value);
            if (Json.bool(file, "primary", false)) return file;
        }
        return Json.map(files.get(0));
    }

    private static String modrinthType(String type) {
        if (type == null) return "mod";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "modpack", "modpacks" -> "modpack";
            case "resourcepack", "resourcepacks", "resource_pack" -> "resourcepack";
            case "shader", "shaders" -> "shader";
            case "datapack", "datapacks" -> "datapack";
            case "world", "worlds", "map", "maps" -> "world";
            default -> "mod";
        };
    }
}
