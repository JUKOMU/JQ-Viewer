package io.github.jukomu.desktop.bridge.model;

import java.util.List;

public record LogExportRequest(String folder, List<String> fileNames) {
}
