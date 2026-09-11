package io.github.jukomu.desktop.service;

import io.github.jukomu.desktop.data.DesktopHistoryStore;
import io.github.jukomu.desktop.dto.DesktopDtos;

import java.sql.SQLException;
import java.util.Objects;

/** Desktop 浏览历史业务入口，保持分页、范围和去重语义集中在本端。 */
public final class DesktopHistoryService {
    private final DesktopHistoryStore store;

    public DesktopHistoryService(DesktopHistoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public DesktopDtos.HistoryPage getBrowseHistory(
            int limit,
            int offset,
            Long startInclusive,
            Long endExclusive
    ) throws SQLException {
        return store.getBrowseHistory(limit, offset, startInclusive, endExclusive);
    }

    public DesktopDtos.BrowseHistoryOverview getBrowseHistoryOverview(
            java.util.List<DesktopDtos.BrowseHistoryRange> ranges
    ) throws SQLException {
        if (ranges == null) {
            throw new IllegalArgumentException("ranges is required");
        }
        return store.getBrowseHistoryOverview(ranges);
    }

    public DesktopDtos.Success recordBrowse(DesktopDtos.RecordBrowseRequest request) throws SQLException {
        if (request == null || text(request.albumId()).isEmpty()) {
            throw new IllegalArgumentException("albumId is required");
        }
        store.recordBrowse(new DesktopDtos.RecordBrowseRequest(
                text(request.albumId()),
                text(request.albumTitle()),
                text(request.coverUrl()),
                text(request.authors()),
                text(request.chapterId()),
                text(request.chapterTitle())
        ));
        return new DesktopDtos.Success(true);
    }

    public DesktopDtos.Success clearBrowseHistory() throws SQLException {
        store.clearBrowseHistory();
        return new DesktopDtos.Success(true);
    }

    public DesktopDtos.Success deleteBrowseItem(long id) throws SQLException {
        if (id < 1) {
            throw new IllegalArgumentException("id must be greater than or equal to 1");
        }
        store.deleteBrowseItem(id);
        return new DesktopDtos.Success(true);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
