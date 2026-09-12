package io.github.jukomu.desktop.feature.history;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.history.model.HistoryItemResponse;
import io.github.jukomu.desktop.feature.history.model.HistoryOverviewRequest;
import io.github.jukomu.desktop.feature.history.model.HistoryOverviewResponse;
import io.github.jukomu.desktop.feature.history.model.HistoryPageResponse;
import io.github.jukomu.desktop.feature.history.model.HistoryRecordRequest;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 提供浏览历史的去重写入、范围查询、分页和清理。 */
public final class HistoryService {
    private static final Set<String> GROUPS = Set.of(
            "today", "yesterday", "thisWeek", "thisMonth",
            "lastThreeMonths", "lastSixMonths", "thisYear", "earlier"
    );

    private final Database database;

    public HistoryService(Database database) {
        this.database = database;
    }

    public synchronized SuccessResponse record(HistoryRecordRequest request) {
        String albumId = text(request.albumId());
        try (PreparedStatement latest = database.connection().prepareStatement(
                "SELECT id, album_id FROM browse_history ORDER BY timestamp DESC, id DESC LIMIT 1")) {
            long id = -1;
            boolean sameAlbum = false;
            try (ResultSet result = latest.executeQuery()) {
                if (result.next()) {
                    id = result.getLong(1);
                    sameAlbum = albumId.equals(result.getString(2));
                }
            }
            if (sameAlbum) {
                try (PreparedStatement update = database.connection().prepareStatement(
                        "UPDATE browse_history SET album_title=?, cover_url=?, authors=?, "
                                + "chapter_id=?, chapter_title=?, timestamp=? WHERE id=?")) {
                    bindItem(update, request, 1);
                    update.setLong(6, System.currentTimeMillis());
                    update.setLong(7, id);
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement insert = database.connection().prepareStatement(
                        "INSERT INTO browse_history(album_id, album_title, cover_url, authors, "
                                + "chapter_id, chapter_title, timestamp) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, albumId);
                    bindItem(insert, request, 2);
                    insert.setLong(7, System.currentTimeMillis());
                    insert.executeUpdate();
                }
            }
            return SuccessResponse.ok();
        } catch (SQLException exception) {
            throw new IllegalStateException("记录浏览历史失败", exception);
        }
    }

    public synchronized HistoryPageResponse page(int limit, int offset, Long start, Long end) {
        if (limit < 0 || offset < 0) throw ApiException.invalidRequest("分页参数不能为负数");
        String where = rangeWhere(start, end);
        String args = rangeArgs(start, end);
        String sql = "SELECT id, album_id, album_title, cover_url, authors, chapter_id, "
                + "chapter_title, timestamp FROM browse_history " + where
                + " ORDER BY timestamp DESC, id DESC" + (limit > 0 ? " LIMIT ? OFFSET ?" : "");
        long totalCount = count(where, args, start, end);
        List<HistoryItemResponse> items = new ArrayList<>();
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            int index = bindRange(statement, args, start, end, 1);
            if (limit > 0) {
                statement.setInt(index++, limit);
                statement.setInt(index, offset);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    items.add(new HistoryItemResponse(
                            rows.getLong(1),
                            rows.getString(2),
                            rows.getString(3),
                            rows.getString(4),
                            rows.getString(5),
                            rows.getString(6),
                            rows.getString(7),
                            rows.getLong(8)
                    ));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取浏览历史失败", exception);
        }
        return new HistoryPageResponse(List.copyOf(items), totalCount);
    }

    public synchronized HistoryOverviewResponse overview(List<HistoryOverviewRequest.Range> ranges) {
        if (ranges == null || ranges.size() != GROUPS.size()) {
            throw ApiException.invalidRequest("ranges必须包含八个时间分组");
        }
        Set<String> keys = new HashSet<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        long total = count("", "", null, null);
        for (HistoryOverviewRequest.Range range : ranges) {
            if (range == null || !GROUPS.contains(range.key()) || !keys.add(range.key())) {
                throw ApiException.invalidRequest("ranges包含无效或重复分组");
            }
            String where = rangeWhere(range.startInclusive(), range.endExclusive());
            String args = rangeArgs(range.startInclusive(), range.endExclusive());
            counts.put(range.key(), count(where, args, range.startInclusive(), range.endExclusive()));
        }
        if (!keys.containsAll(GROUPS)) {
            throw ApiException.invalidRequest("ranges缺少时间分组");
        }
        return new HistoryOverviewResponse(total, Map.copyOf(counts));
    }

    public synchronized SuccessResponse clear() {
        execute("DELETE FROM browse_history");
        return SuccessResponse.ok();
    }

    public synchronized SuccessResponse delete(long id) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "DELETE FROM browse_history WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
            return SuccessResponse.ok();
        } catch (SQLException exception) {
            throw new IllegalStateException("删除浏览历史失败", exception);
        }
    }

    private long count(String where, String args, Long start, Long end) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT COUNT(*) FROM browse_history " + where)) {
            bindRange(statement, args, start, end, 1);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : 0;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取浏览历史数量失败", exception);
        }
    }

    private void execute(String sql) {
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("清理浏览历史失败", exception);
        }
    }

    private static void bindItem(
            PreparedStatement statement,
            HistoryRecordRequest request,
            int index
    ) throws SQLException {
        statement.setString(index, text(request.albumTitle()));
        statement.setString(index + 1, text(request.coverUrl()));
        statement.setString(index + 2, text(request.authors()));
        statement.setString(index + 3, text(request.chapterId()));
        statement.setString(index + 4, text(request.chapterTitle()));
    }

    private static String rangeWhere(Long start, Long end) {
        if (start != null && end != null && start >= end) return " WHERE 1 = 0";
        if (start == null && end == null) return "";
        if (start == null) return " WHERE timestamp < ?";
        if (end == null) return " WHERE timestamp >= ?";
        return " WHERE timestamp >= ? AND timestamp < ?";
    }

    private static String rangeArgs(Long start, Long end) {
        return start == null ? (end == null ? "" : "end") : (end == null ? "start" : "both");
    }

    private static int bindRange(
            PreparedStatement statement,
            String args,
            Long start,
            Long end,
            int index
    ) throws SQLException {
        if ("start".equals(args)) statement.setLong(index++, start);
        if ("end".equals(args)) statement.setLong(index++, end);
        if ("both".equals(args)) {
            statement.setLong(index++, start);
            statement.setLong(index++, end);
        }
        return index;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
