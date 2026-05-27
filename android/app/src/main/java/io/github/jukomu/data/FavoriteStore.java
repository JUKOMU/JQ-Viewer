package io.github.jukomu.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 离线收藏夹 SQLite 数据库。
 * 两张表：offline_folders（文件夹）、offline_favorites（收藏项）。
 */
public class FavoriteStore extends SQLiteOpenHelper {

    private static final String TAG = "FavoriteStore";
    private static final String DB_NAME = "jq_offline_favorites.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE_FOLDERS = "offline_folders";
    private static final String COL_FOLDER_ID = "folder_id";
    private static final String COL_NAME = "name";

    private static final String TABLE_FAVORITES = "offline_favorites";
    private static final String TABLE_BACKUPS = "offline_backups";
    private static final String COL_ID = "id";
    private static final String COL_ALBUM_ID = "album_id";
    private static final String COL_TITLE = "title";
    private static final String COL_COVER_URL = "cover_url";
    private static final String COL_AUTHORS = "authors";
    private static final String COL_TAGS = "tags";
    private static final String COL_BACKUP_KEY = "backup_key";
    private static final String COL_ITEMS_JSON = "items_json";
    private static final String COL_CREATED_AT = "created_at";

    private static FavoriteStore instance;
    private final AtomicInteger nextId = new AtomicInteger(1);

    public static synchronized FavoriteStore getInstance(Context context) {
        if (instance == null) {
            instance = new FavoriteStore(context.getApplicationContext());
        }
        return instance;
    }

    private FavoriteStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_FOLDERS + " ("
            + COL_FOLDER_ID + " TEXT PRIMARY KEY,"
            + COL_NAME + " TEXT NOT NULL"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_FAVORITES + " ("
            + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + COL_FOLDER_ID + " TEXT NOT NULL,"
            + COL_ALBUM_ID + " TEXT NOT NULL,"
            + COL_TITLE + " TEXT NOT NULL DEFAULT '',"
            + COL_COVER_URL + " TEXT NOT NULL DEFAULT '',"
            + COL_AUTHORS + " TEXT NOT NULL DEFAULT '',"
            + COL_TAGS + " TEXT NOT NULL DEFAULT '',"
            + "UNIQUE(" + COL_FOLDER_ID + ", " + COL_ALBUM_ID + ")"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_BACKUPS + " ("
            + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + COL_BACKUP_KEY + " TEXT NOT NULL UNIQUE,"
            + COL_ITEMS_JSON + " TEXT NOT NULL,"
            + COL_CREATED_AT + " INTEGER NOT NULL"
            + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 注意：未来迁移必须使用 ALTER TABLE ADD COLUMN，直接 DROP TABLE 会导致用户数据永久丢失
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_BACKUPS);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_FAVORITES);
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_FOLDERS);
        onCreate(db);
    }

    private String generateFolderId() {
        return "offline_" + System.currentTimeMillis() + "_" + nextId.getAndIncrement();
    }

    // ==================== 文件夹操作 ====================

    public JSONArray getFolders() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                "SELECT f." + COL_FOLDER_ID + ", f." + COL_NAME + ","
                    + " (SELECT COUNT(*) FROM " + TABLE_FAVORITES
                    + " WHERE " + COL_FOLDER_ID + " = f." + COL_FOLDER_ID + ") AS cnt"
                    + " FROM " + TABLE_FOLDERS + " f",
                null);
            while (c.moveToNext()) {
                JSONObject obj = new JSONObject();
                obj.put("folderId", c.getString(0));
                obj.put("name", c.getString(1));
                obj.put("count", c.getInt(2));
                arr.put(obj);
            }
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "getFolders failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    public String createFolder(String name) {
        String folderId = generateFolderId();
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_FOLDER_ID, folderId);
        cv.put(COL_NAME, name != null ? name : "");
        db.insertOrThrow(TABLE_FOLDERS, null, cv);
        return folderId;
    }

    public boolean renameFolder(String folderId, String newName) {
        if (newName == null || newName.trim().isEmpty()) {
            return false;
        }
        SQLiteDatabase db = getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put(COL_NAME, newName.trim());
            int rows = db.update(TABLE_FOLDERS, cv,
                COL_FOLDER_ID + "=?", new String[]{folderId});
            return rows > 0;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "renameFolder failed", e);
            return false;
        }
    }

    public boolean deleteFolder(String folderId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_FAVORITES, COL_FOLDER_ID + "=?",
                new String[]{folderId});
            db.delete(TABLE_FOLDERS, COL_FOLDER_ID + "=?",
                new String[]{folderId});
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "deleteFolder failed", e);
            return false;
        } finally {
            db.endTransaction();
        }
    }

    // ==================== 项目操作 ====================

    public boolean addItem(String folderId, JSONObject item) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put(COL_FOLDER_ID, folderId);
            cv.put(COL_ALBUM_ID, item.optString("id", ""));
            cv.put(COL_TITLE, item.optString("title", ""));
            cv.put(COL_COVER_URL, item.optString("coverUrl", ""));
            cv.put(COL_AUTHORS, item.optJSONArray("authors") != null
                ? item.optJSONArray("authors").toString() : "[]");
            cv.put(COL_TAGS, item.optJSONArray("tags") != null
                ? item.optJSONArray("tags").toString() : "[]");
            long rowId = db.insertWithOnConflict(TABLE_FAVORITES, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE);
            return rowId != -1;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "addItem failed", e);
            return false;
        }
    }

    public boolean removeItem(String folderId, String albumId) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            int rows = db.delete(TABLE_FAVORITES,
                COL_FOLDER_ID + "=? AND " + COL_ALBUM_ID + "=?",
                new String[]{folderId, albumId});
            return rows > 0;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "removeItem failed", e);
            return false;
        }
    }

    public JSONObject getItems(String folderId, String keyword,
                               int page, int pageSize) {
        JSONObject result = new JSONObject();
        Cursor countCursor = null;
        Cursor dataCursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            String where = COL_FOLDER_ID + "=?";
            String[] whereArgs;
            if (keyword != null && !keyword.isEmpty()) {
                where += " AND " + COL_TITLE + " LIKE ?";
                whereArgs = new String[]{folderId, "%" + keyword + "%"};
            } else {
                whereArgs = new String[]{folderId};
            }

            // count
            countCursor = db.query(TABLE_FAVORITES,
                new String[]{"COUNT(*)"}, where, whereArgs,
                null, null, null);
            int totalItems = countCursor.moveToFirst()
                ? countCursor.getInt(0) : 0;
            int totalPages = Math.max(1,
                (int) Math.ceil((double) totalItems / pageSize));
            int currentPage = Math.min(Math.max(1, page), totalPages);
            int offset = (currentPage - 1) * pageSize;

            // data
            dataCursor = db.query(TABLE_FAVORITES,
                new String[]{COL_ALBUM_ID, COL_TITLE, COL_COVER_URL,
                    COL_AUTHORS, COL_TAGS},
                where, whereArgs, null, null,
                COL_ID + " ASC",
                pageSize + " OFFSET " + offset);

            JSONArray content = new JSONArray();
            while (dataCursor.moveToNext()) {
                content.put(cursorToItem(dataCursor));
            }

            result.put("totalItems", totalItems);
            result.put("totalPages", totalPages);
            result.put("currentPage", currentPage);
            result.put("content", content);
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "getItems failed", e);
            try {
                result.put("totalItems", 0);
                result.put("totalPages", 1);
                result.put("currentPage", 1);
                result.put("content", new JSONArray());
            } catch (Exception ex) {
                Log.w(TAG, "构建默认返回值失败", ex);
            }
        } finally {
            if (countCursor != null) countCursor.close();
            if (dataCursor != null) dataCursor.close();
        }
        return result;
    }

    public JSONArray getAllItems(String folderId) {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_FAVORITES,
                new String[]{COL_ALBUM_ID, COL_TITLE, COL_COVER_URL,
                    COL_AUTHORS, COL_TAGS},
                COL_FOLDER_ID + "=?", new String[]{folderId},
                null, null, COL_ID + " ASC");
            while (c.moveToNext()) {
                arr.put(cursorToItem(c));
            }
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "getAllItems failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    // ==================== 跨夹操作 ====================

    public int getTotalCount() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT " + COL_ALBUM_ID + ") FROM "
                    + TABLE_FAVORITES, null);
            return c.moveToFirst() ? c.getInt(0) : 0;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "getTotalCount failed", e);
            return 0;
        } finally {
            if (c != null) c.close();
        }
    }

    public JSONArray getAllItemsMerged() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                "SELECT " + COL_ALBUM_ID + ", " + COL_TITLE
                    + ", " + COL_COVER_URL + ", " + COL_AUTHORS
                    + ", " + COL_TAGS
                    + " FROM " + TABLE_FAVORITES
                    + " WHERE " + COL_ID + " IN (SELECT MIN(" + COL_ID + ")"
                    + " FROM " + TABLE_FAVORITES
                    + " GROUP BY " + COL_ALBUM_ID + ")"
                    + " ORDER BY " + COL_ID + " ASC",
                null);
            while (c.moveToNext()) {
                arr.put(cursorToItem(c));
            }
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "getAllItemsMerged failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    public boolean moveAllItems(String sourceId, String targetId) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = null;
        db.beginTransaction();
        try {
            // 验证两个夹都存在
            c = db.query(TABLE_FOLDERS, new String[]{COL_FOLDER_ID},
                COL_FOLDER_ID + "=? OR " + COL_FOLDER_ID + "=?",
                new String[]{sourceId, targetId},
                null, null, null);
            if (c.getCount() < 2) return false;
            c.close();
            c = null;

            // 游标遍历源夹，逐行 insert or ignore 到目标夹
            c = db.query(TABLE_FAVORITES,
                new String[]{COL_ALBUM_ID, COL_TITLE, COL_COVER_URL,
                    COL_AUTHORS, COL_TAGS},
                COL_FOLDER_ID + "=?", new String[]{sourceId},
                null, null, null);

            while (c.moveToNext()) {
                ContentValues cv = new ContentValues();
                cv.put(COL_FOLDER_ID, targetId);
                cv.put(COL_ALBUM_ID, c.getString(0));
                cv.put(COL_TITLE, c.getString(1));
                cv.put(COL_COVER_URL, c.getString(2));
                cv.put(COL_AUTHORS, c.getString(3));
                cv.put(COL_TAGS, c.getString(4));
                db.insertWithOnConflict(TABLE_FAVORITES, null, cv,
                    SQLiteDatabase.CONFLICT_IGNORE);
            }
            // 删除源夹所有项
            db.delete(TABLE_FAVORITES, COL_FOLDER_ID + "=?",
                new String[]{sourceId});
            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "moveAllItems failed", e);
            return false;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }

    public String copyFolder(String sourceId, String targetName) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = null;
        String newFolderId = generateFolderId();
        db.beginTransaction();
        try {
            // 创建新文件夹
            ContentValues folderCv = new ContentValues();
            folderCv.put(COL_FOLDER_ID, newFolderId);
            folderCv.put(COL_NAME, targetName != null ? targetName : "");
            db.insertOrThrow(TABLE_FOLDERS, null, folderCv);

            // 复制源夹全部项
            c = db.query(TABLE_FAVORITES,
                new String[]{COL_ALBUM_ID, COL_TITLE, COL_COVER_URL,
                    COL_AUTHORS, COL_TAGS},
                COL_FOLDER_ID + "=?", new String[]{sourceId},
                null, null, null);
            while (c.moveToNext()) {
                ContentValues cv = new ContentValues();
                cv.put(COL_FOLDER_ID, newFolderId);
                cv.put(COL_ALBUM_ID, c.getString(0));
                cv.put(COL_TITLE, c.getString(1));
                cv.put(COL_COVER_URL, c.getString(2));
                cv.put(COL_AUTHORS, c.getString(3));
                cv.put(COL_TAGS, c.getString(4));
                db.insertWithOnConflict(TABLE_FAVORITES, null, cv,
                    SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
            return newFolderId;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "copyFolder failed", e);
            return "";
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }

    public int addItemsBatch(String folderId, JSONArray items) {
        SQLiteDatabase db = getWritableDatabase();
        int count = 0;
        db.beginTransaction();
        try {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                ContentValues cv = new ContentValues();
                cv.put(COL_FOLDER_ID, folderId);
                cv.put(COL_ALBUM_ID, item.optString("id", ""));
                cv.put(COL_TITLE, item.optString("title", ""));
                cv.put(COL_COVER_URL, item.optString("coverUrl", ""));
                cv.put(COL_AUTHORS, item.optJSONArray("authors") != null
                    ? item.optJSONArray("authors").toString() : "[]");
                cv.put(COL_TAGS, item.optJSONArray("tags") != null
                    ? item.optJSONArray("tags").toString() : "[]");
                long rowId = db.insertWithOnConflict(TABLE_FAVORITES, null,
                    cv, SQLiteDatabase.CONFLICT_IGNORE);
                if (rowId != -1) count++;
            }
            db.setTransactionSuccessful();
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "addItemsBatch failed", e);
        } finally {
            db.endTransaction();
        }
        return count;
    }

    // ==================== 全部夹操作 ====================

    /**
     * 将全部离线项合并到目标文件夹（去重），其余文件夹清空，事务保证原子性
     */
    public boolean mergeAllToFolder(String targetId) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = null;
        db.beginTransaction();
        try {
            // 验证目标文件夹存在
            c = db.query(TABLE_FOLDERS, new String[]{COL_FOLDER_ID},
                COL_FOLDER_ID + "=?", new String[]{targetId},
                null, null, null);
            if (c.getCount() == 0) return false;
            c.close();
            c = null;

            // 跨夹去重插入到目标夹（每 album_id 取 MIN(id) 行，避免 GROUP BY 列值不确定）
            db.execSQL("INSERT OR IGNORE INTO " + TABLE_FAVORITES
                    + " (" + COL_FOLDER_ID + ", " + COL_ALBUM_ID + ", "
                    + COL_TITLE + ", " + COL_COVER_URL + ", "
                    + COL_AUTHORS + ", " + COL_TAGS + ")"
                    + " SELECT ?, o." + COL_ALBUM_ID + ", "
                    + "o." + COL_TITLE + ", o." + COL_COVER_URL + ", "
                    + "o." + COL_AUTHORS + ", o." + COL_TAGS
                    + " FROM " + TABLE_FAVORITES + " o"
                    + " WHERE o." + COL_ID + " IN (SELECT MIN(" + COL_ID + ")"
                    + " FROM " + TABLE_FAVORITES
                    + " GROUP BY " + COL_ALBUM_ID + ")",
                new Object[]{targetId});

            // 删除非目标夹的全部项
            db.delete(TABLE_FAVORITES,
                COL_FOLDER_ID + "!=?", new String[]{targetId});

            db.setTransactionSuccessful();
            return true;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "mergeAllToFolder failed", e);
            return false;
        } finally {
            if (c != null) c.close();
            db.endTransaction();
        }
    }

    // ==================== 容灾备份 ====================

    public void saveBackup(String key, JSONArray items) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            ContentValues cv = new ContentValues();
            cv.put(COL_BACKUP_KEY, key);
            cv.put(COL_ITEMS_JSON, items.toString());
            cv.put(COL_CREATED_AT, System.currentTimeMillis());
            db.insertWithOnConflict(TABLE_BACKUPS, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "saveBackup failed", e);
        }
    }

    public JSONArray loadBackup(String key) {
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_BACKUPS,
                new String[]{COL_ITEMS_JSON},
                COL_BACKUP_KEY + "=?", new String[]{key},
                null, null, null);
            if (c.moveToFirst()) {
                return new JSONArray(c.getString(0));
            }
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "loadBackup failed", e);
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    public boolean deleteBackup(String key) {
        SQLiteDatabase db = getWritableDatabase();
        try {
            int rows = db.delete(TABLE_BACKUPS,
                COL_BACKUP_KEY + "=?", new String[]{key});
            return rows > 0;
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "deleteBackup failed", e);
            return false;
        }
    }

    public JSONArray listBackupKeys() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_BACKUPS,
                new String[]{COL_BACKUP_KEY},
                null, null, null, null,
                COL_CREATED_AT + " DESC");
            while (c.moveToNext()) {
                arr.put(c.getString(0));
            }
        } catch (Exception e) {
            android.util.Log.w("FavoriteStore", "listBackupKeys failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    // ==================== 辅助 ====================

    private JSONObject cursorToItem(Cursor c) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", c.getString(0));
            obj.put("title", c.getString(1));
            obj.put("coverUrl", c.getString(2));
            // authors 和 tags 存的是 JSON 字符串，直接解析为数组
            obj.put("authors", new JSONArray(c.getString(3)));
            obj.put("tags", new JSONArray(c.getString(4)));
        } catch (Exception e) {
            Log.d(TAG, "转换收藏项记录失败", e);
        }
        return obj;
    }
}
