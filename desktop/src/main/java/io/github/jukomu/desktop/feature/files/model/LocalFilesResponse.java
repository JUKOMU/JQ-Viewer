package io.github.jukomu.desktop.feature.files.model;

import java.util.List;

public record LocalFilesResponse(List<FileDescriptorResponse> files) {
}
