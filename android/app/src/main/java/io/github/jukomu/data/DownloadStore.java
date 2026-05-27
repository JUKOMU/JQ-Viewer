package io.github.jukomu.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 下载任务 SQLite 数据库（权威数据源）。
 * <p>
 * 表结构：
 * - download_tasks: 任务元数据
 * - download_images: 图片信息（sortOrder→filename 映射等）
 */
public class DownloadStore extends SQLiteOpenHelper {

    private static final String TAG = "DownloadStore";
    private static final String DB_NAME = "jq_download.db";
    private static final int DB_VERSION = 3;

    // ---- 表名 ----
    static final String TABLE_TASKS = "download_tasks";
    static final String TABLE_IMAGES = "download_images";

    // ---- tasks 列 ----
    static final String COL_TASK_ID = "task_id";
    static final String COL_ALBUM_ID = "album_id";
    static final String COL_CHAPTER_ID = "chapter_id";
    static final String COL_ALBUM_TITLE = "album_title";
    static final String COL_CHAPTER_TITLE = "chapter_title";
    static final String COL_COVER_URL = "cover_url";
    static final String COL_AUTHOR = "author";
    static final String COL_TAGS = "tags";
    static final String COL_TOTAL_PAGES = "total_pages";
    static final String COL_DOWNLOADED_PAGES = "downloaded_pages";
    static final String COL_FIRST_IMAGE_SORT_ORDER = "first_image_sort_order";
    static final String COL_STATUS = "status";
    static final String COL_ERROR = "error";
    static final String COL_CREATED_AT = "created_at";
    static final String COL_COMPLETED_AT = "completed_at";
    static final String COL_TOTAL_SIZE = "total_size";
    static final String COL_CHAPTER_SORT_ORDER = "chapter_sort_order";

    // ---- images 列 ----
    static final String COL_SORT_ORDER = "sort_order";
    static final String COL_PHOTO_ID = "photo_id";
    static final String COL_FILENAME = "filename";
    static final String COL_URL = "url";
    static final String COL_SCRAMBLE_ID = "scramble_id";
    static final String COL_QUERY_PARAMS = "query_params";

    private static DownloadStore instance;

    public static synchronized DownloadStore getInstance(Context context) {
        if (instance == null) {
            instance = new DownloadStore(context.getApplicationContext());
        }
        return instance;
    }

    private DownloadStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TASKS + " ("
            + COL_TASK_ID + " TEXT PRIMARY KEY,"
            + COL_ALBUM_ID + " TEXT NOT NULL,"
            + COL_CHAPTER_ID + " TEXT NOT NULL,"
            + COL_ALBUM_TITLE + " TEXT NOT NULL,"
            + COL_CHAPTER_TITLE + " TEXT NOT NULL,"
            + COL_COVER_URL + " TEXT NOT NULL,"
            + COL_AUTHOR + " TEXT DEFAULT '',"
            + COL_TAGS + " TEXT DEFAULT '[]',"
            + COL_TOTAL_PAGES + " INTEGER NOT NULL,"
            + COL_DOWNLOADED_PAGES + " INTEGER DEFAULT 0,"
            + COL_FIRST_IMAGE_SORT_ORDER + " INTEGER,"
            + COL_STATUS + " TEXT NOT NULL DEFAULT 'queued',"
            + COL_ERROR + " TEXT,"
            + COL_TOTAL_SIZE + " INTEGER DEFAULT 0,"
            + COL_CHAPTER_SORT_ORDER + " INTEGER DEFAULT 0,"
            + COL_CREATED_AT + " INTEGER NOT NULL,"
            + COL_COMPLETED_AT + " INTEGER"
            + ")");

        db.execSQL("CREATE TABLE " + TABLE_IMAGES + " ("
            + COL_TASK_ID + " TEXT NOT NULL,"
            + COL_SORT_ORDER + " INTEGER NOT NULL,"
            + COL_PHOTO_ID + " TEXT NOT NULL,"
            + COL_FILENAME + " TEXT NOT NULL,"
            + COL_URL + " TEXT NOT NULL,"
            + COL_SCRAMBLE_ID + " TEXT NOT NULL,"
            + COL_QUERY_PARAMS + " TEXT,"
            + "PRIMARY KEY (" + COL_TASK_ID + ", " + COL_SORT_ORDER + "),"
            + "FOREIGN KEY (" + COL_TASK_ID + ") REFERENCES " + TABLE_TASKS + "(" + COL_TASK_ID + ")"
            + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN " + COL_TOTAL_SIZE + " INTEGER DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN " + COL_CHAPTER_SORT_ORDER + " INTEGER DEFAULT 0");
        }
    }

    // ========== 任务操作 ==========

    public void insertTask(String taskId, String albumId, String chapterId,
                           String albumTitle, String chapterTitle, String coverUrl) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TASK_ID, taskId);
        cv.put(COL_ALBUM_ID, albumId);
        cv.put(COL_CHAPTER_ID, chapterId);
        cv.put(COL_ALBUM_TITLE, albumTitle);
        cv.put(COL_CHAPTER_TITLE, chapterTitle);
        cv.put(COL_COVER_URL, coverUrl);
        cv.put(COL_TOTAL_PAGES, 0);
        cv.put(COL_STATUS, "queued");
        cv.put(COL_CREATED_AT, System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(TABLE_TASKS, null, cv,
            SQLiteDatabase.CONFLICT_IGNORE);
    }

    public void updateTaskDetail(String taskId, int totalPages, String author, String tags, int sortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TOTAL_PAGES, totalPages);
        cv.put(COL_AUTHOR, author);
        cv.put(COL_TAGS, tags);
        cv.put(COL_CHAPTER_SORT_ORDER, sortOrder);
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateStatus(String taskId, String status) {
        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS, status);
        if ("downloading".equals(status)) {
            cv.putNull(COL_ERROR);
        }
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateProgress(String taskId, int downloadedPages) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED_PAGES, downloadedPages);
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateCompleted(String taskId, int downloadedPages, int firstSortOrder) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED_PAGES, downloadedPages);
        cv.put(COL_FIRST_IMAGE_SORT_ORDER, firstSortOrder);
        cv.put(COL_STATUS, "completed");
        cv.put(COL_COMPLETED_AT, System.currentTimeMillis());
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateFailed(String taskId, int downloadedPages, String error) {
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED_PAGES, downloadedPages);
        cv.put(COL_STATUS, "failed");
        cv.put(COL_ERROR, error);
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public void updateSize(String taskId, long totalSize) {
        ContentValues cv = new ContentValues();
        cv.put(COL_TOTAL_SIZE, totalSize);
        getWritableDatabase().update(TABLE_TASKS, cv,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    public boolean hasActiveOrCompleted(String taskId) {
        Cursor c = getReadableDatabase().query(TABLE_TASKS,
            new String[]{COL_STATUS}, COL_TASK_ID + " = ?",
            new String[]{taskId}, null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    String status = c.getString(0);
                    return "queued".equals(status) || "downloading".equals(status)
                        || "paused".equals(status) || "completed".equals(status);
                }
            } finally {
                c.close();
            }
        }
        return false;
    }

    public List<JSONObject> getAllTasks() {
        List<JSONObject> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_TASKS, null, null,
            null, null, null, COL_CREATED_AT + " DESC", "500");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    JSONObject task = cursorToTaskJson(c);
                    if (task == null) {
                        Log.w(TAG, "跳过损坏的任务记录");
                        continue;
                    }
                    list.add(task);
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public JSONObject getTask(String taskId) {
        Cursor c = getReadableDatabase().query(TABLE_TASKS, null,
            COL_TASK_ID + " = ?", new String[]{taskId},
            null, null, null);
        if (c != null) {
            try {
                if (c.moveToFirst()) {
                    return cursorToTaskJson(c);
                }
            } finally {
                c.close();
            }
        }
        return null;
    }

    public void deleteTask(String taskId) {
        getWritableDatabase().delete(TABLE_TASKS,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    // ========== 图片操作 ==========

    public void insertImages(String taskId, List<io.github.jukomu.jmcomic.api.model.JmImage> images) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues cv = new ContentValues();
            for (io.github.jukomu.jmcomic.api.model.JmImage img : images) {
                cv.clear();
                cv.put(COL_TASK_ID, taskId);
                cv.put(COL_SORT_ORDER, img.getSortOrder());
                cv.put(COL_PHOTO_ID, img.getPhotoId());
                cv.put(COL_FILENAME, img.getFilename());
                cv.put(COL_URL, img.getUrl());
                cv.put(COL_SCRAMBLE_ID, img.getScrambleId());
                cv.put(COL_QUERY_PARAMS, img.getQueryParams());
                db.insertWithOnConflict(TABLE_IMAGES, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<JSONObject> getImages(String taskId) {
        List<JSONObject> list = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_IMAGES, null,
            COL_TASK_ID + " = ?", new String[]{taskId},
            null, null, COL_SORT_ORDER + " ASC");
        if (c != null) {
            try {
                while (c.moveToNext()) {
                    JSONObject obj = new JSONObject();
                    try {
                        obj.put("taskId", c.getString(c.getColumnIndexOrThrow(COL_TASK_ID)));
                        obj.put("sortOrder", c.getInt(c.getColumnIndexOrThrow(COL_SORT_ORDER)));
                        obj.put("photoId", c.getString(c.getColumnIndexOrThrow(COL_PHOTO_ID)));
                        obj.put("filename", c.getString(c.getColumnIndexOrThrow(COL_FILENAME)));
                        obj.put("url", c.getString(c.getColumnIndexOrThrow(COL_URL)));
                        obj.put("scrambleId", c.getString(c.getColumnIndexOrThrow(COL_SCRAMBLE_ID)));
                        obj.put("queryParams", c.getString(c.getColumnIndexOrThrow(COL_QUERY_PARAMS)));
                        list.add(obj);
                    } catch (Exception e) {
                        Log.d(TAG, "跳过无效图片记录", e);
                    }
                }
            } finally {
                c.close();
            }
        }
        return list;
    }

    public void deleteImages(String taskId) {
        getWritableDatabase().delete(TABLE_IMAGES,
            COL_TASK_ID + " = ?", new String[]{taskId});
    }

    // ========== 启动校验 ==========

    public void validateOnStartup(File baseDir) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            // 1. 僵尸恢复：queued/downloading → failed
            ContentValues cvZombie = new ContentValues();
            cvZombie.put(COL_STATUS, "failed");
            cvZombie.put(COL_ERROR, "App restart, task interrupted");
            db.update(TABLE_TASKS, cvZombie,
                COL_STATUS + " IN ('queued', 'downloading', 'paused')", null);

            // 2. 校验 completed 任务的文件完整性
            Cursor c = db.query(TABLE_TASKS,
                new String[]{COL_TASK_ID, COL_ALBUM_ID, COL_CHAPTER_ID, COL_TOTAL_PAGES},
                COL_STATUS + " = ?", new String[]{"completed"},
                null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String taskId = c.getString(0);
                        String aid = c.getString(1);
                        String cid = c.getString(2);
                        int total = c.getInt(3);

                        File chapterDir = new File(baseDir,
                            aid + File.separator + cid);
                        if (!chapterDir.isDirectory()) {
                            ContentValues cv = new ContentValues();
                            cv.put(COL_STATUS, "failed");
                            cv.put(COL_ERROR, "Download file directory missing");
                            db.update(TABLE_TASKS, cv,
                                COL_TASK_ID + " = ?", new String[]{taskId});
                            Log.w(TAG, "Startup check: mark " + taskId + " failed (dir missing)");
                            continue;
                        }

                        // 检查 meta.json 是否存在作为快速校验
                        File metaFile = new File(chapterDir, "meta.json");
                        if (!metaFile.exists()) {
                            ContentValues cv = new ContentValues();
                            cv.put(COL_STATUS, "failed");
                            cv.put(COL_ERROR, "meta.json missing");
                            db.update(TABLE_TASKS, cv,
                                COL_TASK_ID + " = ?", new String[]{taskId});
                            Log.w(TAG, "Startup check: mark " + taskId + " failed (meta missing)");
                        }

                        // 检查图片文件数量
                        File[] imageFiles = chapterDir.listFiles(
                            (dir, name) -> !name.equals("meta.json") && !name.endsWith(".tmp"));
                        int fileCount = imageFiles != null ? imageFiles.length : 0;
                        if (fileCount < total) {
                            ContentValues cv = new ContentValues();
                            cv.put(COL_STATUS, "failed");
                            cv.put(COL_ERROR, "Missing images: " + fileCount + "/" + total);
                            db.update(TABLE_TASKS, cv,
                                COL_TASK_ID + " = ?", new String[]{taskId});
                            Log.w(TAG, "Startup check: mark " + taskId
                                + " failed (images " + fileCount + "/" + total + ")");
                        }
                    }
                } finally {
                    c.close();
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ========== 辅助 ==========

    private JSONObject cursorToTaskJson(Cursor c) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("taskId", c.getString(c.getColumnIndexOrThrow(COL_TASK_ID)));
            obj.put("albumId", c.getString(c.getColumnIndexOrThrow(COL_ALBUM_ID)));
            obj.put("chapterId", c.getString(c.getColumnIndexOrThrow(COL_CHAPTER_ID)));
            obj.put("albumTitle", c.getString(c.getColumnIndexOrThrow(COL_ALBUM_TITLE)));
            obj.put("chapterTitle", c.getString(c.getColumnIndexOrThrow(COL_CHAPTER_TITLE)));
            obj.put("coverUrl", c.getString(c.getColumnIndexOrThrow(COL_COVER_URL)));
            int firstSOIdx = c.getColumnIndex(COL_FIRST_IMAGE_SORT_ORDER);
            if (!c.isNull(firstSOIdx)) {
                obj.put("firstImageSortOrder", c.getInt(firstSOIdx));
            }
            obj.put("totalPages", c.getInt(c.getColumnIndexOrThrow(COL_TOTAL_PAGES)));
            obj.put("downloadedPages", c.getInt(c.getColumnIndexOrThrow(COL_DOWNLOADED_PAGES)));
            obj.put("status", c.getString(c.getColumnIndexOrThrow(COL_STATUS)));
            int errIdx = c.getColumnIndex(COL_ERROR);
            if (!c.isNull(errIdx)) {
                obj.put("error", c.getString(errIdx));
            }
            obj.put("createdAt", c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)));
            int compIdx = c.getColumnIndex(COL_COMPLETED_AT);
            if (!c.isNull(compIdx)) {
                obj.put("completedAt", c.getLong(compIdx));
            }
            int sizeIdx = c.getColumnIndex(COL_TOTAL_SIZE);
            if (sizeIdx >= 0 && !c.isNull(sizeIdx)) {
                obj.put("totalSize", c.getLong(sizeIdx));
            }
            int sortIdx = c.getColumnIndex(COL_CHAPTER_SORT_ORDER);
            if (sortIdx >= 0 && !c.isNull(sortIdx)) {
                obj.put("chapterSortOrder", c.getInt(sortIdx));
            }
        } catch (Exception e) {
            Log.w(TAG, "转换任务记录失败", e);
            return null;
        }
        return obj;
    }
}
