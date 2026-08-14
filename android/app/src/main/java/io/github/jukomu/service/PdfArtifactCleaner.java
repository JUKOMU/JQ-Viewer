package io.github.jukomu.service;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deletes only PDF writer artifacts whose ownership was persisted by PdfStore. */
public final class PdfArtifactCleaner {

    private PdfArtifactCleaner() {
    }

    public static void cleanupKnownVolume(JSONObject volume) throws IOException {
        String finalPath = volume.optString("finalPath", "");
        String tempPath = volume.optString("tempPath", "");
        String workPath = volume.optString("workDir", "");
        if (finalPath.isEmpty() || tempPath.isEmpty() || workPath.isEmpty()) {
            throw new IOException("CLEANUP_PATH_UNSAFE: PDF 临时路径记录不完整");
        }
        File finalFile = new File(finalPath);
        File tempFile = new File(tempPath);
        File workDirectory = new File(workPath);
        String expectedTemp = PdfBoxExportWriter.getTempFile(finalFile).getCanonicalPath();
        String expectedWork = PdfBoxExportWriter.getWorkDirectory(finalFile).getCanonicalPath();
        if (!expectedTemp.equals(tempFile.getCanonicalPath())
                || !expectedWork.equals(workDirectory.getCanonicalPath())) {
            throw new IOException("CLEANUP_PATH_UNSAFE: PDF 临时路径不匹配");
        }
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
