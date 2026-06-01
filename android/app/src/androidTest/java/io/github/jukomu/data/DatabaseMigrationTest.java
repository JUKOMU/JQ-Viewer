package io.github.jukomu.data;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

import java.lang.reflect.Constructor;

public class DatabaseMigrationTest {

    @Test
    public void settingsUpgradeFromV1PreservesExistingRows() throws Exception {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            SettingsStore store = newStore(SettingsStore.class);
            store.onCreate(db);

            db.execSQL(
                "INSERT OR REPLACE INTO settings (key, value, updated_at) VALUES (?, ?, ?)",
                new Object[]{"reader_preload_pages", "23", 123L});

            store.onUpgrade(db, 1, 2);

            assertEquals("23", queryString(
                db,
                "SELECT value FROM settings WHERE key = ?",
                new String[]{"reader_preload_pages"}));
            assertEquals(10, queryInt(db, "SELECT COUNT(*) FROM settings", null));
        } finally {
            db.close();
        }
    }

    @Test
    public void favoriteUpgradeFromV1PreservesExistingRows() throws Exception {
        SQLiteDatabase db = SQLiteDatabase.create(null);
        try {
            FavoriteStore store = newStore(FavoriteStore.class);
            store.onCreate(db);

            db.execSQL(
                "INSERT INTO offline_folders (folder_id, name) VALUES (?, ?)",
                new Object[]{"folder-1", "default"});
            db.execSQL(
                "INSERT INTO offline_favorites "
                    + "(folder_id, album_id, title, cover_url, authors, tags) "
                    + "VALUES (?, ?, ?, ?, ?, ?)",
                new Object[]{"folder-1", "album-1", "title", "cover", "author", "[]"});
            db.execSQL(
                "INSERT INTO offline_backups (backup_key, items_json, created_at) VALUES (?, ?, ?)",
                new Object[]{"backup-1", "[]", 456L});

            store.onUpgrade(db, 1, 2);

            assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM offline_folders", null));
            assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM offline_favorites", null));
            assertEquals(1, queryInt(db, "SELECT COUNT(*) FROM offline_backups", null));
            assertEquals("album-1", queryString(
                db,
                "SELECT album_id FROM offline_favorites WHERE folder_id = ?",
                new String[]{"folder-1"}));
        } finally {
            db.close();
        }
    }

    private static <T> T newStore(Class<T> storeClass) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Constructor<T> constructor = storeClass.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(context);
    }

    private static int queryInt(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            cursor.moveToFirst();
            return cursor.getInt(0);
        }
    }

    private static String queryString(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            cursor.moveToFirst();
            return cursor.getString(0);
        }
    }
}
