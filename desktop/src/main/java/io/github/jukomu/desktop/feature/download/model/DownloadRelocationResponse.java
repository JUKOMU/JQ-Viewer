package io.github.jukomu.desktop.feature.download.model;

public record DownloadRelocationResponse(
        boolean success,
        boolean downloadPublic,
        int moved,
        String displayPath,
        boolean cleanupPending,
        String cleanupMessage
) {
}
