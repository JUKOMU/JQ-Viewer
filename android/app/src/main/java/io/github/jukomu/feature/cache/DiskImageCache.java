package io.github.jukomu.feature.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.StatFs;
import java.io.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Android 在线图片冷层，索引与下载数据独立。 */
public final class DiskImageCache extends SQLiteOpenHelper {
    private static final int VERSION = 1;
    private static final String TABLE = "image_cache_entries";
    private static final String KEY = "cache_key";
    private static final String FILE = "file_name";
    private static final String MIME = "mime_type";
    private static final String SIZE = "size_bytes";
    private static final String ACCESSED = "last_access";
    private final File directory;
    private long capacityBytes;

    DiskImageCache(Context context) { this(context, new File(context.getCacheDir(), "image-cache")); }

    DiskImageCache(Context context, File directory) {
        super(context.getApplicationContext(), new File(directory, "index.db").getAbsolutePath(), null, VERSION);
        this.directory = directory;
        if (!directory.exists()) directory.mkdirs();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" + KEY + " TEXT PRIMARY KEY NOT NULL," + FILE
            + " TEXT NOT NULL," + MIME + " TEXT NOT NULL," + SIZE + " INTEGER NOT NULL," + ACCESSED + " INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_image_cache_access ON " + TABLE + " (" + ACCESSED + ")");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    synchronized void setCapacity(long bytes) {
        capacityBytes = Math.max(0L, bytes);
        if (capacityBytes == 0L) clear(); else evictToCapacity();
    }
    synchronized long usedBytes() {
        long total = 0L;
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{FILE, SIZE}, null, null, null, null, null)) {
            while (cursor.moveToNext()) {
                File file = new File(directory, cursor.getString(0));
                total = safeAdd(total, file.isFile() ? file.length() : cursor.getLong(1));
            }
        }
        return total;
    }
    synchronized long availableBytes() {
        try { return new StatFs(directory.getAbsolutePath()).getAvailableBytes(); }
        catch (RuntimeException error) { return -1L; }
    }
    synchronized boolean put(String key, byte[] data, String mimeType) {
        if (capacityBytes <= 0L || data == null || data.length == 0 || data.length > capacityBytes) return false;
        File temp = new File(directory, digest(key) + "." + System.nanoTime() + ".tmp");
        File target = new File(directory, digest(key) + ".bin");
        try {
            if (!directory.exists() && !directory.mkdirs()) return false;
            try (FileOutputStream output = new FileOutputStream(temp)) { output.write(data); output.getFD().sync(); }
            if (target.exists() && !target.delete()) return false;
            if (!temp.renameTo(target)) return false;
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                ContentValues values = new ContentValues();
                values.put(KEY, key); values.put(FILE, target.getName());
                values.put(MIME, mimeType == null ? "image/jpeg" : mimeType);
                values.put(SIZE, data.length); values.put(ACCESSED, System.currentTimeMillis());
                db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            evictToCapacity();
            return true;
        } catch (IOException | RuntimeException error) { temp.delete(); return false; }
    }
    synchronized Entry get(String key) {
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{FILE, MIME, SIZE}, KEY + "=?", new String[]{key}, null, null, null)) {
            if (!cursor.moveToFirst()) return null;
            File file = new File(directory, cursor.getString(0));
            if (!file.isFile()) { remove(key); return null; }
            ContentValues access = new ContentValues();
            access.put(ACCESSED, System.currentTimeMillis());
            getWritableDatabase().update(TABLE, access, KEY + "=?", new String[]{key});
            return new Entry(file, cursor.getString(1), cursor.getLong(2));
        }
    }

    synchronized void removeKey(String key) {
        remove(key);
    }
    synchronized List<Info> snapshot() {
        List<Info> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{KEY, SIZE, MIME}, null, null, null, null, ACCESSED + " ASC")) {
            while (cursor.moveToNext()) {
                if (new File(directory, digest(cursor.getString(0)) + ".bin").isFile()) {
                    result.add(new Info(cursor.getString(0), cursor.getLong(1), cursor.getString(2)));
                }
            }
        }
        return result;
    }
    synchronized void removeByPrefix(String prefix) {
        List<String> keys = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{KEY}, KEY + " LIKE ?", new String[]{prefix + "%"}, null, null, null)) {
            while (cursor.moveToNext()) keys.add(cursor.getString(0));
        }
        for (String key : keys) remove(key);
    }
    synchronized void clear() {
        SQLiteDatabase db = getWritableDatabase();
        List<String> files = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE, new String[]{FILE}, null, null, null, null, null)) {
            while (cursor.moveToNext()) files.add(cursor.getString(0));
        }
        db.delete(TABLE, null, null);
        for (String file : files) new File(directory, file).delete();
        cleanupTemporaryFiles();
    }
    synchronized void remove(String key) {
        SQLiteDatabase db = getWritableDatabase();
        String file = null;
        try (Cursor cursor = db.query(TABLE, new String[]{FILE}, KEY + "=?", new String[]{key}, null, null, null)) {
            if (cursor.moveToFirst()) file = cursor.getString(0);
        }
        db.delete(TABLE, KEY + "=?", new String[]{key});
        if (file != null) new File(directory, file).delete();
    }
    void recover() {
        synchronized (this) {
            cleanupTemporaryFiles();
            List<String> indexed = new ArrayList<>();
            try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{KEY, FILE}, null, null, null, null, null)) {
                while (cursor.moveToNext()) {
                    File file = new File(directory, cursor.getString(1));
                    if (file.isFile()) indexed.add(file.getName());
                    else getWritableDatabase().delete(TABLE, KEY + "=?", new String[]{cursor.getString(0)});
                }
            }
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                if (!file.getName().equals("index.db") && !file.getName().equals("index.db-journal") && !indexed.contains(file.getName())) file.delete();
            }
        }
    }
    private void evictToCapacity() {
        while (usedBytes() > capacityBytes) {
            Info oldest = null;
            try (Cursor cursor = getReadableDatabase().query(TABLE, new String[]{KEY, SIZE, MIME}, null, null, null, null, ACCESSED + " ASC", "1")) {
                if (cursor.moveToFirst()) oldest = new Info(cursor.getString(0), cursor.getLong(1), cursor.getString(2));
            }
            if (oldest == null) break;
            remove(oldest.key());
        }
    }
    private void cleanupTemporaryFiles() {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".tmp"));
        if (files != null) for (File file : files) file.delete();
    }
    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception error) { return Integer.toHexString(value.hashCode()); }
    }
    private static long safeAdd(long left, long right) { return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right; }
    static final class Entry {
        final File file; final String mimeType; final long size;
        Entry(File file, String mimeType, long size) { this.file = file; this.mimeType = mimeType; this.size = size; }
        InputStream open() throws IOException { return new FileInputStream(file); }

        byte[] readBytes() throws IOException { return java.nio.file.Files.readAllBytes(file.toPath()); }
    }
    public record Info(String key, long sizeBytes, String mimeType) { }
}
