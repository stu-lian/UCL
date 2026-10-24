package org.unioncraft.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/** Thin HTTP helper around the JDK client with retries and streaming downloads. */
public final class Http {
    private Http() {}

    public static final String USER_AGENT = "UnionCraftLauncher/0.2.0 (Minecraft launcher; contact: local)";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private static final HttpClient NO_REDIRECT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    public record Response(int status, String body, Map<String, java.util.List<String>> headers) {
        public boolean ok() { return status >= 200 && status < 300; }
        public String header(String name) {
            for (Map.Entry<String, java.util.List<String>> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            }
            return "";
        }
    }

    public static Response get(String url) throws IOException, InterruptedException {
        return get(url, Map.of());
    }

    public static Response get(String url, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json, */*")
                .GET();
        headers.forEach(b::header);
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body(), r.headers().map());
    }

    public static Response getJson(String url) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Response r = get(url);
                if (r.ok()) return r;
                last = new IOException("HTTP " + r.status() + " for " + url + ": " + trim(r.body()));
                if (r.status() >= 400 && r.status() < 500) break;
            } catch (IOException e) {
                last = e;
            }
            if (attempt < 2) Thread.sleep(350L * (attempt + 1));
        }
        throw last == null ? new IOException("请求失败：" + url) : last;
    }

    public static Response postJson(String url, Object payload, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8));
        headers.forEach(b::header);
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body(), r.headers().map());
    }

    public static Response postForm(String url, Map<String, String> form, Map<String, String> headers)
            throws IOException, InterruptedException {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!body.isEmpty()) body.append('&');
            body.append(encode(e.getKey())).append('=').append(encode(e.getValue()));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        headers.forEach(b::header);
        HttpResponse<String> r = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(r.statusCode(), r.body(), r.headers().map());
    }

    public static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * Downloads a URL to a path, using a .part file and three retries. If the target already
     * exists and its SHA-1 matches, it is left untouched. Progress is reported in cumulative bytes
     * for the current file.
     */
    public static void download(String url, Path target, String expectedSha1, LongConsumer progress)
            throws IOException, InterruptedException {
        download(url, target, expectedSha1, progress, progress, true);
    }

    public static void download(String url, Path target, String expectedSha1, LongConsumer progress,
                                boolean followRedirects)
            throws IOException, InterruptedException {
        download(url, target, expectedSha1, progress, progress, followRedirects);
    }

    public static void download(String url, Path target, String expectedSha1, LongConsumer progress,
                                LongConsumer networkProgress, boolean followRedirects)
            throws IOException, InterruptedException {
        if (Files.exists(target) && expectedSha1 != null && !expectedSha1.isBlank()
                && expectedSha1.equalsIgnoreCase(Util.sha1(target))) {
            if (progress != null) progress.accept(Files.size(target));
            return;
        }
        if (Files.exists(target) && (expectedSha1 == null || expectedSha1.isBlank()) && Files.size(target) > 0) {
            if (progress != null) progress.accept(Files.size(target));
            return;
        }
        Files.createDirectories(target.getParent());
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            Path part = target.resolveSibling(target.getFileName() + ".part");
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofMinutes(5))
                        .header("User-Agent", USER_AGENT)
                        .GET().build();
                HttpResponse<InputStream> response = (followRedirects ? CLIENT : NO_REDIRECT)
                        .send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    try (InputStream ignored = response.body()) { }
                    Files.deleteIfExists(part);
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                long total = 0;
                try (InputStream in = response.body();
                     OutputStream out = Files.newOutputStream(part, StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[128 * 1024];
                    long done = 0;
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        out.write(buffer, 0, n);
                        done += n;
                        if (progress != null) progress.accept(done);
                        if (networkProgress != null) networkProgress.accept(done);
                    }
                    total = done;
                }
                if (expectedSha1 != null && !expectedSha1.isBlank()) {
                    String actual = Util.sha1(part);
                    if (!actual.equalsIgnoreCase(expectedSha1)) {
                        Files.deleteIfExists(part);
                        throw new IOException("SHA-1 mismatch for " + target.getFileName()
                                + " (expected " + expectedSha1 + ", got " + actual + ")");
                    }
                }
                try {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
                if (progress != null) progress.accept(total);
                return;
            } catch (IOException ex) {
                last = ex;
                if (attempt < 3) Thread.sleep(700L * attempt);
            }
        }
        throw last == null ? new IOException("Download failed: " + url) : last;
    }

    public static Map<String, String> headers(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
