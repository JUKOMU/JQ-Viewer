package io.github.jukomu.desktop.feature.download.model;

import java.util.List;

public record DownloadResourceKeysRequest(List<Item> items) {
    public record Item(String albumId, String chapterId) {
    }
}
