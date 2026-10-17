package io.github.jukomu.desktop.feature.settings.model;

public record ExportPreferencesResponse(
    ExportFolder exportFolder,
    String directoryTemplate,
    String fileNameTemplate,
    String lastFormat
) {
}
