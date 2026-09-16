package io.github.jukomu.desktop.feature.files;

import com.formdev.flatlaf.util.SystemFileChooser;
import io.github.jukomu.desktop.bridge.ApiException;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** 使用操作系统原生文件对话框选择目录。 */
public final class SystemFolderPicker implements FolderPicker {
    @Override
    public Path pick(Path initialDirectory) {
        if (GraphicsEnvironment.isHeadless()) {
            throw ApiException.unavailable("当前环境无法打开目录选择器");
        }

        AtomicReference<Path> selected = new AtomicReference<>();
        Runnable choose = () -> {
            SystemFileChooser chooser = new SystemFileChooser(initialDirectory.toFile());
            chooser.setDialogTitle("选择文件夹");
            chooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(null) == SystemFileChooser.APPROVE_OPTION) {
                selected.set(chooser.getSelectedFile().toPath().toAbsolutePath().normalize());
            }
        };

        if (SwingUtilities.isEventDispatchThread()) {
            choose.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(choose);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw ApiException.cancelled("目录选择已取消");
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtimeException) throw runtimeException;
                throw ApiException.unavailable("无法打开目录选择器");
            }
        }
        return selected.get();
    }
}
