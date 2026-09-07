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

    private static void ensureNotSymbolicLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("CLEANUP_PATH_UNSAFE: PDF 临时路径包含符号链接");
        }
    }
}
