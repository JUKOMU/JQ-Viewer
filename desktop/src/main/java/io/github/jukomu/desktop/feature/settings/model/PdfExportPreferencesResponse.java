package io.github.jukomu.desktop.feature.settings.model;

public record PdfExportPreferencesResponse(
        PdfExportFolder exportFolder,
        String directoryTemplate,
        String fileNameTemplate,
        String lastFormat
) {
}
