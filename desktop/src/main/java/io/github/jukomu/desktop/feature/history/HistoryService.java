package io.github.jukomu.desktop.feature.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** 提供浏览历史的去重写入、范围查询、分页和清理。 */
public final class HistoryService {
    private static final String[] GROUPS = {
            "today", "yesterday", "thisWeek", "thisMonth",
            "lastThreeMonths", "lastSixMonths", "thisYear", "earlier"
    };

    private final Database database;

    public HistoryService(Database database) {
        this.database = database;
    }

    public synchronized ObjectNode record(ObjectNode request) {
        String albumId = text(request, "albumId");
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
            return success();
        } catch (SQLException exception) {
            throw new IllegalStateException("记录浏览历史失败", exception);
        }
    }

    public synchronized ObjectNode page(int limit, int offset, Long start, Long end) {
        if (limit < 0 || offset < 0) throw ApiException.invalidRequest("分页参数不能为负数");
        String where = rangeWhere(start, end);
        String args = rangeArgs(start, end);
        String sql = "SELECT id, album_id, album_title, cover_url, authors, chapter_id, "
                + "chapter_title, timestamp FROM browse_history " + where
                + " ORDER BY timestamp DESC, id DESC" + (limit > 0 ? " LIMIT ? OFFSET ?" : "");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ArrayNode items = result.putArray("items");
        try (PreparedStatement count = database.connection().prepareStatement(
                "SELECT COUNT(*) FROM browse_history " + where)) {
            bindRange(count, args, start, end, 1);
            try (ResultSet rows = count.executeQuery()) {
                result.put("totalCount", rows.next() ? rows.getLong(1) : 0);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取浏览历史数量失败", exception);
        }
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            int index = bindRange(statement, args, start, end, 1);
            if (limit > 0) {
                statement.setInt(index++, limit);
                statement.setInt(index, offset);
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ObjectNode item = items.addObject();
                    item.put("id", rows.getLong(1));
                    item.put("albumId", rows.getString(2));
                    item.put("albumTitle", rows.getString(3));
                    item.put("coverUrl", rows.getString(4));
                    item.put("authors", rows.getString(5));
                    item.put("chapterId", rows.getString(6));
                    item.put("chapterTitle", rows.getString(7));
                    item.put("timestamp", rows.getLong(8));
                }
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("读取浏览历史失败", exception);
        }
        return result;
    }

    public synchronized ObjectNode overview(ArrayNode ranges) {
        if (ranges.size() != GROUPS.length) {
            throw ApiException.invalidRequest("ranges必须包含八个时间分组");
        }
        Set<String> keys = new HashSet<>();
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        ObjectNode counts = result.putObject("groupCounts");
        long total = page(0, 0, null, null).path("totalCount").asLong();
        for (int index = 0; index < ranges.size(); index++) {
            JsonNode value = ranges.get(index);
            if (!value.isObject()) throw ApiException.invalidRequest("ranges包含无效元素");
            String key = text((ObjectNode) value, "key");
            if (!Set.of(GROUPS).contains(key) || !keys.add(key)) {
                throw ApiException.invalidRequest("ranges包含无效或重复分组");
            }
            Long start = optionalLong((ObjectNode) value, "startInclusive");
            Long end = optionalLong((ObjectNode) value, "endExclusive");
            ObjectNode page = page(0, 0, start, end);
            counts.put(key, page.path("totalCount").asLong());
        }
        for (String group : GROUPS) if (!keys.contains(group)) {
            throw ApiException.invalidRequest("ranges缺少时间分组");
        }
        result.put("totalCount", total);
        return result;
    }

    public synchronized ObjectNode clear() {
        execute("DELETE FROM browse_history");
        return success();
    }

    public synchronized ObjectNode delete(long id) {
        try (PreparedStatement statement = database.connection().prepareStatement(
                "DELETE FROM browse_history WHERE id = ?")) {
            statement.setLong(1, id);
            statement.executeUpdate();
            return success();
        } catch (SQLException exception) {
            throw new IllegalStateException("删除浏览历史失败", exception);
        }
    }

    private void execute(String sql) {
        try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("清理浏览历史失败", exception);
        }
    }

    private static void bindItem(PreparedStatement statement, ObjectNode request, int index) throws SQLException {
        statement.setString(index, text(request, "albumTitle"));
        statement.setString(index + 1, text(request, "coverUrl"));
        statement.setString(index + 2, text(request, "authors"));
        statement.setString(index + 3, text(request, "chapterId"));
        statement.setString(index + 4, text(request, "chapterTitle"));
    }

    private static String text(ObjectNode request, String key) {
        JsonNode value = request.get(key);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual()) throw ApiException.invalidRequest(key + "必须是字符串");
        return value.textValue();
    }

    private static Long optionalLong(ObjectNode request, String key) {
        if (!request.has(key) || request.get(key).isNull()) return null;
        JsonNode value = request.get(key);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw ApiException.invalidRequest(key + "必须是整数");
        }
        return value.longValue();
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

    private static int bindRange(PreparedStatement statement, String args, Long start, Long end, int index)
            throws SQLException {
        if ("start".equals(args)) statement.setLong(index++, start);
        if ("end".equals(args)) statement.setLong(index++, end);
        if ("both".equals(args)) {
            statement.setLong(index++, start);
            statement.setLong(index++, end);
        }
        return index;
    }

    private static ObjectNode success() {
        return JsonNodeFactory.instance.objectNode().put("success", true);
    }
}
