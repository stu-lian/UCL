package org.unioncraft.launcher;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Microsoft account login using the first-party Minecraft client authorization-code flow
 * (client id 00000000402b5328, redirect https://login.live.com/oauth20_desktop.srf), then the
 * Xbox Live -> XSTS -> Minecraft Services exchange. No client secret is required.
 */
public final class AuthService {
    public static final String CLIENT_ID = "00000000402b5328";
    public static final String REDIRECT_URI = "https://login.live.com/oauth20_desktop.srf";
    private static final String SCOPE = "XboxLive.signin offline_access";
    private static final String AUTHORIZE = "https://login.live.com/oauth20_authorize.srf";
    private static final String TOKEN = "https://login.live.com/oauth20_token.srf";

    private final Path file;
    private final Map<String, Object> data = new LinkedHashMap<>();
    private volatile String pendingState = "";

    public AuthService(Path dataDir) {
        this.file = dataDir.resolve("accounts.json");
        if (Files.exists(file)) {
            try {
                data.putAll(Json.parseObject(Util.read(file, "{}")));
            } catch (Exception ignored) { }
        }
        if (!(data.get("accounts") instanceof List)) data.put("accounts", new ArrayList<>());
        if (!(data.get("active") instanceof String)) data.put("active", "");
    }

    public synchronized String startUrl() {
        String state = UUID.randomUUID().toString().replace("-", "");
        pendingState = state;
        return AUTHORIZE + "?client_id=" + Http.encode(CLIENT_ID)
                + "&response_type=code"
                + "&redirect_uri=" + Http.encode(REDIRECT_URI)
                + "&scope=" + Http.encode(SCOPE)
                + "&prompt=select_account"
                + "&state=" + state;
    }

    public synchronized String pendingState() {
        return pendingState;
    }

    public synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", activeId());
        List<Object> accounts = new ArrayList<>();
        for (Object value : accounts()) {
            accounts.add(publicAccount(Json.map(value)));
        }
        out.put("accounts", accounts);
        return out;
    }

    public synchronized Map<String, Object> activeProfile() {
        String active = activeId();
        for (Object value : accounts()) {
            Map<String, Object> account = Json.map(value);
            if (active.equals(Json.str(account, "id", ""))) return publicAccount(account);
        }
        return null;
    }

    public synchronized Map<String, Object> activeAccountRaw() {
        String active = activeId();
        for (Object value : accounts()) {
            Map<String, Object> account = Json.map(value);
            if (active.equals(Json.str(account, "id", ""))) return account;
        }
        return null;
    }

    public synchronized Map<String, Object> login(String callbackUrl, Jobs.Job job) throws Exception {
        if (callbackUrl == null || callbackUrl.isBlank()) throw new IOException("请粘贴登录后的回调地址");
        String code = extractCode(callbackUrl);
        if (code.isBlank() && !callbackUrl.contains("://") && callbackUrl.trim().length() > 20) {
            code = callbackUrl.trim();
        }
        if (code.isBlank()) throw new IOException("回调地址中找不到 code 参数");
        if (job != null) {
            job.progress(0.15, "Microsoft 账户", "正在交换授权码");
            job.log("收到授权码 · 正在连接 Microsoft 身份平台");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("code", code);
        form.put("grant_type", "authorization_code");
        form.put("redirect_uri", REDIRECT_URI);
        Http.Response response = Http.postForm(TOKEN, form, Map.of());
        Map<String, Object> token = parseTokenResponse(response);
        if (job != null) job.progress(0.35, "Microsoft 账户", "授权成功 · 正在验证 Xbox 档案");
        Map<String, Object> account = exchange(token, job);
        upsert(account);
        data.put("active", Json.str(account, "id", ""));
        save();
        if (job != null) {
            job.progress(0.95, "Microsoft 账户", "欢迎回来，" + Json.str(account, "name", "玩家"));
            job.log("正版档案已登录 · " + Json.str(account, "name", ""));
        }
        return publicAccount(account);
    }

    private Map<String, Object> exchange(Map<String, Object> token, Jobs.Job job) throws Exception {
        String msaToken = Json.str(token, "access_token", "");
        if (msaToken.isBlank()) throw new IOException("Microsoft 未返回访问令牌");
        if (job != null) job.progress(0.42, "Xbox Live", "验证 Xbox Live 身份");

        Map<String, Object> xblPayload = Json.obj(
                "Properties", Json.obj(
                        "AuthMethod", "RPS",
                        "SiteName", "user.auth.xboxlive.com",
                        "RpsTicket", "d=" + msaToken),
                "RelyingParty", "http://auth.xboxlive.com",
                "TokenType", "JWT");
        Http.Response xblResponse = Http.postJson("https://user.auth.xboxlive.com/user/authenticate", xblPayload,
                Map.of("x-xbl-contract-version", "1"));
        Map<String, Object> xbl = jsonOrThrow(xblResponse, "Xbox Live 身份验证");
        String xblToken = Json.str(xbl, "Token", "");
        String uhs = "";
        List<Object> xui = Json.list(Json.map(xbl.get("DisplayClaims")).get("xui"));
        if (!xui.isEmpty()) uhs = Json.str(Json.map(xui.get(0)), "uhs", "");
        if (xblToken.isBlank() || uhs.isBlank()) throw new IOException("Xbox Live 返回了不完整的身份信息");

        if (job != null) job.progress(0.58, "Xbox Live", "申请 XSTS 授权");
        Map<String, Object> xstsPayload = Json.obj(
                "Properties", Json.obj(
                        "SandboxId", "RETAIL",
                        "UserTokens", Json.arr(xblToken)),
                "RelyingParty", "rp://api.minecraftservices.com/",
                "TokenType", "JWT");
        Http.Response xstsResponse = Http.postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsPayload,
                Map.of("x-xbl-contract-version", "1"));
        if (!xstsResponse.ok()) {
            throw new IOException(xstsMessage(xstsResponse.body()));
        }
        Map<String, Object> xsts = Json.parseObject(xstsResponse.body());
        String xstsToken = Json.str(xsts, "Token", "");
        if (xstsToken.isBlank()) throw new IOException("XSTS 授权失败");

        if (job != null) job.progress(0.7, "Minecraft 服务", "换取 Minecraft 访问令牌");
        Map<String, Object> mcPayload = Json.obj("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);
        Http.Response mcResponse = Http.postJson("https://api.minecraftservices.com/authentication/login_with_xbox",
                mcPayload, Map.of());
        Map<String, Object> mc = jsonOrThrow(mcResponse, "Minecraft 服务登录");
        String mcToken = Json.str(mc, "access_token", "");
        if (mcToken.isBlank()) throw new IOException("Minecraft 服务未返回访问令牌");

        if (job != null) job.progress(0.82, "Minecraft 服务", "读取游戏档案");
        Http.Response profileResponse = Http.get("https://api.minecraftservices.com/minecraft/profile",
                Map.of("Authorization", "Bearer " + mcToken));
        if (profileResponse.status() == 404) {
            throw new IOException("这个 Microsoft 账户还没有拥有 Minecraft: Java Edition");
        }
        Map<String, Object> profile = jsonOrThrow(profileResponse, "Minecraft 档案");
        String id = Json.str(profile, "id", "");
        String name = Json.str(profile, "name", "");
        if (id.isBlank() || name.isBlank()) throw new IOException("Minecraft 档案信息不完整");

        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", id);
        account.put("name", name);
        account.put("accessToken", mcToken);
        account.put("refreshToken", Json.str(token, "refresh_token", ""));
        account.put("msaToken", msaToken);
        account.put("expiresAt", System.currentTimeMillis() + Json.longValue(mc, "expires_in", 86400) * 1000L);
        account.put("skinUrl", "https://minotar.net/helm/" + id + "/160.png");
        account.put("capeUrl", "https://mc-heads.net/body/" + id + "/right");
        account.put("type", "microsoft");
        account.put("addedAt", System.currentTimeMillis());
        return account;
    }

    private Map<String, Object> parseTokenResponse(Http.Response response) throws IOException {
        if (!response.ok()) {
            String body = response.body() == null ? "" : response.body();
            String message = "Microsoft 授权失败";
            try {
                Map<String, Object> error = Json.parseObject(body);
                message += "：" + Json.str(error, "error_description", Json.str(error, "error", ""));
            } catch (Exception ignored) { }
            throw new IOException(message);
        }
        return Json.parseObject(response.body());
    }

    private Map<String, Object> jsonOrThrow(Http.Response response, String action) throws IOException {
        if (!response.ok()) {
            String body = response.body() == null ? "" : response.body();
            String detail = "";
            try {
                Map<String, Object> map = Json.parseObject(body);
                detail = Json.str(map, "errorMessage", Json.str(map, "error", ""));
            } catch (Exception ignored) { }
            throw new IOException(action + "失败（HTTP " + response.status() + "）"
                    + (detail.isBlank() ? "" : "：" + detail));
        }
        return Json.parseObject(response.body());
    }

    private static String xstsMessage(String body) {
        try {
            Map<String, Object> map = Json.parseObject(body);
            long xerr = Json.longValue(map, "XErr", 0);
            if (xerr == 2148916233L) return "该 Microsoft 账户尚未创建 Xbox 档案，请先在 xbox.com 登录一次";
            if (xerr == 2148916235L) return "该账户所在地区无法使用 Xbox Live";
            if (xerr == 2148916238L) return "儿童账户需要先加入 Xbox 家庭组";
            return "XSTS 授权失败（XErr " + xerr + "）";
        } catch (Exception e) {
            return "XSTS 授权失败";
        }
    }

    public synchronized Map<String, Object> offlineLogin(String name) throws IOException {
        String clean = name == null ? "" : name.trim();
        if (!clean.matches("[A-Za-z0-9_]{1,16}")) {
            throw new IOException("离线用户名需要 1-16 位字母、数字或下划线");
        }
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + clean).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", uuid.toString().replace("-", ""));
        account.put("name", clean);
        account.put("type", "offline");
        account.put("accessToken", "0");
        account.put("refreshToken", "");
        account.put("expiresAt", Long.MAX_VALUE);
        account.put("addedAt", System.currentTimeMillis());
        account.put("skinUrl", "");
        account.put("capeUrl", "");
        upsert(account);
        data.put("active", Json.str(account, "id", ""));
        save();
        return publicAccount(account);
    }

    public synchronized Map<String, Object> thirdPartyLogin(String apiRoot, String username, String password,
                                                            Jobs.Job job) throws Exception {
        String api = apiRoot == null ? "" : apiRoot.trim().replaceAll("/+$", "");
        if (!api.matches("https?://.+")) throw new IOException("请输入有效的验证服务器地址（http/https）");
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw new IOException("请输入第三方服务器的用户名和密码");
        }
        if (job != null) {
            job.progress(0.15, "第三方验证", "连接 " + api);
            job.log("使用 authlib-injector / Yggdrasil 协议验证");
        }
        Map<String, Object> payload = Json.obj(
                "agent", Json.obj("name", "Minecraft", "version", 1),
                "username", username,
                "password", password,
                "requestUser", true,
                "clientToken", UUID.randomUUID().toString());
        Http.Response response = Http.postJson(api + "/authserver/authenticate", payload, Map.of());
        if (!response.ok()) {
            String message = "第三方验证失败（HTTP " + response.status() + "）";
            try {
                message += "：" + Json.str(Json.parseObject(response.body()), "errorMessage", "");
            } catch (Exception ignored) { }
            throw new IOException(message);
        }
        Map<String, Object> dataResponse = Json.parseObject(response.body());
        String token = Json.str(dataResponse, "accessToken", "");
        String clientToken = Json.str(dataResponse, "clientToken", "");
        Map<String, Object> selected = Json.map(dataResponse.get("selectedProfile"));
        if (selected.isEmpty()) {
            List<Object> profiles = Json.list(dataResponse.get("availableProfiles"));
            if (!profiles.isEmpty()) selected = Json.map(profiles.get(0));
        }
        String id = Json.str(selected, "id", "");
        String name = Json.str(selected, "name", username);
        if (token.isBlank() || id.isBlank()) throw new IOException("验证服务器没有返回可用的角色档案");
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", id);
        account.put("name", name);
        account.put("type", "thirdparty");
        account.put("server", api);
        account.put("accessToken", token);
        account.put("clientToken", clientToken);
        account.put("refreshToken", "");
        account.put("expiresAt", 0);
        account.put("addedAt", System.currentTimeMillis());
        account.put("skinUrl", "https://minotar.net/helm/" + id + "/160.png");
        account.put("capeUrl", "");
        upsert(account);
        data.put("active", id);
        save();
        if (job != null) {
            job.progress(0.9, "第三方验证", "欢迎，" + name);
            job.log("第三方角色已登录 · " + name + " · " + api);
        }
        return publicAccount(account);
    }

    public synchronized Path ensureAuthlibInjector(Path dataDir, Jobs.Job job) throws Exception {
        Path target = dataDir.resolve("cache").resolve("authlib-injector.jar");
        if (Files.isRegularFile(target) && Files.size(target) > 100_000) return target;
        if (job != null) job.log("下载 authlib-injector");
        Map<String, Object> meta = Json.parseObject(Http.getJson(
                "https://authlib-injector.yushi.moe/artifact/latest.json").body());
        String url = Json.str(meta, "download_url", "");
        if (url.isBlank()) throw new IOException("无法获取 authlib-injector 下载地址");
        Http.download(url, target, null, null);
        return target;
    }

    public static String extractCode(String input) {
        String value = input.trim();
        if (value.contains("code=")) {
            int index = value.indexOf("code=");
            String tail = value.substring(index + 5);
            int end = tail.length();
            for (char stop : new char[]{'&', '#', ' '}) {
                int found = tail.indexOf(stop);
                if (found >= 0 && found < end) end = found;
            }
            return Util.urlDecode(tail.substring(0, end));
        }
        return "";
    }

    public synchronized Map<String, Object> ensureFresh(Jobs.Job job) throws Exception {
        Map<String, Object> account = activeAccountRaw();
        if (account == null) throw new IOException("请先登录账户");
        String type = Json.str(account, "type", "microsoft");
        if ("offline".equals(type)) return account;
        if ("thirdparty".equals(type)) {
            if (Json.str(account, "accessToken", "").isBlank()) {
                throw new IOException("第三方验证已失效，请重新验证");
            }
            return account;
        }
        long expires = Json.longValue(account, "expiresAt", 0);
        if (expires > System.currentTimeMillis() + 90_000L && !Json.str(account, "accessToken", "").isBlank()) {
            return account;
        }
        String refresh = Json.str(account, "refreshToken", "");
        if (refresh.isBlank()) throw new IOException("登录已过期，请重新登录 Microsoft 账户");
        if (job != null) {
            job.progress(0.12, "账户", "刷新 Microsoft 登录");
            job.log("Minecraft 令牌过期 · 正在静默刷新");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("refresh_token", refresh);
        form.put("grant_type", "refresh_token");
        form.put("redirect_uri", REDIRECT_URI);
        form.put("scope", SCOPE);
        Http.Response response = Http.postForm(TOKEN, form, Map.of());
        Map<String, Object> token = parseTokenResponse(response);
        Map<String, Object> fresh = exchange(token, job);
        if (Json.str(fresh, "refreshToken", "").isBlank()) fresh.put("refreshToken", refresh);
        fresh.put("addedAt", Json.longValue(account, "addedAt", System.currentTimeMillis()));
        upsert(fresh);
        save();
        return fresh;
    }

    public synchronized void setActive(String id) {
        data.put("active", id);
        save();
    }

    public synchronized void remove(String id) {
        List<Object> accounts = accounts();
        accounts.removeIf(value -> id.equals(Json.str(Json.map(value), "id", "")));
        if (id.equals(activeId())) data.put("active", accounts.isEmpty() ? ""
                : Json.str(Json.map(accounts.get(0)), "id", ""));
        save();
    }

    private void upsert(Map<String, Object> account) {
        String id = Json.str(account, "id", "");
        List<Object> accounts = accounts();
        for (int i = 0; i < accounts.size(); i++) {
            if (id.equals(Json.str(Json.map(accounts.get(i)), "id", ""))) {
                accounts.set(i, account);
                return;
            }
        }
        accounts.add(account);
    }

    @SuppressWarnings("unchecked")
    private List<Object> accounts() {
        Object value = data.get("accounts");
        if (!(value instanceof List)) {
            value = new ArrayList<>();
            data.put("accounts", value);
        }
        return (List<Object>) value;
    }

    private String activeId() {
        return Json.str(data.get("active"), "");
    }

    private Map<String, Object> publicAccount(Map<String, Object> account) {
        if (account == null || account.isEmpty()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", Json.str(account, "id", ""));
        out.put("name", Json.str(account, "name", ""));
        out.put("skinUrl", Json.str(account, "skinUrl", ""));
        out.put("capeUrl", Json.str(account, "capeUrl", ""));
        out.put("type", Json.str(account, "type", "microsoft"));
        out.put("server", Json.str(account, "server", ""));
        out.put("addedAt", Json.longValue(account, "addedAt", 0));
        out.put("active", Json.str(account, "id", "").equals(activeId()));
        return out;
    }

    private void save() {
        try {
            Util.write(file, Json.write(data));
        } catch (IOException ignored) { }
    }

    public static String safeCallback(String value) {
        try {
            URI uri = URI.create(value.trim());
            return uri.getHost() == null ? "" : uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }
}
