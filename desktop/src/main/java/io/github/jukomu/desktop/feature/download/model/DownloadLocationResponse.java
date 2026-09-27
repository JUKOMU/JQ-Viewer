package io.github.jukomu.desktop.feature.download.model;

public record DownloadLocationResponse(
    boolean downloadPublic,
    String displayPath,
    boolean cleanupPending,
    String cleanupMessage
) {
}
