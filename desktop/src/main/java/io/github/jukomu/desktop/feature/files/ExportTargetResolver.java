package io.github.jukomu.desktop.feature.files;

import io.github.jukomu.desktop.bridge.ApiException;

import java.io.IOException;
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
            Path canonicalRoot = root.toFile().getCanonicalFile().toPath();
            Path target = canonicalRoot.resolve(relative).toFile().getCanonicalFile().toPath();
            if (target.equals(canonicalRoot) || !target.startsWith(canonicalRoot)) {
                throw ApiException.invalidRequest("导出目标超出所选目录");
            }
            return target;
        } catch (InvalidPathException exception) {
            throw ApiException.invalidRequest("relativePath包含无效路径");
        } catch (IOException exception) {
            throw ApiException.invalidRequest("导出目标路径无法解析");
        }
    }
}
