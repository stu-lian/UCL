package org.unioncraft.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Native folder picker for the local Web UI, backed by the Windows Forms dialog. */
public final class FolderPicker {
    private FolderPicker() {}

    public static synchronized String pick(String title) {
        if (!Util.WINDOWS) return "";
        String safeTitle = title == null ? "选择文件夹" : title.replace("'", "''");
        String script = "$ErrorActionPreference='Stop';"
                + "Add-Type -AssemblyName System.Windows.Forms;"
                + "$d=New-Object System.Windows.Forms.FolderBrowserDialog;"
                + "$d.Description='" + safeTitle + "';"
                + "$d.ShowNewFolderButton=$true;"
                + "if($d.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK){[Console]::Out.Write($d.SelectedPath)}";
        try {
            Process process = new ProcessBuilder("powershell", "-NoProfile", "-STA", "-Command", script)
                    .redirectErrorStream(true).start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line);
            }
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                return "";
            }
            return output.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
