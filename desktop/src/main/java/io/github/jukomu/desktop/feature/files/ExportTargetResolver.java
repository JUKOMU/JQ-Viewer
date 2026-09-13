package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** 将 FolderRef 与目录内相对路径解析为受约束的本地导出目标。 */
public final class ExportTargetResolver {
    private ExportTargetResolver() {
    }

    public static Path resolve(String folderReference, String relativePath) {
        Path root = FileReferences.parseFolder(folderReference);
        if (relativePath == null || relativePath.isBlank()) {
            throw ApiException.invalidRequest("relativePath不能为空");
        }

        try {
            Path relative = Path.of(relativePath);
            if (relative.isAbsolute()) {
                throw ApiException.invalidRequest("relativePath必须是相对路径");
            }
            Path target = root.resolve(relative).normalize();
            if (target.equals(root) || !target.startsWith(root)) {
                throw ApiException.invalidRequest("导出目标超出所选目录");
            }
            return target;
        } catch (InvalidPathException exception) {
            throw ApiException.invalidRequest("relativePath包含无效路径");
        }
    }
}
