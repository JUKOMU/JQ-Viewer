package io.github.jukomu.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/**
 * 导入 PDF 的 SQLite 数据库（引用模式——仅存路径，不复制文件）。
 * <p>
 * 表结构：imported_pdfs
 */
public class PdfImportStore extends SQLiteOpenHelper {

    private static final String TAG = "PdfImportStore";
    private static final String DB_NAME = "jq_pdf_import.db";
    private static final int DB_VERSION = 1;

    static final String TABLE_PDFS = "imported_pdfs";

    static final String COL_ID = "id";
    static final String COL_FILE_PATH = "file_path";
    static final String COL_FILE_NAME = "file_name";
    static final String COL_ALBUM_ID = "album_id";
    static final String COL_ALBUM_TITLE = "album_title";
    static final String COL_COVER_URL = "cover_url";
    static final String COL_AUTHORS = "authors";
    static final String COL_CHAPTER_SORT_ORDER = "chapter_sort_order";
    static final String COL_CREATED_AT = "created_at";
    static final String COL_FOLDER_ID = "folder_id";

    private static PdfImportStore instance;

    public static synchronized PdfImportStore getInstance(Context context) {
        if (instance == null) {
            instance = new PdfImportStore(context.getApplicationContext());
        }
        return instance;
    }

    private PdfImportStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_PDFS + " ("
            + COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
            + COL_FILE_PATH + " TEXT NOT NULL UNIQUE,"
            + COL_FILE_NAME + " TEXT NOT NULL,"
            + COL_ALBUM_ID + " TEXT NOT NULL,"
            + COL_ALBUM_TITLE + " TEXT NOT NULL DEFAULT '',"
            + COL_COVER_URL + " TEXT NOT NULL DEFAULT '',"
            + COL_AUTHORS + " TEXT NOT NULL DEFAULT '',"
            + COL_CHAPTER_SORT_ORDER + " INTEGER DEFAULT 0,"
            + COL_CREATED_AT + " INTEGER NOT NULL,"
            + COL_FOLDER_ID + " TEXT DEFAULT NULL"
            + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // ========== CRUD ==========

    public long insertPdf(String filePath, String fileName, String albumId,
                          String albumTitle, String coverUrl, String authors,
                          int chapterSortOrder, long createdAt, String folderId) {
        ContentValues cv = new ContentValues();
        cv.put(COL_FILE_PATH, filePath);
        cv.put(COL_FILE_NAME, fileName);
        cv.put(COL_ALBUM_ID, albumId);
        cv.put(COL_ALBUM_TITLE, albumTitle);
        cv.put(COL_COVER_URL, coverUrl);
        cv.put(COL_AUTHORS, authors);
        cv.put(COL_CHAPTER_SORT_ORDER, chapterSortOrder);
        cv.put(COL_CREATED_AT, createdAt);
        if (folderId != null) {
            cv.put(COL_FOLDER_ID, folderId);
        }
        return getWritableDatabase().insertWithOnConflict(
            TABLE_PDFS, null, cv, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public JSONArray getAllPdfs() {
        JSONArray arr = new JSONArray();
        Cursor c = null;
        try {
            c = getReadableDatabase().query(TABLE_PDFS, null,
                null, null, null, null,
                COL_ALBUM_ID + ", " + COL_CHAPTER_SORT_ORDER);
            while (c.moveToNext()) {
                arr.put(cursorToPdfJson(c));
            }
        } catch (Exception e) {
            Log.e(TAG, "getAllPdfs failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr;
    }

    public boolean deletePdf(long id) {
        return getWritableDatabase().delete(TABLE_PDFS,
            COL_ID + " = ?", new String[]{String.valueOf(id)}) > 0;
    }

    public long getCount() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM " + TABLE_PDFS, null);
            if (c.moveToFirst()) return c.getLong(0);
        } catch (Exception e) {
            Log.e(TAG, "getCount failed", e);
        } finally {
            if (c != null) c.close();
        }
        return 0;
    }

    private JSONObject cursorToPdfJson(Cursor c) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", c.getLong(c.getColumnIndexOrThrow(COL_ID)));
            String path = c.getString(c.getColumnIndexOrThrow(COL_FILE_PATH));
            obj.put("filePath", path);
            obj.put("fileName", c.getString(c.getColumnIndexOrThrow(COL_FILE_NAME)));
            obj.put("albumId", c.getString(c.getColumnIndexOrThrow(COL_ALBUM_ID)));
            obj.put("albumTitle", c.getString(c.getColumnIndexOrThrow(COL_ALBUM_TITLE)));
            obj.put("coverUrl", c.getString(c.getColumnIndexOrThrow(COL_COVER_URL)));
            obj.put("authors", c.getString(c.getColumnIndexOrThrow(COL_AUTHORS)));
            int orderIdx = c.getColumnIndex(COL_CHAPTER_SORT_ORDER);
            obj.put("chapterSortOrder",
                !c.isNull(orderIdx) ? c.getInt(orderIdx) : 0);
            obj.put("createdAt", c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT)));
            int folderIdx = c.getColumnIndex(COL_FOLDER_ID);
            if (!c.isNull(folderIdx)) {
                obj.put("folderId", c.getString(folderIdx));
            }
            // 文件大小
            File f = new File(path);
            obj.put("fileSize", f.length());
            // PDF 页数
            try {
                PdfRenderer renderer = new PdfRenderer(
                    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY));
                obj.put("pageCount", renderer.getPageCount());
                renderer.close();
            } catch (Exception ignored) {
                // 非 PDF 或损坏文件
            }
        } catch (Exception e) {
            Log.e(TAG, "cursorToPdfJson failed", e);
        }
        return obj;
    }
}
