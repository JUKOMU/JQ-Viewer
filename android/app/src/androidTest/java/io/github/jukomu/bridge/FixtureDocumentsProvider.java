package io.github.jukomu.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** 只支持 DocumentsContract 删除的测试 provider，通用 resolver.delete 不能成功。 */
public final class FixtureDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "io.github.jukomu.test.documents";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if ("children".equals(uri.getLastPathSegment())) {
            return queryChildDocuments(DocumentsContract.getDocumentId(uri), projection, order);
        }
        return queryDocument(DocumentsContract.getDocumentId(uri), projection);
    }

    public Cursor queryDocument(String id, String[] projection) {
        if (id.startsWith("query-failed")) throw new IllegalStateException("fixture query failed");
        if (id.startsWith("null-query")) return null;
        String[] columns = projection == null ? new String[]{
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        if (id.startsWith("missing") || deleted(id).exists()) return cursor;
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: row.add("fixture.pdf"); break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:
                    row.add("root".equals(id) || "opaque-parent".equals(id)
                        ? DocumentsContract.Document.MIME_TYPE_DIR : "application/pdf"); break;
                case DocumentsContract.Document.COLUMN_FLAGS:
                    row.add(DocumentsContract.Document.FLAG_SUPPORTS_DELETE); break;
                default: row.add(0L);
            }
        }
        return cursor;
    }

    public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        if (parent.startsWith("query-failed")) throw new IllegalStateException("fixture query failed");
        return new MatrixCursor(projection == null
            ? new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID} : projection);
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Uri uri = extras.getParcelable("uri");
        String id = DocumentsContract.getDocumentId(uri);
        if ("android:deleteDocument".equals(method)) {
            deleteDocument(id);
            return new Bundle();
        }
        if ("android:findDocumentPath".equals(method)
            && android.os.Build.VERSION.SDK_INT >= 26) {
            if (id.startsWith("unknown")) throw new UnsupportedOperationException("no path");
            Bundle result = new Bundle();
            result.putParcelable("result", new DocumentsContract.Path(null,
                Arrays.asList("root", "opaque-parent", id)));
            return result;
        }
        throw new UnsupportedOperationException(method);
    }

    private void deleteDocument(String id) {
        if (id.startsWith("denied")) throw new SecurityException("fixture deletion denied");
        try {
            if (!deleted(id).createNewFile()) throw new IOException("already deleted");
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("use DocumentsContract.deleteDocument");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    private File deleted(String id) {
        return new File(getContext().getCacheDir(), "deleted-" + id);
    }
}
