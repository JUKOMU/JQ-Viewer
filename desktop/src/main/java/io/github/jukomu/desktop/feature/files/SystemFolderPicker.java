package io.github.jukomu.desktop.feature.files;

import com.formdev.flatlaf.util.SystemFileChooser;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.dialog.DesktopFileDialogHost;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;

/** 使用操作系统原生文件对话框选择目录。 */
public final class SystemFolderPicker implements FolderPicker {
    private final DesktopFileDialogHost dialogHost = DesktopFileDialogHost.shared();

    @Override
    public Path pick(Path initialDirectory) {
        if (GraphicsEnvironment.isHeadless()) {
            throw ApiException.unavailable("当前环境无法打开目录选择器");
        }

        SystemFileChooser chooser = new SystemFileChooser(initialDirectory.toFile());
        chooser.setDialogTitle("选择文件夹");
        chooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        try {
            if (dialogHost.showOpenDialog(chooser) != SystemFileChooser.APPROVE_OPTION) return null;
            return chooser.getSelectedFile().toPath().toAbsolutePath().normalize();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ApiException.cancelled("目录选择已取消");
        }
    }
}
