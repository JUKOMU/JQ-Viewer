package io.github.jukomu.feature.pdf.export;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deletes only PDF writer artifacts whose ownership was persisted by PdfStore.
 */
public final class PdfArtifactCleaner {

    private PdfArtifactCleaner() {
    }

    public static void cleanupKnownVolume(JSONObject volume) throws IOException {
        String tempPath = volume.optString("tempPath", "");
        String workPath = volume.optString("workDir", "");
        if (tempPath.isEmpty() || workPath.isEmpty()) {
            throw new IOException("CLEANUP_PATH_UNSAFE: PDF 临时路径记录不完整");
        }
        File tempFile = new File(tempPath);
        File workDirectory = new File(workPath);
        ensureNotSymbolicLink(tempFile.toPath());
        ensureNotSymbolicLink(workDirectory.toPath());
        deleteTreeWithoutFollowingLinks(tempFile.toPath());
        deleteTreeWithoutFollowingLinks(workDirectory.toPath());
    }

    /**
     * 清理 SAF 导出专用 staging 目录，并在删除前统计其中的文件长度。
     * cacheRoot 之外的路径以及 path provider 的最终输出不会进入此方法。
     */
    public static long cleanupStagingDirectory(File cacheRoot, String exportId)
        throws IOException {
        if (cacheRoot == null || exportId == null || exportId.isEmpty()
            || exportId.contains("/") || exportId.contains("\\")) {
            throw new IOException("CLEANUP_PATH_UNSAFE: PDF staging 标识无效");
        }
        File root = cacheRoot.getCanonicalFile();
        File staging = new File(root, exportId).getCanonicalFile();
        if (!staging.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("CLEANUP_PATH_UNSAFE: PDF staging 路径越界");
        }
        Path stagingPath = staging.toPath();
        long bytes = treeSizeWithoutFollowingLinks(stagingPath);
        deleteTreeWithoutFollowingLinks(stagingPath);
        return bytes;
    }

    private static void deleteTreeWithoutFollowingLinks(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        ensureNotSymbolicLink(path);
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                for (Path child : children) deleteTreeWithoutFollowingLinks(child);
            }
        }
        Files.deleteIfExists(path);
    }

    private static long treeSizeWithoutFollowingLinks(Path path) throws IOException {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return 0L;
        ensureNotSymbolicLink(path);
        if (!Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return Files.size(path);
        }

        long total = 0L;
        try (java.nio.file.DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
            for (Path child : children) {
                long childBytes = treeSizeWithoutFollowingLinks(child);
                if (childBytes > 0L && total > Long.MAX_VALUE - childBytes) {
                    total = Long.MAX_VALUE;
                } else {
                    total += childBytes;
                }
            }
        }
        return total;
    }

    private static void ensureNotSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("CLEANUP_PATH_UNSAFE: PDF 临时路径包含符号链接");
        }
    }
}
