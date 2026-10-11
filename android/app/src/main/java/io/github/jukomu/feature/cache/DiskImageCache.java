package io.github.jukomu.feature.cache;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.StatFs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * Android 在线图片冷层，索引与下载数据独立。所有操作均在热层锁外调用。
 */
public final class DiskImageCache extends SQLiteOpenHelper {
    private static final Logger LOGGER = LoggerFactory.getLogger(DiskImageCache.class);
    private static final int VERSION = 1;
    private static final String TABLE = "image_cache_entries";
    private static final String KEY = "cache_key";
    private static final String FILE = "file_name";
    private static final String MIME = "mime_type";
    private static final String SIZE = "size_bytes";
    private static final String ACCESSED = "last_access";
    private final File directory;
    private final LinkedHashMap<String, StoredEntry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Long> pendingAccesses = new LinkedHashMap<>();
    private final Map<File, Integer> readers = new LinkedHashMap<>();
    private final Set<File> retiredFiles = new HashSet<>();
    private long lastAccess;
    private long capacityBytes;
    private boolean policyApplied;

    DiskImageCache(Context context) {
        this(context, new File(context.getCacheDir(), "image-cache"));
    }

    DiskImageCache(Context context, File directory) {
        super(context.getApplicationContext(), new File(directory, "index.db").getAbsolutePath(), null, VERSION);
        this.directory = directory;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建图片缓存目录");
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" + KEY + " TEXT PRIMARY KEY NOT NULL," + FILE
            + " TEXT NOT NULL," + MIME + " TEXT NOT NULL," + SIZE + " INTEGER NOT NULL," + ACCESSED + " INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_image_cache_access ON " + TABLE + " (" + ACCESSED + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    /** 设置磁盘冷层容量（字节）。 */
    synchronized void setCapacity(long bytes) {
        capacityBytes = Math.max(0L, bytes);
        policyApplied = true;
        flushAccessTimes();
        if (capacityBytes == 0L) clear();
        else evictToCapacity(0L, null);
    }

    /**
     * 对外图片 payload，不包含索引及已失效修订。
     */
    /** 获取磁盘冷层已提交图片 payload 使用量（字节）。 */
    synchronized long payloadBytes() {
        long total = 0L;
        for (StoredEntry entry : entries.values()) {
            if (entry.file.isFile()) total += entry.size;
        }
        return total;
    }

    /**
     * 实际目录占用：索引、journal、临时文件、孤儿及延迟回收文件均计入。
     */
    /** 获取冷层目录实际占用（字节），包含索引、临时文件和残留文件。 */
    synchronized long usedBytes() {
        return directoryBytes(false);
    }

    private long directoryBytes(boolean imagesOnly) {
        long total = 0L;
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException("无法读取图片缓存目录");
        for (File file : files) {
            if (file.isFile() && (!imagesOnly || !file.getName().startsWith("index.db"))) {
                total += file.length();
            }
        }
        return total;
    }

    synchronized long availableBytes() {
        try {
            return new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        } catch (RuntimeException error) {
            return -1L;
        }
    }

    synchronized boolean put(String key, byte[] data, String mimeType) {
        if (!policyApplied || data == null || data.length == 0 || data.length > capacityBytes) return false;
        StoredEntry old = peek(key);
        long reclaimed = old != null && !readers.containsKey(old.file) ? old.size : 0L;
        if (!evictToCapacity(data.length - reclaimed, key)) return false;
        long available = availableBytes();
        if (available < CacheCapacityPolicy.DISK_RESERVE_MB * CacheCapacityPolicy.MIB + data.length) return false;
        String revision = digest(key) + "." + UUID.randomUUID();
        File temp = new File(directory, revision + ".tmp");
        File target = new File(directory, revision + ".bin");
        boolean committed = false;
        long accessed = nextAccess();
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(data);
                output.getFD().sync();
            }
            if (!temp.renameTo(target)) throw new IOException("无法发布图片缓存修订文件");
            SQLiteDatabase db = getWritableDatabase();
            db.beginTransaction();
            try {
                writeAccessTimes(db);
                ContentValues values = new ContentValues();
                values.put(KEY, key);
                values.put(FILE, target.getName());
                values.put(MIME, mimeType == null ? "image/jpeg" : mimeType);
                values.put(SIZE, data.length);
                values.put(ACCESSED, accessed);
                if (db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE) < 0) {
                    throw new IllegalStateException("图片冷层索引提交失败");
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            committed = true;
            pendingAccesses.clear();
            entries.remove(key);
            entries.put(key, new StoredEntry(target, mimeType == null ? "image/jpeg" : mimeType, data.length));
            if (old != null) retire(old.file);
            return true;
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("图片冷层写入失败", error);
            return false;
        } finally {
            temp.delete();
            if (!committed) target.delete();
        }
    }

    /**
     * 命中只更新进程内 LRU；文件租约保证升温失败后仍可流式返回。
     */
    synchronized Entry get(String key) {
        StoredEntry stored = entries.get(key);
        if (stored == null) return null;
        if (!stored.file.isFile()) {
            removeKey(key);
            return null;
        }
        pendingAccesses.put(key, nextAccess());
        readers.merge(stored.file, 1, Integer::sum);
        return new Entry(this, stored);
    }

    synchronized boolean has(String key) {
        StoredEntry entry = peek(key);
        return entry != null && entry.file.isFile();
    }

    /**
     * 不改变 LRU、不写盘的冷层元数据快照。
     */
    synchronized List<Info> snapshot() {
        List<Info> result = new ArrayList<>();
        for (Map.Entry<String, StoredEntry> item : entries.entrySet()) {
            StoredEntry entry = item.getValue();
            if (entry.file.isFile()) result.add(new Info(item.getKey(), entry.size, entry.mimeType));
        }
        return result;
    }

    synchronized void removeKey(String key) {
        StoredEntry entry = peek(key);
        if (entry == null) return;
        getWritableDatabase().delete(TABLE, KEY + "=?", new String[]{key});
        entries.remove(key);
        pendingAccesses.remove(key);
        retire(entry.file);
    }

    synchronized void removeKey(String key, Entry revision) {
        StoredEntry current = peek(key);
        if (current != null && current.file.equals(revision.file)) removeKey(key);
    }

    synchronized void removeByPrefix(String prefix) {
        for (String key : new ArrayList<>(entries.keySet())) {
            if (key.startsWith(prefix)) removeKey(key);
        }
    }

    synchronized void clear() {
        getWritableDatabase().delete(TABLE, null, null);
        for (StoredEntry entry : entries.values()) retire(entry.file);
        entries.clear();
        pendingAccesses.clear();
    }

    synchronized void recover() {
        entries.clear();
        pendingAccesses.clear();
        Set<String> indexed = new HashSet<>();
        List<String> missing = new ArrayList<>();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor cursor = db.query(TABLE, new String[]{KEY, FILE, MIME, SIZE, ACCESSED},
            null, null, null, null, ACCESSED + " ASC, " + KEY + " ASC")) {
            while (cursor.moveToNext()) {
                File file = new File(directory, cursor.getString(1));
                if (file.isFile() && file.length() == cursor.getLong(3)) {
                    indexed.add(file.getName());
                    entries.put(cursor.getString(0), new StoredEntry(file, cursor.getString(2), cursor.getLong(3)));
                    lastAccess = Math.max(lastAccess, cursor.getLong(4));
                } else missing.add(cursor.getString(0));
            }
        }
        for (String key : missing) db.delete(TABLE, KEY + "=?", new String[]{key});
        File[] files = directory.listFiles();
        if (files == null) throw new IllegalStateException("无法恢复图片缓存目录");
        for (File file : files) {
            if (!file.getName().startsWith("index.db") && !indexed.contains(file.getName())) file.delete();
        }
        // 恢复阶段不应用默认 0 容量；真实策略应用前只读。
    }

    private boolean evictToCapacity(long incomingBytes, String replacingKey) {
        long replacingBytes = 0L;
        StoredEntry replacing = replacingKey == null ? null : peek(replacingKey);
        if (replacing != null && !readers.containsKey(replacing.file)) replacingBytes = replacing.size;
        while (directoryBytes(true) - replacingBytes + incomingBytes > capacityBytes) {
            String oldest = null;
            for (Map.Entry<String, StoredEntry> item : entries.entrySet()) {
                if (!item.getKey().equals(replacingKey) && !readers.containsKey(item.getValue().file)) {
                    oldest = item.getKey();
                    break;
                }
            }
            if (oldest == null) return false;
            removeKey(oldest);
        }
        return true;
    }

    private StoredEntry peek(String key) {
        // LinkedHashMap.get 会改变访问顺序，查询存在性和修订时不能使用它。
        for (Map.Entry<String, StoredEntry> item : entries.entrySet()) {
            if (item.getKey().equals(key)) return item.getValue();
        }
        return null;
    }

    private long nextAccess() {
        lastAccess = Math.max(System.currentTimeMillis(), lastAccess + 1L);
        return lastAccess;
    }

    private void writeAccessTimes(SQLiteDatabase db) {
        for (Map.Entry<String, Long> access : pendingAccesses.entrySet()) {
            ContentValues values = new ContentValues();
            values.put(ACCESSED, access.getValue());
            db.update(TABLE, values, KEY + "=?", new String[]{access.getKey()});
        }
    }

    private void flushAccessTimes() {
        if (pendingAccesses.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        try {
            db.beginTransaction();
            try {
                writeAccessTimes(db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            pendingAccesses.clear();
        } catch (RuntimeException error) {
            LOGGER.warn("图片冷层访问顺序持久化失败，保留进程内顺序", error);
        }
    }

    private void retire(File file) {
        if (readers.containsKey(file)) retiredFiles.add(file);
        else file.delete();
    }

    private synchronized void release(File file) {
        int count = readers.get(file) - 1;
        if (count > 0) readers.put(file, count);
        else {
            readers.remove(file);
            if (retiredFiles.remove(file)) file.delete();
            if (policyApplied) evictToCapacity(0L, null);
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private record StoredEntry(File file, String mimeType, long size) {
    }

    static final class Entry implements AutoCloseable {
        private final DiskImageCache owner;
        final File file;
        final String mimeType;
        final long size;
        private boolean closed;

        Entry(DiskImageCache owner, StoredEntry stored) {
            this.owner = owner;
            this.file = stored.file;
            this.mimeType = stored.mimeType;
            this.size = stored.size;
        }

        InputStream open() throws IOException {
            try {
                return new FilterInputStream(new FileInputStream(file)) {
                    @Override
                    public void close() throws IOException {
                        try {
                            super.close();
                        } finally {
                            Entry.this.close();
                        }
                    }
                };
            } catch (IOException error) {
                close();
                throw error;
            }
        }

        byte[] readBytes() throws IOException {
            if (size > Integer.MAX_VALUE) throw new IOException("图片超过升温读取预算");
            byte[] data = new byte[(int) size];
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
                input.readFully(data);
            }
            return data;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            owner.release(file);
        }
    }

    public record Info(String key, long sizeBytes, String mimeType) {
    }
}
