package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.JsonObject;

import javax.swing.JFileChooser;
import java.awt.GraphicsEnvironment;
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
}
