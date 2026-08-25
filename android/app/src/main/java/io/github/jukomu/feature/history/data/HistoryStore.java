package io.github.jukomu.feature.history.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 历史记录 SQLite 数据库。
 * <p>
 * 两张表：browse_history（浏览历史）、parse_history（解析历史）。
 * 均为追加写入、无上限。
 */
public class HistoryStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "jq_history.db";
    private static final int DB_VERSION = 2;

    private static final String TABLE_BROWSE = "browse_history";
    private static final String COL_ID = "id";
    private static final String COL_ALBUM_ID = "album_id";
    private static final String COL_ALBUM_TITLE = "album_title";
    private static final String COL_COVER_URL = "cover_url";
    private static final String COL_AUTHORS = "authors";
    private static final String COL_CHAPTER_ID = "chapter_id";
    private static final String COL_CHAPTER_TITLE = "chapter_title";
    private static final String COL_TIMESTAMP = "timestamp";

    private static final String TABLE_PARSE = "parse_history";
    private static final String COL_TEXT = "text";
    private static final String COL_MODE = "mode";

    private static HistoryStore instance;

    public static synchronized HistoryStore getInstance(Context context) {
        if (instance == null) {
            instance = new HistoryStore(context.getApplicationContext());
        }
        return instance;
    }

    private HistoryStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_BROWSE + " ("
            + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + COL_ALBUM_ID + " TEXT NOT NULL,"
            + COL_ALBUM_TITLE + " TEXT NOT NULL,"
            + COL_COVER_URL + " TEXT NOT NULL DEFAULT '',"
            + COL_AUTHORS + " TEXT NOT NULL DEFAULT '',"
            + COL_CHAPTER_ID + " TEXT NOT NULL DEFAULT '',"
            + COL_CHAPTER_TITLE + " TEXT NOT NULL DEFAULT '',"
            + COL_TIMESTAMP + " INTEGER NOT NULL"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_PARSE + " ("
            + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + COL_TEXT + " TEXT NOT NULL,"
            + COL_TIMESTAMP + " INTEGER NOT NULL,"
            + COL_MODE + " TEXT NOT NULL DEFAULT 'single-mode'"
            + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_PARSE + " ADD COLUMN " + COL_MODE + " TEXT NOT NULL DEFAULT 'single-mode'");
        }
    }

    // ==================== 浏览历史 ====================

    /**
     * 条件去重写入：最新记录相同 albumId → 更新全部字段，否则新增。
     */
    public boolean recordBrowse(String albumId, String albumTitle, String coverUrl,
                                String authors, String chapterId, String chapterTitle) {
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();

        Cursor c = null;
        db.beginTransaction();
        try {
            c = db.query(TABLE_BROWSE, new String[]{COL_ID, COL_ALBUM_ID},
                null, null, null, null, COL_ID + " DESC", "1");
            boolean matched = false;
            if (c.moveToFirst()) {
                String lastAlbumId = c.getString(1);
                if (albumId.equals(lastAlbumId)) {
                    ContentValues cv = new ContentValues();
                    cv.put(COL_ALBUM_TITLE, albumTitle != null ? albumTitle : "");
                    cv.put(COL_COVER_URL, coverUrl != null ? coverUrl : "");
                    cv.put(COL_AUTHORS, authors != null ? authors : "");
                    cv.put(COL_CHAPTER_ID, chapterId != null ? chapterId : "");
                    cv.put(COL_CHAPTER_TITLE, chapterTitle != null ? chapterTitle : "");
                    cv.put(COL_TIMESTAMP, now);
                    db.update(TABLE_BROWSE, cv, COL_ID + "=?", new String[]{String.valueOf(c.getLong(0))});
                    matched = true;
                }
            }

            if (!matched) {
                ContentValues cv = new ContentValues();
                cv.put(COL_ALBUM_ID, albumId);
                cv.put(COL_ALBUM_TITLE, albumTitle != null ? albumTitle : "");
                cv.put(COL_COVER_URL, coverUrl != null ? coverUrl : "");
                cv.put(COL_AUTHORS, authors != null ? authors : "");
                cv.put(COL_CHAPTER_ID, chapterId != null ? chapterId : "");
                cv.put(COL_CHAPTER_TITLE, chapterTitle != null ? chapterTitle : "");
                cv.put(COL_TIMESTAMP, now);
                db.insert(TABLE_BROWSE, null, cv);
            }
            db.setTransactionSuccessful();
            return !matched;
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "recordBrowse failed", e);
            return false;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }

    /**
     * 获取浏览历史（按 timestamp DESC），支持 offset/limit 分页，并返回全量记录数。
     */
    public JSONObject getBrowseHistory(int limit, int offset) {
        JSONObject result = new JSONObject();
        JSONArray arr = new JSONArray();
        Cursor dataCursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            long totalCount = countRows(TABLE_BROWSE);
            String limitClause = limit > 0
                ? (offset > 0 ? offset + "," + limit : String.valueOf(limit))
                : null;
            dataCursor = db.query(TABLE_BROWSE,
                new String[]{COL_ID, COL_ALBUM_ID, COL_ALBUM_TITLE, COL_COVER_URL, COL_AUTHORS,
                    COL_CHAPTER_ID, COL_CHAPTER_TITLE, COL_TIMESTAMP},
                null, null, null, null,
                COL_TIMESTAMP + " DESC",
                limitClause);
            while (dataCursor.moveToNext()) {
                JSONObject obj = new JSONObject();
                obj.put("id", dataCursor.getLong(0));
                obj.put("albumId", dataCursor.getString(1));
                obj.put("albumTitle", dataCursor.getString(2));
                obj.put("coverUrl", dataCursor.getString(3));
                obj.put("authors", dataCursor.getString(4));
                obj.put("chapterId", dataCursor.getString(5));
                obj.put("chapterTitle", dataCursor.getString(6));
                obj.put("timestamp", dataCursor.getLong(7));
                arr.put(obj);
            }
            result.put("items", arr);
            result.put("totalCount", totalCount);
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "getBrowseHistory failed", e);
            try {
                result.put("items", new JSONArray());
                result.put("totalCount", 0L);
            } catch (Exception ignored) {
                android.util.Log.w("HistoryStore", "构建浏览历史默认返回值失败", ignored);
            }
        } finally {
            if (dataCursor != null) dataCursor.close();
        }
        return result;
    }

    /**
     * 清除全部浏览历史
     */
    public void clearBrowseHistory() {
        try {
            getWritableDatabase().delete(TABLE_BROWSE, null, null);
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "clearBrowseHistory failed", e);
        }
    }

    public void deleteBrowseItem(int id) {
        try {
            getWritableDatabase().delete(TABLE_BROWSE, COL_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "deleteBrowseItem failed", e);
        }
    }

    // ==================== 解析历史 ====================

    /**
     * 去重追加：相同 text 存在则移除旧记录再插入新记录
     */
    public void addParseHistory(String text, String mode) {
        String t = text != null ? text.trim() : "";
        if (t.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        long now = System.currentTimeMillis();

        db.beginTransaction();
        try {
            db.delete(TABLE_PARSE, COL_TEXT + "=? COLLATE NOCASE", new String[]{t});
            ContentValues cv = new ContentValues();
            cv.put(COL_TEXT, t);
            cv.put(COL_TIMESTAMP, now);
            cv.put(COL_MODE, mode != null ? mode : "single-mode");
            db.insert(TABLE_PARSE, null, cv);
            db.setTransactionSuccessful();
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "addParseHistory failed", e);
        } finally {
            db.endTransaction();
        }
    }

    public JSONObject getParseHistory(int limit, int offset) {
        JSONObject result = new JSONObject();
        JSONArray arr = new JSONArray();
        Cursor dataCursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            long totalCount = countRows(TABLE_PARSE);
            String limitClause = limit > 0
                ? (offset > 0 ? offset + "," + limit : String.valueOf(limit))
                : null;
            dataCursor = db.query(TABLE_PARSE,
                new String[]{COL_ID, COL_TEXT, COL_TIMESTAMP, COL_MODE},
                null, null, null, null,
                COL_TIMESTAMP + " DESC",
                limitClause);
            while (dataCursor.moveToNext()) {
                JSONObject obj = new JSONObject();
                obj.put("id", dataCursor.getLong(0));
                obj.put("text", dataCursor.getString(1));
                obj.put("timestamp", dataCursor.getLong(2));
                obj.put("mode", dataCursor.getString(3));
                arr.put(obj);
            }
            result.put("items", arr);
            result.put("totalCount", totalCount);
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "getParseHistory failed", e);
            try {
                result.put("items", new JSONArray());
                result.put("totalCount", 0L);
            } catch (Exception ignored) {
                android.util.Log.w("HistoryStore", "构建解析历史默认返回值失败", ignored);
            }
        } finally {
            if (dataCursor != null) dataCursor.close();
        }
        return result;
    }

    public void clearParseHistory() {
        try {
            getWritableDatabase().delete(TABLE_PARSE, null, null);
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "clearParseHistory failed", e);
        }
    }

    public void deleteParseItem(int id) {
        try {
            getWritableDatabase().delete(TABLE_PARSE, COL_ID + "=?", new String[]{String.valueOf(id)});
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "deleteParseItem failed", e);
        }
    }

    private long countRows(String table) {
        try (Cursor cursor = getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM " + table, null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0L;
        } catch (Exception e) {
            android.util.Log.w("HistoryStore", "countRows failed for " + table, e);
            return 0L;
        }
    }
}
