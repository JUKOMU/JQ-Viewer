package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;

import javax.swing.JFileChooser;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** 使用 JDK 自带 Swing 调用系统目录选择器。 */
public final class SwingFolderPicker implements FolderPicker {
    @Override
    public Path pick(Path initialDirectory) {
        if (GraphicsEnvironment.isHeadless()) {
            throw ApiException.unavailable("当前环境无法打开目录选择器");
        }

        AtomicReference<Path> selected = new AtomicReference<>();
        Runnable choose = () -> {
            JFileChooser chooser = new JFileChooser(initialDirectory.toFile());
            chooser.setDialogTitle("选择文件夹");
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setAcceptAllFileFilterUsed(false);
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                selected.set(chooser.getSelectedFile().toPath());
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
                throw ApiException.unavailable("无法打开目录选择器");
            }
        }
        return selected.get();
    }
}
