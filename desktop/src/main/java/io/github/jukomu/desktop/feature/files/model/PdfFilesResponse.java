package io.github.jukomu.desktop.feature.files.model;

import java.util.List;

public record PdfFilesResponse(List<FileDescriptorResponse> files) {
}
