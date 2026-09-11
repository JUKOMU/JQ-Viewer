package io.github.jukomu.desktop.feature.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.data.DesktopDatabase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 实现 Desktop 浏览历史的去重写入、分页查询和时间分组统计。 */
public final class DesktopHistory {
    private static final List<String> GROUP_KEYS = List.of(
            "today", "yesterday", "thisWeek", "thisMonth",
            "lastThreeMonths", "lastSixMonths", "thisYear", "earlier"
    );

    private final DesktopDatabase database;

    public DesktopHistory(DesktopDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public ObjectNode recordBrowse(ObjectNode request) throws SQLException {
        String albumId = text(request, "albumId").trim();
        if (albumId.isEmpty()) throw new IllegalArgumentException("albumId is required");
        synchronized (database) {
            Connection connection = database.connection();
            boolean autoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                Long latestId = latestMatchingId(connection, albumId);
                if (latestId == null) insert(connection, request, albumId);
                else update(connection, request, latestId);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
        return success();
    }

    public ObjectNode getBrowseHistory(
            int limit,
            int offset,
            Long startInclusive,
            Long endExclusive
    ) throws SQLException {
        if (limit < 0 || offset < 0) {
            throw new IllegalArgumentException("limit and offset must be non-negative");
        }
        Range range = Range.of(startInclusive, endExclusive);
        synchronized (database) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            result.put("totalCount", count(range));
            ArrayNode items = result.putArray("items");
            String sql = """
                    SELECT id, album_id, album_title, cover_url, authors,
                           chapter_id, chapter_title, timestamp
                    FROM browse_history
                    %s
                    ORDER BY timestamp DESC, id DESC
                    %s
                    """.formatted(range.where(), limit > 0 ? "LIMIT ? OFFSET ?" : "");
            try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
                int index = range.bind(statement, 1);
                if (limit > 0) {
                    statement.setInt(index++, limit);
                    statement.setInt(index, offset);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        items.addObject()
                                .put("id", rows.getLong("id"))
                                .put("albumId", rows.getString("album_id"))
                                .put("albumTitle", rows.getString("album_title"))
                                .put("coverUrl", rows.getString("cover_url"))
                                .put("authors", rows.getString("authors"))
                                .put("chapterId", rows.getString("chapter_id"))
                                .put("chapterTitle", rows.getString("chapter_title"))
                                .put("timestamp", rows.getLong("timestamp"));
                    }
                }
            }
            return result;
        }
    }

    public ObjectNode getBrowseHistoryOverview(ArrayNode ranges) throws SQLException {
        if (ranges.size() != GROUP_KEYS.size()) {
            throw new IllegalArgumentException("ranges must contain exactly eight groups");
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        synchronized (database) {
            for (JsonNode value : ranges) {
                if (!(value instanceof ObjectNode rangeNode)) {
                    throw new IllegalArgumentException("each range must be a JSON object");
                }
                String key = text(rangeNode, "key");
                if (!GROUP_KEYS.contains(key) || !seen.add(key)) {
                    throw new IllegalArgumentException("invalid or duplicate browse group key: " + key);
                }
                counts.put(key, count(Range.of(nullableLong(rangeNode, "startInclusive"),
                        nullableLong(rangeNode, "endExclusive"))));
            }
            if (!seen.containsAll(GROUP_KEYS)) {
                throw new IllegalArgumentException("ranges must contain all browse group keys");
            }
            ObjectNode result = JsonNodeFactory.instance.objectNode()
                    .put("totalCount", count(Range.unbounded()));
            ObjectNode groupCounts = result.putObject("groupCounts");
            GROUP_KEYS.forEach(key -> groupCounts.put(key, counts.get(key)));
            return result;
        }
    }

    public ObjectNode clearBrowseHistory() throws SQLException {
        synchronized (database) {
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "DELETE FROM browse_history"
            )) {
                statement.executeUpdate();
            }
        }
        return success();
    }

    public ObjectNode deleteBrowseItem(long id) throws SQLException {
        if (id < 1) throw new IllegalArgumentException("id must be greater than or equal to 1");
        synchronized (database) {
            try (PreparedStatement statement = database.connection().prepareStatement(
                    "DELETE FROM browse_history WHERE id = ?"
            )) {
                statement.setLong(1, id);
                statement.executeUpdate();
            }
        }
        return success();
    }

    private Long latestMatchingId(Connection connection, String albumId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, album_id
                FROM browse_history
                ORDER BY id DESC
                LIMIT 1
                """)) {
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !albumId.equals(row.getString("album_id"))) return null;
                return row.getLong("id");
            }
        }
    }

    private void insert(Connection connection, ObjectNode request, String albumId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO browse_history(
                    album_id, album_title, cover_url, authors,
                    chapter_id, chapter_title, timestamp
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, albumId);
            bindValues(statement, request, 2);
            statement.executeUpdate();
        }
    }

    private void update(Connection connection, ObjectNode request, long id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE browse_history
                SET album_title = ?, cover_url = ?, authors = ?,
                    chapter_id = ?, chapter_title = ?, timestamp = ?
                WHERE id = ?
                """)) {
            bindValues(statement, request, 1);
            statement.setLong(7, id);
            statement.executeUpdate();
        }
    }

    private void bindValues(PreparedStatement statement, ObjectNode request, int offset)
            throws SQLException {
        statement.setString(offset, text(request, "albumTitle"));
        statement.setString(offset + 1, text(request, "coverUrl"));
        statement.setString(offset + 2, text(request, "authors"));
        statement.setString(offset + 3, text(request, "chapterId"));
        statement.setString(offset + 4, text(request, "chapterTitle"));
        statement.setLong(offset + 5, System.currentTimeMillis());
    }

    private long count(Range range) throws SQLException {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "SELECT COUNT(*) FROM browse_history " + range.where()
        )) {
            range.bind(statement, 1);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("count query returned no row");
                return result.getLong(1);
            }
        }
    }

    private static String text(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? "" : value.asText("");
    }

    private static Long nullableLong(ObjectNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber()) {
            throw new IllegalArgumentException(name + " must be an integer or null");
        }
        return value.longValue();
    }

    private static ObjectNode success() {
        return JsonNodeFactory.instance.objectNode().put("success", true);
    }

    private record Range(String where, Long first, Long second) {
        static Range of(Long startInclusive, Long endExclusive) {
            if (startInclusive != null && endExclusive != null && startInclusive >= endExclusive) {
                return new Range("WHERE 1 = 0", null, null);
            }
            if (startInclusive == null && endExclusive == null) return unbounded();
            if (startInclusive == null) return new Range("WHERE timestamp < ?", endExclusive, null);
            if (endExclusive == null) return new Range("WHERE timestamp >= ?", startInclusive, null);
            return new Range("WHERE timestamp >= ? AND timestamp < ?", startInclusive, endExclusive);
        }

        static Range unbounded() {
            return new Range("", null, null);
        }

        int bind(PreparedStatement statement, int index) throws SQLException {
            if (first != null) statement.setLong(index++, first);
            if (second != null) statement.setLong(index++, second);
            return index;
        }
    }
}
