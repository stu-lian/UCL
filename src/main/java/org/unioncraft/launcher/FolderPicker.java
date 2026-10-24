package org.unioncraft.launcher;

import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;

/** Native folder picker for the local Web UI. */
public final class FolderPicker {
    private FolderPicker() {}

    public static synchronized String pick(String title) {
        return Util.WINDOWS ? pickWindows(title) : pickUnix(title);
    }

    private static String pickWindows(String title) {
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

    private static String pickUnix(String title) {
        String safe = title == null ? "选择文件夹" : title;
        String result = command("zenity", "--file-selection", "--directory", "--title=" + safe);
        if (!result.isBlank()) return result;
        result = command("kdialog", "--getexistingdirectory", Path.of(System.getProperty("user.home")).toString(),
                "--title", safe);
        if (!result.isBlank()) return result;
        if (GraphicsEnvironment.isHeadless()) return "";
        final String[] selected = {""};
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle(safe);
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                chooser.setAcceptAllFileFilterUsed(false);
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    selected[0] = chooser.getSelectedFile().getAbsolutePath();
                }
            });
        } catch (Exception ignored) { }
        return selected[0];
    }

    private static String command(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
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
            return process.exitValue() == 0 ? output.toString().trim() : "";
        } catch (Exception e) {
            return "";
        }
    }
}
