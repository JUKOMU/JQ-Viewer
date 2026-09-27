package io.github.jukomu.desktop.feature.download.model;

public record RelocationProgressEvent(
    int current,
    int total,
    String phase,
    String currentFile
) {
}
