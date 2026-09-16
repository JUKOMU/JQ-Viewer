package io.github.jukomu.desktop.feature.ocr;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.formdev.flatlaf.util.SystemFileChooser.FileNameExtensionFilter;
import io.github.jukomu.desktop.bridge.ApiException;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** 使用操作系统原生文件对话框选择待识别图片。 */
public final class SystemImagePicker implements ImagePicker {
    private static final FileNameExtensionFilter IMAGE_FILTER =
            new FileNameExtensionFilter(
                    "图片文件 (PNG, JPG, GIF, BMP, TIFF, WEBP)",
                    "png", "jpg", "jpeg", "gif", "bmp", "tif", "tiff", "webp");

    @Override
    public Path pickImage() {
        if (GraphicsEnvironment.isHeadless()) {
            throw ApiException.unavailable("当前环境不支持图片选择器");
        }

        AtomicReference<Path> selected = new AtomicReference<>();
        Runnable openChooser = () -> {
            SystemFileChooser chooser = new SystemFileChooser();
            chooser.setDialogTitle("选择要识别的图片");
            chooser.setFileSelectionMode(SystemFileChooser.FILES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            chooser.addChoosableFileFilter(IMAGE_FILTER);
            if (chooser.showOpenDialog(null) == SystemFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                if (file != null) selected.set(file.toPath().toAbsolutePath().normalize());
            }
        };

        try {
            if (SwingUtilities.isEventDispatchThread()) {
                openChooser.run();
            } else {
                SwingUtilities.invokeAndWait(openChooser);
            }
            return selected.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.cancelled("图片选择已取消");
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw ApiException.unavailable("无法打开图片选择器");
        }
    }
}
