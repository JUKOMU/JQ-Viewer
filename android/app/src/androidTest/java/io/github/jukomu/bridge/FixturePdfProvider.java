package io.github.jukomu.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

/** 测试专用文档 provider，用于验证 PdfServer 的 SAF FileRef 读取链路。 */
public final class FixturePdfProvider extends ContentProvider {
    private static final String PATH = "/document/fixture";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!PATH.equals(uri.getPath()) || !mode.contains("r")) {
            throw new FileNotFoundException(uri.toString());
        }
        if (getContext() == null) throw new FileNotFoundException("provider context unavailable");
        File file = new File(getContext().getCacheDir(), "fixture.pdf");
        try {
            writeFixture(file);
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static void writeFixture(File file) throws IOException {
        PdfDocument document = new PdfDocument();
        try {
            for (int pageNumber = 1; pageNumber <= 2; pageNumber++) {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(
                    100, 100, pageNumber).create());
                page.getCanvas().drawColor(Color.WHITE);
                document.finishPage(page);
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                document.writeTo(output);
            }
        } finally {
            document.close();
        }
    }

    @Nullable
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
