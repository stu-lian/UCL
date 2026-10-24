package org.unioncraft.launcher;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory background job tracker consumed by the web UI through polling. */
public final class Jobs {
    public static final ExecutorService POOL = Executors.newFixedThreadPool(8, r -> {
        Thread thread = new Thread(r, "ucl-worker");
        thread.setDaemon(true);
        return thread;
    });

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private Jobs() {}

    public static final class Job {
        private final String id;
        private final String kind;
        private final String label;
        private final long createdAt = Instant.now().toEpochMilli();
        private final Deque<String> logs = new ArrayDeque<>();
        private volatile String status = "running";
        private volatile String stage = "准备中";
        private volatile String message = "";
        private volatile double progress = 0;
        private volatile String error = "";
        private volatile int filesDone = 0;
        private volatile int filesTotal = 0;
        private volatile long bytesDone = 0;
        private volatile long bytesTotal = 0;
        private volatile long bytesPerSecond = 0;
        private volatile Map<String, Object> result = new LinkedHashMap<>();
        private volatile long finishedAt = 0;

        Job(String id, String kind, String label) {
            this.id = id;
            this.kind = kind;
            this.label = label;
        }

        public String id() { return id; }

        public String status() { return status; }

        public synchronized void progress(double value, String stage, String message) {
            this.progress = Math.max(0, Math.min(1, value));
            if (stage != null && !stage.isBlank()) this.stage = stage;
            if (message != null) this.message = message;
        }

        public synchronized void details(int doneFiles, int totalFiles, long doneBytes, long totalBytes, long speed) {
            this.filesDone = Math.max(0, doneFiles);
            this.filesTotal = Math.max(0, totalFiles);
            this.bytesDone = Math.max(0, doneBytes);
            this.bytesTotal = Math.max(0, totalBytes);
            this.bytesPerSecond = Math.max(0, speed);
        }

        public synchronized void log(String line) {
            String stamped = "[" + Util.nowTime() + "] " + line;
            logs.addLast(stamped);
            while (logs.size() > 400) logs.removeFirst();
        }

        public synchronized void complete(Map<String, Object> result) {
            this.result = result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
            this.progress = 1;
            if (filesTotal > 0) filesDone = filesTotal;
            this.bytesPerSecond = 0;
            this.status = "done";
            this.stage = "完成";
            this.finishedAt = Instant.now().toEpochMilli();
        }

        public synchronized void fail(String message) {
            this.error = message == null ? "未知错误" : message;
            this.status = "error";
            this.stage = "失败";
            this.finishedAt = Instant.now().toEpochMilli();
        }

        public synchronized Map<String, Object> json() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("kind", kind);
            map.put("label", label);
            map.put("status", status);
            map.put("stage", stage);
            map.put("message", message);
            map.put("progress", progress);
            map.put("filesDone", filesDone);
            map.put("filesTotal", filesTotal);
            map.put("bytesDone", bytesDone);
            map.put("bytesTotal", bytesTotal);
            map.put("bytesPerSecond", bytesPerSecond);
            map.put("error", error);
            map.put("result", result);
            map.put("createdAt", createdAt);
            map.put("finishedAt", finishedAt);
            map.put("logs", new ArrayList<>(logs));
            return map;
        }
    }

    public static Job create(String kind, String label) {
        String id = "job_" + SEQ.incrementAndGet() + "_" + Integer.toHexString((int) (System.nanoTime() & 0xffffff));
        Job job = new Job(id, kind, label);
        JOBS.put(id, job);
        trim();
        return job;
    }

    public static Job get(String id) {
        return id == null ? null : JOBS.get(id);
    }

    public static List<Map<String, Object>> active() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Job job : JOBS.values()) {
            if ("running".equals(job.status())) out.add(job.json());
        }
        return out;
    }

    private static void trim() {
        if (JOBS.size() <= 80) return;
        List<Map.Entry<String, Job>> entries = new ArrayList<>(JOBS.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().createdAt, b.getValue().createdAt));
        for (int i = 0; i < entries.size() - 60; i++) JOBS.remove(entries.get(i).getKey());
    }

    public static Job async(String kind, String label, ThrowingConsumer<Job> work) {
        Job job = create(kind, label);
        POOL.submit(() -> {
            try {
                work.accept(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                job.fail("任务已取消");
            } catch (Exception e) {
                job.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        });
        return job;
    }

    @FunctionalInterface
    public interface ThrowingConsumer<T> {
        void accept(T value) throws Exception;
    }
}
