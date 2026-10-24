package org.unioncraft.launcher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Local HTTP API and static file server for the Web UI. */
public final class ApiServer implements HttpHandler {
    private final String token;
    private final Path dataDir;
    private final Path webDir;
    private final Config config;
    private final AuthService auth;
    private final MinecraftMeta meta;
    private final InstanceService instances;
    private final JavaService javaService;
    private final ModrinthService modrinth;
    private final CurseForgeService curseForge;
    private final Launcher launcher;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private static volatile long lastHeartbeat = System.currentTimeMillis();

    public static long lastHeartbeat() {
        return lastHeartbeat;
    }

    public ApiServer(String token, Path dataDir, Path webDir, Config config, AuthService auth,
                     MinecraftMeta meta, InstanceService instances, JavaService javaService,
                     ModrinthService modrinth, CurseForgeService curseForge, Launcher launcher) {
        this.token = token;
        this.dataDir = dataDir;
        this.webDir = webDir;
        this.config = config;
        this.auth = auth;
        this.meta = meta;
        this.instances = instances;
        this.javaService = javaService;
        this.modrinth = modrinth;
        this.curseForge = curseForge;
        this.launcher = launcher;
    }

    private record CacheEntry(long timestamp, Object value) {}

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                handleApi(exchange, path);
            } else {
                serveStatic(exchange, path);
            }
        } catch (Exception e) {
            try {
                int status = e instanceof IllegalArgumentException ? 400 : 500;
                sendJson(exchange, status, Json.obj("error", friendly(e)));
            } catch (Exception ignored) { }
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String path) throws Exception {
        if (!authorized(exchange)) {
            sendJson(exchange, 401, Json.obj("error", "未授权的请求，请从 Union Craft Launcher 窗口打开"));
            return;
        }
        lastHeartbeat = System.currentTimeMillis();
        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
        if (path.equals("/api/health")) {
            sendJson(exchange, 200, Json.obj("ok", true, "version", Util.VERSION));
            return;
        }
        if (path.equals("/api/heartbeat") && method.equals("POST")) {
            lastHeartbeat = System.currentTimeMillis();
            sendJson(exchange, 200, Json.obj("ok", true));
            return;
        }
        if (path.equals("/api/bootstrap") && method.equals("GET")) {
            sendJson(exchange, 200, bootstrap());
            return;
        }
        if (path.equals("/api/versions") && method.equals("GET")) {
            Map<String, String> params = Util.parseQuery(exchange.getRequestURI().getRawQuery());
            boolean snapshots = Json.bool(params.get("snapshots"), config.getBool("showSnapshots", false));
            String rootParam = Json.str(params, "root", "");
            MinecraftMeta versionMeta = rootParam.isBlank() ? meta
                    : new MinecraftMeta(config, dataDir, Path.of(rootParam));
            List<Map<String, Object>> all = new ArrayList<>();
            String warning = "";
            try {
                all.addAll(versionMeta.versionList());
            } catch (Exception e) {
                warning = "远程版本清单读取失败：" + e.getMessage();
            }
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
            for (Map<String, Object> version : all) {
                version.put("installed", false);
                merged.put(Json.str(version, "id", ""), version);
            }
            for (Map<String, Object> local : versionMeta.localVersions()) {
                String id = Json.str(local, "id", "");
                Map<String, Object> existing = merged.get(id);
                if (existing != null) {
                    existing.put("installed", true);
                    existing.put("local", true);
                    if (!"vanilla".equals(Json.str(local, "loader", "vanilla"))) {
                        existing.put("loader", Json.str(local, "loader", "vanilla"));
                    }
                } else {
                    merged.put(id, local);
                }
            }
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> version : merged.values()) {
                String type = Json.str(version, "type", "release");
                if (!snapshots && !"release".equals(type) && !Json.bool(version, "local", false)) continue;
                filtered.add(version);
                if (filtered.size() >= 800) break;
            }
            filtered.sort((a, b) -> Json.str(b, "releaseTime", "").compareTo(Json.str(a, "releaseTime", "")));
            Map<String, Object> latest = new LinkedHashMap<>();
            try {
                latest = Json.map(versionMeta.manifest().get("latest"));
            } catch (Exception ignored) { }
            sendJson(exchange, 200, Json.obj("versions", filtered, "latest", latest,
                    "warning", warning, "source", new Mirror(config).label(),
                    "root", versionMeta.root().toString()));
            return;
        }
        if (path.equals("/api/scan-root") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String rawPath = Json.str(body, "path", "");
            Path root = Path.of(rawPath).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) throw new IllegalArgumentException("目录不存在：" + root);
            Path versions = root.resolve("versions");
            if (!Files.isDirectory(versions)) throw new IllegalArgumentException("这个目录不像 Minecraft 客户端目录（缺少 versions 文件夹）");
            MinecraftMeta scanned = new MinecraftMeta(config, dataDir, root);
            Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
            for (Map<String, Object> local : scanned.localVersions()) {
                merged.put(Json.str(local, "id", ""), local);
            }
            List<Map<String, Object>> values = new ArrayList<>(merged.values());
            values.sort((a, b) -> Json.str(b, "releaseTime", "").compareTo(Json.str(a, "releaseTime", "")));
            sendJson(exchange, 200, Json.obj("path", root.toString(), "versions", values,
                    "count", values.size(), "name", root.getFileName() == null ? root.toString() : root.getFileName().toString()));
            return;
        }
        if (path.equals("/api/pick-folder") && method.equals("POST")) {
            String selected = FolderPicker.pick("选择 Minecraft 客户端目录");
            sendJson(exchange, 200, Json.obj("path", selected));
            return;
        }
        if (path.equals("/api/java") && method.equals("GET")) {
            sendJson(exchange, 200, Json.obj("runtimes", javaService.detect(false).stream().map(JavaService.JavaInfo::json).toList()));
            return;
        }
        if (path.equals("/api/java/scan") && method.equals("POST")) {
            sendJson(exchange, 200, Json.obj("runtimes", javaService.detect(true).stream().map(JavaService.JavaInfo::json).toList()));
            return;
        }
        if (path.equals("/api/config")) {
            if (method.equals("GET")) {
                sendJson(exchange, 200, configForUi());
                return;
            }
            if (method.equals("POST")) {
                Map<String, Object> body = readJson(exchange);
                saveConfig(body);
                sendJson(exchange, 200, configForUi());
                return;
            }
        }
        if (path.equals("/api/accounts") && method.equals("GET")) {
            sendJson(exchange, 200, auth.status());
            return;
        }
        if (path.equals("/api/auth/start") && method.equals("POST")) {
            sendJson(exchange, 200, Json.obj("url", auth.startUrl()));
            return;
        }
        if (path.equals("/api/window/theme") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            WindowTheme.applyAsync(Json.bool(body, "dark", false));
            sendJson(exchange, 200, Json.obj("ok", true));
            return;
        }
        if (path.equals("/api/auth/open") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String url = Json.str(body, "url", "");
            if (!(url.startsWith("https://login.live.com/")
                    || url.startsWith("https://login.microsoftonline.com/"))) {
                throw new IllegalArgumentException("只允许打开 Microsoft 登录地址");
            }
            boolean opened = Main.openExternal(url);
            sendJson(exchange, 200, Json.obj("ok", opened));
            return;
        }
        if (path.equals("/api/auth/complete") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String callback = Json.str(body, "url", "");
            Jobs.Job job = Jobs.async("auth", "Microsoft 登录", j -> {
                Map<String, Object> account = auth.login(callback, j);
                j.complete(Json.obj("account", account, "accounts", auth.status()));
            });
            sendJson(exchange, 202, Json.obj("job", job.id(), "message", "正在登录"));
            return;
        }
        if (path.equals("/api/auth/offline") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            Map<String, Object> account = auth.offlineLogin(Json.str(body, "name", ""));
            sendJson(exchange, 200, Json.obj("account", account, "accounts", auth.status()));
            return;
        }
        if (path.equals("/api/auth/thirdparty") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String url = Json.str(body, "url", "");
            String username = Json.str(body, "username", "");
            String password = Json.str(body, "password", "");
            Jobs.Job job = Jobs.async("auth", "第三方验证", j -> {
                Map<String, Object> account = auth.thirdPartyLogin(url, username, password, j);
                j.complete(Json.obj("account", account, "accounts", auth.status()));
            });
            sendJson(exchange, 202, Json.obj("job", job.id()));
            return;
        }
        if (path.equals("/api/auth/select") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            auth.setActive(Json.str(body, "id", ""));
            sendJson(exchange, 200, auth.status());
            return;
        }
        if (path.equals("/api/auth/logout") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            auth.remove(Json.str(body, "id", ""));
            sendJson(exchange, 200, auth.status());
            return;
        }
        if (path.equals("/api/instances") && method.equals("GET")) {
            sendJson(exchange, 200, Json.obj("instances", instances.list()));
            return;
        }
        if (path.equals("/api/instances/import") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            Map<String, Object> instance = instances.importExternal(
                    Json.str(body, "name", ""),
                    Json.str(body, "path", ""),
                    Json.str(body, "version", ""),
                    Json.str(body, "loader", "vanilla"),
                    Json.bool(body, "isolated", true));
            sendJson(exchange, 200, Json.obj("instance", instance));
            return;
        }
        if (path.equals("/api/instances") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String version = Json.str(body, "version", "");
            if (version.isBlank()) throw new IllegalArgumentException("请选择 Minecraft 版本");
            String loader = Json.str(body, "loader", "vanilla").toLowerCase(Locale.ROOT);
            if (!loader.equals("vanilla") && !loader.equals("fabric") && !loader.equals("quilt")
                    && !loader.equals("forge")) {
                throw new IllegalArgumentException("当前版本支持 Vanilla / Fabric / Quilt / Forge");
            }
            meta.detail(version);
            Map<String, Object> instance = instances.create(
                    Json.str(body, "name", "新的世界"),
                    version,
                    loader,
                    Json.str(body, "loaderVersion", ""),
                    Json.str(body, "description", ""));
            sendJson(exchange, 200, Json.obj("instance", instance));
            return;
        }
        if (path.startsWith("/api/instances/")) {
            String rest = path.substring("/api/instances/".length());
            String[] parts = rest.split("/");
            String id = Util.urlDecode(parts[0]);
            if (parts.length == 1) {
                if (method.equals("GET")) {
                    sendJson(exchange, 200, Json.obj("instance", instances.get(id)));
                    return;
                }
                if (method.equals("PATCH")) {
                    sendJson(exchange, 200, Json.obj("instance", instances.update(id, readJson(exchange))));
                    return;
                }
                if (method.equals("DELETE")) {
                    instances.delete(id, true);
                    sendJson(exchange, 200, Json.obj("ok", true));
                    return;
                }
            }
            if (parts.length >= 2) {
                String action = parts[1];
                if (action.equals("launch") && method.equals("POST")) {
                    Map<String, Object> instance = instances.get(id);
                    Jobs.Job job = Jobs.async("launch", "启动 " + Json.str(instance, "name", id), j -> {
                        Map<String, Object> result = launcher.launch(id, j);
                        j.complete(result);
                    });
                    sendJson(exchange, 202, Json.obj("job", job.id()));
                    return;
                }
                if (action.equals("prepare") && method.equals("POST")) {
                    Map<String, Object> instance = instances.get(id);
                    Jobs.Job job = Jobs.async("install", "准备 " + Json.str(instance, "name", id), j -> {
                        Map<String, Object> result = launcher.prepare(id, j);
                        j.complete(result);
                    });
                    sendJson(exchange, 202, Json.obj("job", job.id()));
                    return;
                }
                if (action.equals("stop") && method.equals("POST")) {
                    launcher.stop(id);
                    sendJson(exchange, 200, Json.obj("ok", true));
                    return;
                }
                if (action.equals("open") && method.equals("POST")) {
                    Util.openFolder(instances.gameDir(id));
                    sendJson(exchange, 200, Json.obj("ok", true));
                    return;
                }
                if (action.equals("icon") && method.equals("GET")) {
                    Path icon = instances.instanceDir(id).resolve("icon.png");
                    if (Files.isRegularFile(icon)) {
                        byte[] bytes = Files.readAllBytes(icon);
                        exchange.getResponseHeaders().set("Content-Type", "image/png");
                        exchange.getResponseHeaders().set("Cache-Control", "private, max-age=120");
                        exchange.sendResponseHeaders(200, bytes.length);
                        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
                    } else {
                        sendJson(exchange, 404, Json.obj("error", "No icon"));
                    }
                    return;
                }
                if (action.equals("log") && method.equals("GET")) {
                    sendJson(exchange, 200, Json.obj("log", launcher.tailLog(id, 240)));
                    return;
                }
                if (action.equals("content")) {
                    Map<String, String> params = Util.parseQuery(exchange.getRequestURI().getRawQuery());
                    String type = Json.str(params, "type", "mod");
                    if (method.equals("GET")) {
                        sendJson(exchange, 200, Json.obj("files", instances.content(id, type)));
                        return;
                    }
                    if (method.equals("DELETE")) {
                        instances.removeContent(id, type, Json.str(params, "name", ""));
                        sendJson(exchange, 200, Json.obj("ok", true));
                        return;
                    }
                }
                if (action.equals("content") && parts.length >= 3 && parts[2].equals("toggle") && method.equals("POST")) {
                    Map<String, Object> body = readJson(exchange);
                    instances.toggleContent(id, Json.str(body, "type", "mod"), Json.str(body, "name", ""));
                    sendJson(exchange, 200, Json.obj("ok", true));
                    return;
                }
            }
        }
        if (path.equals("/api/running") && method.equals("GET")) {
            sendJson(exchange, 200, Json.obj("running", launcher.running()));
            return;
        }
        if (path.equals("/api/open-folder") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String target = Json.str(body, "target", "data");
            if ("minecraft".equals(target)) Util.openFolder(meta.root());
            else if ("instances".equals(target)) Util.openFolder(config.instancesDir());
            else if ("game".equals(target)) Util.openFolder(instances.gameDir(Json.str(body, "instanceId", "")));
            else Util.openFolder(dataDir);
            sendJson(exchange, 200, Json.obj("ok", true));
            return;
        }
        if (path.equals("/api/jobs") && method.equals("GET")) {
            sendJson(exchange, 200, Json.obj("jobs", Jobs.active()));
            return;
        }
        if (path.startsWith("/api/jobs/") && method.equals("GET")) {
            String id = path.substring("/api/jobs/".length());
            Jobs.Job job = Jobs.get(id);
            if (job == null) {
                sendJson(exchange, 404, Json.obj("error", "任务不存在"));
            } else {
                sendJson(exchange, 200, job.json());
            }
            return;
        }
        if (path.equals("/api/discover") && method.equals("GET")) {
            Map<String, String> params = Util.parseQuery(exchange.getRequestURI().getRawQuery());
            String source = Json.str(params, "source", "modrinth");
            String type = Json.str(params, "type", "modpack");
            String query = Json.str(params, "q", "");
            int offset = Math.max(0, Json.integer(params.get("offset"), 0));
            int limit = Math.max(1, Math.min(40, Json.integer(params.get("limit"), 20)));
            String gameVersion = Json.str(params, "gameVersion", "");
            String loader = Json.str(params, "loader", "");
            String sort = Json.str(params, "sort", "downloads");
            String key = "discover:" + source + ":" + type + ":" + query + ":" + offset + ":" + limit
                    + ":" + gameVersion + ":" + loader + ":" + sort;
            Map<String, Object> result = (Map<String, Object>) cached(key, 120_000, () ->
                    "curseforge".equalsIgnoreCase(source)
                            ? curseForge.search(type, query, offset, limit, gameVersion, loader, sort)
                            : modrinth.search(type, query, offset, limit, gameVersion, loader, sort));
            sendJson(exchange, 200, result);
            return;
        }
        if (path.equals("/api/featured") && method.equals("GET")) {
            Map<String, Object> result = (Map<String, Object>) cached("featured", 300_000, () -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("modpacks", modrinth.featuredModpacks());
                out.put("hero", modrinth.search("modpack", "", 0, 4, "", "", "downloads").get("hits"));
                return out;
            });
            sendJson(exchange, 200, result);
            return;
        }
        if (path.equals("/api/project") && method.equals("GET")) {
            Map<String, String> params = Util.parseQuery(exchange.getRequestURI().getRawQuery());
            String source = Json.str(params, "source", "modrinth");
            String id = Json.str(params, "id", "");
            if (id.isBlank()) throw new IllegalArgumentException("缺少项目 ID");
            Object result = "curseforge".equalsIgnoreCase(source) ? curseForge.project(id) : modrinth.project(id);
            sendJson(exchange, 200, result);
            return;
        }
        if (path.equals("/api/project/versions") && method.equals("GET")) {
            Map<String, String> params = Util.parseQuery(exchange.getRequestURI().getRawQuery());
            String source = Json.str(params, "source", "modrinth");
            String id = Json.str(params, "id", "");
            String gameVersion = Json.str(params, "gameVersion", "");
            String loader = Json.str(params, "loader", "");
            List<Map<String, Object>> result = new ArrayList<>();
            if ("curseforge".equalsIgnoreCase(source)) {
                for (Map<String, Object> file : curseForge.files(id, gameVersion, loader)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", Json.str(file, "id", ""));
                    item.put("name", Json.str(file, "displayName", Json.str(file, "fileName", "")));
                    item.put("versionNumber", Json.str(file, "displayName", ""));
                    item.put("gameVersions", Json.list(file.get("gameVersions")));
                    item.put("loaders", new ArrayList<>());
                    item.put("date", Json.str(file, "fileDate", ""));
                    item.put("fileName", Json.str(file, "fileName", ""));
                    item.put("fileSize", Json.longValue(file, "fileLength", 0));
                    item.put("downloads", Json.longValue(file, "downloadCount", 0));
                    result.add(item);
                }
            } else {
                for (Map<String, Object> version : modrinth.versions(id, gameVersion, loader)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", Json.str(version, "id", ""));
                    item.put("name", Json.str(version, "name", ""));
                    item.put("versionNumber", Json.str(version, "version_number", ""));
                    item.put("gameVersions", Json.list(version.get("game_versions")));
                    item.put("loaders", Json.list(version.get("loaders")));
                    item.put("date", Json.str(version, "date_published", ""));
                    Map<String, Object> file = null;
                    for (Object f : Json.list(version.get("files"))) {
                        Map<String, Object> candidate = Json.map(f);
                        if (Json.bool(candidate, "primary", false)) { file = candidate; break; }
                    }
                    if (file == null && !Json.list(version.get("files")).isEmpty()) {
                        file = Json.map(Json.list(version.get("files")).get(0));
                    }
                    item.put("fileName", file == null ? "" : Json.str(file, "filename", ""));
                    item.put("fileSize", file == null ? 0 : Json.longValue(file, "size", 0));
                    item.put("downloads", Json.longValue(version, "downloads", 0));
                    result.add(item);
                }
            }
            result.sort((a, b) -> Json.str(b, "date", "").compareTo(Json.str(a, "date", "")));
            sendJson(exchange, 200, Json.obj("versions", result));
            return;
        }
        if (path.equals("/api/install/fabric-api") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String instanceId = Json.str(body, "instanceId", "");
            instances.get(instanceId);
            Jobs.Job job = Jobs.async("install", "安装 Fabric API", j -> {
                j.complete(modrinth.installFabricApi(instanceId, j));
            });
            sendJson(exchange, 202, Json.obj("job", job.id()));
            return;
        }
        if (path.equals("/api/install") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String source = Json.str(body, "source", "modrinth");
            String type = Json.str(body, "type", "mod");
            String projectId = Json.str(body, "projectId", "");
            String versionId = Json.str(body, "versionId", "");
            String fileId = Json.str(body, "fileId", "");
            String instanceId = Json.str(body, "instanceId", "");
            if (projectId.isBlank() || instanceId.isBlank()) throw new IllegalArgumentException("缺少项目或实例");
            instances.get(instanceId);
            Jobs.Job job = Jobs.async("install", "安装内容", j -> {
                Map<String, Object> result = "curseforge".equalsIgnoreCase(source)
                        ? curseForge.installContent(type, projectId, versionId.isBlank() ? fileId : versionId, instanceId, j)
                        : modrinth.installContent(type, projectId, versionId, instanceId, j);
                j.complete(result);
            });
            sendJson(exchange, 202, Json.obj("job", job.id()));
            return;
        }
        if (path.equals("/api/install/modpack") && method.equals("POST")) {
            Map<String, Object> body = readJson(exchange);
            String source = Json.str(body, "source", "modrinth");
            String projectId = Json.str(body, "projectId", "");
            String versionId = Json.str(body, "versionId", "");
            String fileId = Json.str(body, "fileId", "");
            if (projectId.isBlank()) throw new IllegalArgumentException("缺少整合包 ID");
            Jobs.Job job = Jobs.async("modpack", "安装整合包", j -> {
                Map<String, Object> result = "curseforge".equalsIgnoreCase(source)
                        ? curseForge.installModpack(projectId, versionId.isBlank() ? fileId : versionId, j)
                        : modrinth.installModpack(projectId, versionId, j);
                j.complete(result);
            });
            sendJson(exchange, 202, Json.obj("job", job.id()));
            return;
        }
        if (path.equals("/api/app/quit") && method.equals("POST")) {
            sendJson(exchange, 200, Json.obj("ok", true));
            new Thread(() -> {
                try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                Main.requestShutdown();
            }).start();
            return;
        }
        sendJson(exchange, 404, Json.obj("error", "接口不存在：" + path));
    }

    private String lastJobId() {
        List<Map<String, Object>> active = Jobs.active();
        if (active.isEmpty()) return "";
        return Json.str(active.get(active.size() - 1), "id", "");
    }

    private Map<String, Object> bootstrap() throws Exception {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("version", Util.VERSION);
        out.put("appName", Util.APP_NAME);
        out.put("platform", Launcher.platformLabel());
        out.put("dataDir", dataDir.toString());
        out.put("config", configForUi());
        out.put("accounts", auth.status());
        out.put("instances", instances.list());
        out.put("running", launcher.running());
        out.put("java", javaService.detect(false).stream().map(JavaService.JavaInfo::json).toList());
        Map<String, Object> latest = new LinkedHashMap<>();
        try {
            latest = Json.map(meta.manifest().get("latest"));
        } catch (Exception ignored) {
            latest.put("release", "1.21");
            latest.put("snapshot", "1.21");
        }
        out.put("latest", latest);
        out.put("curseforge", Json.obj("configured", !config.curseforgeKey().isBlank()));
        out.put("now", System.currentTimeMillis());
        return out;
    }

    private Map<String, Object> configForUi() throws Exception {
        Map<String, Object> snapshot = config.snapshot();
        Object key = snapshot.remove("curseforgeKey");
        String text = key == null ? "" : String.valueOf(key);
        snapshot.put("curseforgeKeySet", !text.isBlank());
        snapshot.put("curseforgeKeyHint", text.length() > 4 ? "••••••••" + text.substring(text.length() - 4) : "");
        snapshot.put("minecraftDir", config.minecraftDir().toString());
        snapshot.put("javaRuntimes", javaService.detect(false).stream().map(JavaService.JavaInfo::json).toList());
        return snapshot;
    }

    private void saveConfig(Map<String, Object> body) throws Exception {
        Map<String, Object> sets = new LinkedHashMap<>();
        if (body.containsKey("memoryMb")) {
            int memory = Json.integer(body.get("memoryMb"), config.getInt("memoryMb", 4096));
            sets.put("memoryMb", Math.max(1024, Math.min(32768, memory)));
        }
        if (body.containsKey("minMemoryMb")) {
            int min = Json.integer(body.get("minMemoryMb"), config.getInt("minMemoryMb", 1024));
            sets.put("minMemoryMb", Math.max(512, Math.min(16384, min)));
        }
        for (String key : new String[]{"javaPath", "showSnapshots", "closeOnLaunch", "highPerformanceGpu", "accent",
                "theme", "language", "reduceMotion", "downloadSource", "lastInstance"}) {
            if (body.containsKey(key)) sets.put(key, body.get(key));
        }
        if (body.containsKey("downloadConcurrency")) {
            sets.put("downloadConcurrency", Math.max(2, Math.min(64, Json.integer(body.get("downloadConcurrency"), 64))));
        }
        if (body.containsKey("curseforgeKey")) {
            String key = Json.str(body, "curseforgeKey", "").trim();
            if (!key.isBlank() && !key.contains("•")) sets.put("curseforgeKey", key);
        }
        config.setAll(sets);
        config.save();
    }

    @SuppressWarnings("unchecked")
    private Object cached(String key, long ttl, ThrowingSupplier supplier) throws Exception {
        CacheEntry entry = cache.get(key);
        if (entry != null && System.currentTimeMillis() - entry.timestamp < ttl) return entry.value;
        Object value = supplier.get();
        cache.put(key, new CacheEntry(System.currentTimeMillis(), value));
        return value;
    }

    @FunctionalInterface
    private interface ThrowingSupplier {
        Object get() throws Exception;
    }

    private boolean authorized(HttpExchange exchange) {
        if ("dev".equals(token)) return true;
        String header = exchange.getRequestHeaders().getFirst("X-UCL-Token");
        if (token.equals(header)) return true;
        Map<String, String> query = Util.parseQuery(exchange.getRequestURI().getRawQuery());
        return token.equals(query.get("token"));
    }

    private Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(2 * 1024 * 1024);
            if (bytes.length == 0) return new LinkedHashMap<>();
            return Json.parseObject(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private void sendJson(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = Json.write(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void serveStatic(HttpExchange exchange, String rawPath) throws IOException {
        String method = exchange.getRequestMethod();
        if (!method.equals("GET") && !method.equals("HEAD")) {
            sendJson(exchange, 405, Json.obj("error", "Method not allowed"));
            return;
        }
        String path = Util.urlDecode(rawPath);
        if (path.equals("/") || path.isBlank()) path = "/index.html";
        if (path.contains("..")) {
            sendJson(exchange, 400, Json.obj("error", "Bad path"));
            return;
        }
        Path file = webDir.resolve(path.substring(1)).normalize();
        if (!file.startsWith(webDir.normalize()) || !Files.isRegularFile(file)) {
            sendJson(exchange, 404, Json.obj("error", "Not found"));
            return;
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        String type = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            default -> "application/octet-stream";
        };
        byte[] bytes = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' https: data:; style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self'; connect-src 'self'; font-src 'self' data:; frame-ancestors 'none'");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(200, method.equals("HEAD") ? -1 : bytes.length);
        if (!method.equals("HEAD")) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private static String friendly(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}

