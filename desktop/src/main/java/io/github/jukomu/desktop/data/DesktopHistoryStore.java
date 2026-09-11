package io.github.jukomu.desktop.data;

import io.github.jukomu.desktop.dto.DesktopDtos;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Desktop 浏览历史的 SQLite 读写与事务边界。 */
public final class DesktopHistoryStore {
    private static final String[] BROWSE_GROUP_KEYS = {
            "today", "yesterday", "thisWeek", "thisMonth",
            "lastThreeMonths", "lastSixMonths", "thisYear", "earlier"
    };

    private final DesktopDatabase database;

    public DesktopHistoryStore(DesktopDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * 复用 Android 当前行为：只把最近一条相同专辑的记录更新到最前，避免连续阅读同一本子产生重复卡片。
     */
    public void recordBrowse(DesktopDtos.RecordBrowseRequest request) throws SQLException {
        synchronized (database) {
            var connection = database.connection();
            boolean autoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                Long latestId = null;
                try (PreparedStatement statement = connection.prepareStatement("""
                        SELECT id
                        FROM browse_history
                        ORDER BY id DESC
                        LIMIT 1
                        """)) {
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            latestId = result.getLong(1);
                        }
                    }
                }

                boolean updated = false;
                if (latestId != null) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "SELECT album_id FROM browse_history WHERE id = ?")) {
                        statement.setLong(1, latestId);
                        try (ResultSet result = statement.executeQuery()) {
                            if (result.next() && request.albumId().equals(result.getString(1))) {
                                try (PreparedStatement update = connection.prepareStatement("""
                                        UPDATE browse_history
                                        SET album_title = ?, cover_url = ?, authors = ?,
                                            chapter_id = ?, chapter_title = ?, timestamp = ?
                                        WHERE id = ?
                                        """)) {
                                    bindBrowseValues(update, request);
                                    update.setLong(7, latestId);
                                    update.executeUpdate();
                                }
                                updated = true;
                            }
                        }
                    }
                }

                if (!updated) {
                    try (PreparedStatement statement = connection.prepareStatement("""
                            INSERT INTO browse_history(
                                album_id, album_title, cover_url, authors,
                                chapter_id, chapter_title, timestamp
                            ) VALUES (?, ?, ?, ?, ?, ?, ?)
                            """)) {
                        statement.setString(1, request.albumId());
                        bindBrowseValues(statement, request, 2);
                        statement.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    public DesktopDtos.HistoryPage getBrowseHistory(
            int limit,
            int offset,
            Long startInclusive,
            Long endExclusive
    ) throws SQLException {
        validatePagination(limit, offset);
        synchronized (database) {
            String where = rangeWhere(startInclusive, endExclusive);
            List<String> args = rangeArgs(startInclusive, endExclusive);
            long totalCount = count("browse_history", where, args);
            String sql = """
                    SELECT id, album_id, album_title, cover_url, authors,
                           chapter_id, chapter_title, timestamp
                    FROM browse_history
                    %s
                    ORDER BY timestamp DESC, id DESC
                    """.formatted(where);
            if (limit > 0) {
                sql += " LIMIT ? OFFSET ?";
            }

            List<DesktopDtos.BrowseHistoryItem> items = new ArrayList<>();
            try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
                int index = bindArgs(statement, args, 1);
                if (limit > 0) {
                    statement.setInt(index++, limit);
                    statement.setInt(index, offset);
                }
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        items.add(new DesktopDtos.BrowseHistoryItem(
                                result.getLong("id"),
                                result.getString("album_id"),
                                result.getString("album_title"),
                                result.getString("cover_url"),
                                result.getString("authors"),
                                result.getString("chapter_id"),
                                result.getString("chapter_title"),
                                result.getLong("timestamp")
                        ));
                    }
                }
            }
            return new DesktopDtos.HistoryPage(items, totalCount);
        }
    }

    public DesktopDtos.BrowseHistoryOverview getBrowseHistoryOverview(
            List<DesktopDtos.BrowseHistoryRange> ranges
    ) throws SQLException {
        Objects.requireNonNull(ranges, "ranges");
        if (ranges.size() != BROWSE_GROUP_KEYS.length) {
            throw new IllegalArgumentException("ranges must contain exactly eight groups");
        }

        java.util.LinkedHashMap<String, Long> groupCounts = new java.util.LinkedHashMap<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        synchronized (database) {
            for (DesktopDtos.BrowseHistoryRange range : ranges) {
                validateRangeKey(range.key());
                if (!seen.add(range.key())) {
                    throw new IllegalArgumentException(
                            "invalid or duplicate browse group key: " + range.key());
                }
                groupCounts.put(
                        range.key(),
                        count("browse_history", rangeWhere(range.startInclusive(), range.endExclusive()),
                                rangeArgs(range.startInclusive(), range.endExclusive()))
                );
            }
            for (String key : BROWSE_GROUP_KEYS) {
                if (!seen.contains(key)) {
                    throw new IllegalArgumentException("missing browse group key: " + key);
                }
            }
            return new DesktopDtos.BrowseHistoryOverview(
                    count("browse_history", null, List.of()),
                    groupCounts
            );
        }
    }

    public void clearBrowseHistory() throws SQLException {
        synchronized (database) {
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "DELETE FROM browse_history")) {
                statement.executeUpdate();
            }
        }
    }

    public void deleteBrowseItem(long id) throws SQLException {
        synchronized (database) {
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "DELETE FROM browse_history WHERE id = ?")) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
        }
    }

    private long count(String table, String where, List<String> args) throws SQLException {
        String sql = "SELECT COUNT(*) FROM " + table + (where == null ? "" : " " + where);
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            bindArgs(statement, args, 1);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("count query returned no row");
                }
                return result.getLong(1);
            }
        }
    }

    private static void bindBrowseValues(
            PreparedStatement statement,
            DesktopDtos.RecordBrowseRequest request
    ) throws SQLException {
        bindBrowseValues(statement, request, 1);
    }

    private static void bindBrowseValues(
            PreparedStatement statement,
            DesktopDtos.RecordBrowseRequest request,
            int offset
    ) throws SQLException {
        statement.setString(offset, request.albumTitle());
        statement.setString(offset + 1, request.coverUrl());
        statement.setString(offset + 2, request.authors());
        statement.setString(offset + 3, request.chapterId());
        statement.setString(offset + 4, request.chapterTitle());
        statement.setLong(offset + 5, System.currentTimeMillis());
    }

    private static String rangeWhere(Long startInclusive, Long endExclusive) {
        if (startInclusive != null && endExclusive != null && startInclusive >= endExclusive) {
            return "WHERE 1 = 0";
        }
        if (startInclusive == null && endExclusive == null) {
            return "";
        }
        if (startInclusive == null) {
            return "WHERE timestamp < ?";
        }
        if (endExclusive == null) {
            return "WHERE timestamp >= ?";
        }
        return "WHERE timestamp >= ? AND timestamp < ?";
    }

    private static List<String> rangeArgs(Long startInclusive, Long endExclusive) {
        if (startInclusive != null && endExclusive != null && startInclusive >= endExclusive) {
            return List.of();
        }
        if (startInclusive == null && endExclusive == null) {
            return List.of();
        }
        if (startInclusive == null) {
            return List.of(String.valueOf(endExclusive));
        }
        if (endExclusive == null) {
            return List.of(String.valueOf(startInclusive));
        }
        return List.of(String.valueOf(startInclusive), String.valueOf(endExclusive));
    }

    private static int bindArgs(PreparedStatement statement, List<String> args, int start)
            throws SQLException {
        int index = start;
        for (String arg : args) {
            statement.setString(index++, arg);
        }
        return index;
    }

    private static void validatePagination(int limit, int offset) {
        if (limit < 0 || offset < 0) {
            throw new IllegalArgumentException("limit and offset must be non-negative");
        }
    }

    private static void validateRangeKey(String key) {
        for (String validKey : BROWSE_GROUP_KEYS) {
            if (validKey.equals(key)) {
                return;
            }
        }
        throw new IllegalArgumentException("invalid browse group key: " + key);
    }
}
