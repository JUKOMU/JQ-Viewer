package io.github.jukomu.feature.localfile.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * 将本地文件 ref 解析为当前运行时可访问的对象；不参与 ref 的持久化。
 */
public final class LocalFileRefResolver {
    private LocalFileRefResolver() {
    }

    public static ParcelFileDescriptor openReadDescriptor(Context context, String fileRef)
        throws IOException {
        LocalFileRef.Parsed parsed = requireFile(fileRef);
        if (parsed.provider == LocalFileRef.Provider.SAF) {
            ContentResolver resolver = context.getContentResolver();
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(
                Uri.parse(parsed.payload), "r");
            if (descriptor == null) throw new FileNotFoundException(parsed.payload);
            return descriptor;
        }
        File file = new File(parsed.payload);
        if (!OsConstants.S_ISREG(stat(file).st_mode)) throw new IOException("引用不是普通文件");
        if (!file.canRead()) throw new SecurityException("没有权限读取文件");
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException failure) {
            // open() 的 FileNotFoundException 也可能是权限/I/O 错误，重新确认是否确实缺失。
            stat(file);
            throw new IOException("无法打开本地文件", failure);
        }
    }

    /**
     * 以统一的可关闭流读取文件引用，调用方不需要区分 path 与 SAF。
     */
    public static InputStream openReadStream(Context context, String fileRef)
        throws IOException {
        return new ParcelFileDescriptor.AutoCloseInputStream(
            openReadDescriptor(context, fileRef));
    }

    public static DocumentFile documentFile(Context context, String ref) {
        LocalFileRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider != LocalFileRef.Provider.SAF) return null;
        if (parsed.kind == LocalFileRef.Kind.FOLDER) {
            return DocumentFile.fromTreeUri(context, Uri.parse(parsed.payload));
        }
        return DocumentFile.fromSingleUri(context, Uri.parse(parsed.payload));
    }

    public static File pathFile(String ref) {
        LocalFileRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider != LocalFileRef.Provider.PATH) return null;
        return new File(parsed.payload);
    }

    public static boolean exists(Context context, String ref) throws IOException {
        LocalFileRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider == LocalFileRef.Provider.PATH) {
            try {
                stat(new File(parsed.payload));
                return true;
            } catch (FileNotFoundException missing) {
                return false;
            }
        }
        Uri document = Uri.parse(parsed.payload);
        if (parsed.kind == LocalFileRef.Kind.FOLDER
            && !DocumentsContract.isDocumentUri(context, document)) {
            document = DocumentsContract.buildDocumentUriUsingTree(document,
                DocumentsContract.getTreeDocumentId(document));
        }
        try (Cursor cursor = queryDocument(context, document,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID)) {
            return cursor.moveToFirst();
        }
    }

    /** 查询失败不是空目录或缺失。调用方拥有返回的 cursor。 */
    public static Cursor queryDocument(Context context, Uri uri, String... columns)
        throws IOException {
        try {
            Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null);
            if (cursor == null) throw new IOException("文件提供方未返回查询结果");
            try {
                android.os.Bundle extras = cursor.getExtras();
                if (extras.getString(DocumentsContract.EXTRA_ERROR) != null
                    || extras.getBoolean(DocumentsContract.EXTRA_LOADING, false)) {
                    throw new IOException("文件提供方查询失败或尚未完成");
                }
            } catch (IOException | RuntimeException error) {
                cursor.close();
                throw error;
            }
            return cursor;
        } catch (SecurityException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("文件提供方查询失败", error);
        }
    }

    public static StructStat stat(File file) throws IOException {
        try {
            return Os.stat(file.getAbsolutePath());
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.EACCES || error.errno == OsConstants.EPERM) {
                throw new SecurityException("没有权限访问文件", error);
            }
            if (error.errno == OsConstants.ENOENT || error.errno == OsConstants.ENOTDIR) {
                FileNotFoundException missing = new FileNotFoundException(file.getAbsolutePath());
                missing.initCause(error);
                throw missing;
            }
            throw error.rethrowAsIOException();
        }
    }

    public static DocumentFile findChild(Context context, DocumentFile folder, String name)
        throws IOException {
        Uri parent = folder.getUri();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parent,
            DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = queryDocument(context, children,
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME)) {
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentFile.fromTreeUri(context,
                        DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)));
                }
            }
        }
        return null;
    }

    /** documentId 是不透明标识，只有 provider 返回的路径才可用于定位父目录。 */
    public static Uri parentDocumentUri(Context context, Uri file) throws IOException {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
            && DocumentsContract.isTreeUri(file)) {
            try {
                DocumentsContract.Path path = DocumentsContract.findDocumentPath(
                    context.getContentResolver(), file);
                if (path != null && path.getPath().size() > 1) {
                    java.util.List<String> ids = path.getPath();
                    return DocumentsContract.buildDocumentUriUsingTree(file,
                        ids.get(ids.size() - 2));
                }
            } catch (SecurityException error) {
                throw error;
            } catch (RuntimeException error) {
                throw new IOException("无法定位所在目录", error);
            }
        }
        throw new FileNotFoundException("无法定位所在目录");
    }

    public static boolean canRead(Context context, String ref) {
        LocalFileRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider == LocalFileRef.Provider.PATH) return new File(parsed.payload).canRead();
        DocumentFile document = documentFile(context, ref);
        return document != null && document.canRead();
    }

    public static Uri uri(String ref) {
        LocalFileRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider != LocalFileRef.Provider.SAF) return null;
        return Uri.parse(parsed.payload);
    }

    private static LocalFileRef.Parsed requireFile(String ref) {
        LocalFileRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.kind != LocalFileRef.Kind.FILE) throw new IllegalArgumentException("需要文件引用");
        return parsed;
    }

    private static LocalFileRef.Parsed requireFileOrFolder(String ref) {
        return LocalFileRef.parse(ref);
    }
}
