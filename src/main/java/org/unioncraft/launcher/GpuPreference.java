package org.unioncraft.launcher;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Applies the Windows per-application "High performance" GPU preference for the Java runtime. */
public final class GpuPreference {
    private GpuPreference() {}

    public static void apply(Path javaExe, Jobs.Job job) {
        if (!Util.WINDOWS || javaExe == null) return;
        try {
            Process process = new ProcessBuilder("reg", "add",
                    "HKCU\\Software\\Microsoft\\DirectX\\UserGpuPreferences",
                    "/v", javaExe.toAbsolutePath().toString(),
                    "/t", "REG_SZ", "/d", "GpuPreference=2;", "/f")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean done = process.waitFor(4, TimeUnit.SECONDS);
            if (done && process.exitValue() == 0 && job != null) {
                job.log("已请求高性能 GPU · " + javaExe.getFileName());
            }
        } catch (Exception ignored) {
            // The registry preference is optional; the game still starts with the system default GPU.
        }
    }
}
