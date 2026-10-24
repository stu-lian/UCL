package org.unioncraft.launcher;

import java.util.concurrent.TimeUnit;

/** Applies a native Windows title-bar color to the Chromium app window. */
public final class WindowTheme {
    private WindowTheme() {}

    public static void applyAsync(boolean dark) {
        if (!Util.WINDOWS) return;
        Thread thread = new Thread(() -> {
            for (int attempt = 0; attempt < 10; attempt++) {
                if (apply(dark)) return;
                try { Thread.sleep(450); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "ucl-window-theme");
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean apply(boolean dark) {
        String caption = dark ? "0x00352B25" : "0x00FBF6F3";
        String text = dark ? "0x00F4EDE9" : "0x00332720";
        String script = """
                $ErrorActionPreference = 'Stop'
                Add-Type @'
                using System;
                using System.Runtime.InteropServices;
                public class UclWindowTheme {
                  [DllImport("dwmapi.dll")]
                  public static extern int DwmSetWindowAttribute(IntPtr hwnd, int attr, ref int value, int size);
                }
                '@
                $p = Get-Process chrome,msedge,brave -ErrorAction SilentlyContinue |
                     Where-Object { $_.MainWindowTitle -eq 'Union Craft Launcher' } |
                     Select-Object -First 1
                if (-not $p) { exit 3 }
                $h = [IntPtr]$p.MainWindowHandle
                if ($h -eq [IntPtr]::Zero) { exit 3 }
                $dark = %s
                [UclWindowTheme]::DwmSetWindowAttribute($h, 20, [ref]$dark, 4) | Out-Null
                $caption = [int]%s
                $text = [int]%s
                [UclWindowTheme]::DwmSetWindowAttribute($h, 35, [ref]$caption, 4) | Out-Null
                [UclWindowTheme]::DwmSetWindowAttribute($h, 36, [ref]$text, 4) | Out-Null
                exit 0
                """.formatted(dark ? "1" : "0", caption, text);
        java.nio.file.Path file = null;
        try {
            file = java.nio.file.Files.createTempFile("ucl-window-theme", ".ps1");
            java.nio.file.Files.writeString(file, script, java.nio.charset.StandardCharsets.UTF_8);
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", file.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (file != null) {
                try { java.nio.file.Files.deleteIfExists(file); } catch (Exception ignored) { }
            }
        }
    }
}
