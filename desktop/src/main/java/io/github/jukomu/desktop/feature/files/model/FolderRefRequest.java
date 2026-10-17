package io.github.jukomu.desktop.feature.files.model;

import java.util.List;

public record FolderRefRequest(String folder, List<String> formats) {
}
