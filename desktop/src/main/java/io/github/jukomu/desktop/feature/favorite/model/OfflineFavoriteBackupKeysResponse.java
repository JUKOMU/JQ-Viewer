package io.github.jukomu.desktop.feature.favorite.model;

import java.util.List;

/** 返回按最近保存时间倒序排列的备份 key。 */
public record OfflineFavoriteBackupKeysResponse(List<String> keys) {
}
