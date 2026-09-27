package io.github.jukomu.desktop.feature.ocr;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.dialog.DesktopFileDialogHost;

import java.awt.*;
import java.io.File;
import java.nio.file.Path;

/**
 * 使用操作系统原生文件对话框选择待识别图片。
 */
public final class SystemImagePicker implements ImagePicker {
    private static final FileNameExtensionFilter IMAGE_FILTER =
        new FileNameExtensionFilter(
            "图片文件 (PNG, JPG, GIF, BMP, TIFF, WEBP)",
            "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp");
    private final DesktopFileDialogHost dialogHost = DesktopFileDialogHost.shared();

    @Override
    public Path pickImage() {
        if (GraphicsEnvironment.isHeadless()) {
            throw ApiException.unavailable("当前环境不支持图片选择器");
        }

        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle("选择要识别的图片");
        chooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        chooser.addChoosableFileFilter(IMAGE_FILTER);
        try {
            if (dialogHost.showOpenDialog(chooser) != SystemFileChooser.APPROVE_OPTION) return null;
            File file = chooser.getSelectedFile();
            return file == null ? null : file.toPath().toAbsolutePath().normalize();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.cancelled("图片选择已取消");
        }
    }
}
