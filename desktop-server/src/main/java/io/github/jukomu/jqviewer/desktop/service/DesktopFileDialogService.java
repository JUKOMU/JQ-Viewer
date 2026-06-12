package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.JsonObject;

import javax.swing.JFileChooser;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopFileDialogService {
    public JsonObject pickDirectory(Path initialDirectory) {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Desktop directory picker is not available in headless mode");
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.setDialogTitle("选择文件夹");
        if (initialDirectory != null) {
            chooser.setCurrentDirectory(initialDirectory.toFile());
        }

        JsonObject result = new JsonObject();
        int choice = chooser.showOpenDialog(null);
        if (choice != JFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            result.addProperty("cancelled", true);
            return result;
        }

        result.addProperty("cancelled", false);
        result.addProperty("path", chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
        return result;
    }

    public JsonObject openDirectory(Path directory) throws IOException {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            throw new IllegalStateException("Desktop directory opener is not available in this environment");
        }
        Path normalized = directory.toAbsolutePath().normalize();
        Files.createDirectories(normalized);
        Desktop.getDesktop().open(normalized.toFile());

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("path", normalized.toString());
        return result;
    }
}
