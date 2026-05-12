package io.github.jukomu.storage;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * 应用设置 SQLite 数据库（权威持久化源）。
 * <p>
 * 表结构：单表 key-value，新增设置无需 migration。
 */
public class SettingsDatabase extends SQLiteOpenHelper {

    private static final String DB_NAME = "jq_settings.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE = "settings";
    private static final String COL_KEY = "key";
    private static final String COL_VALUE = "value";
    private static final String COL_UPDATED_AT = "updated_at";

    private static SettingsDatabase instance;

    public static synchronized SettingsDatabase getInstance(Context context) {
        if (instance == null) {
            instance = new SettingsDatabase(context.getApplicationContext());
        }
        return instance;
    }

    private SettingsDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_KEY + " TEXT PRIMARY KEY NOT NULL,"
                + COL_VALUE + " TEXT NOT NULL,"
                + COL_UPDATED_AT + " INTEGER NOT NULL"
                + ")");

        long now = System.currentTimeMillis();
        insertDefault(db, "reader_preload_pages", "15", now);
        insertDefault(db, "preload_concurrency", "6", now);
        insertDefault(db, "download_concurrency", "6", now);
        insertDefault(db, "download_public", "false", now);
        insertDefault(db, "cache_capacity_mb", "640", now);
    }

    private void insertDefault(SQLiteDatabase db, String key, String value, long now) {
        ContentValues cv = new ContentValues();
        cv.put(COL_KEY, key);
        cv.put(COL_VALUE, value);
        cv.put(COL_UPDATED_AT, now);
        db.insert(TABLE, null, cv);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    // ========== 通用读写 ==========

    public String getString(String key) {
        try (Cursor c = getReadableDatabase().query(TABLE,
                new String[]{COL_VALUE}, COL_KEY + "=?", new String[]{key},
                null, null, null)) {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        } catch (Exception ignored) { }
        return null;
    }

    public int getInt(String key, int defaultVal) {
        String raw = getString(key);
        if (raw == null) return defaultVal;
        try {
            int n = Integer.parseInt(raw);
            return (n > 0) ? n : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    public long getLong(String key, long defaultVal) {
        String raw = getString(key);
        if (raw == null) return defaultVal;
        try {
            long n = Long.parseLong(raw);
            return (n > 0) ? n : defaultVal;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    public boolean getBoolean(String key, boolean defaultVal) {
        String raw = getString(key);
        if (raw == null) return defaultVal;
        return Boolean.parseBoolean(raw);
    }

    public boolean putString(String key, String value) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(COL_KEY, key);
            cv.put(COL_VALUE, value);
            cv.put(COL_UPDATED_AT, System.currentTimeMillis());
            return getWritableDatabase().insertWithOnConflict(TABLE, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (Exception ignored) {
            return false;
        }
    }
}
