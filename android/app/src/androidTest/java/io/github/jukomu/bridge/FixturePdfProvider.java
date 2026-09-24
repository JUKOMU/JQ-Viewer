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
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 测试专用文档 provider，用于验证 PdfServer 的 SAF FileRef 读取链路。 */
public final class FixturePdfProvider extends ContentProvider {
    private static final String PATH = "/document/fixture";
    private static final String CBZ_PATH = "/document/fixture-cbz";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public String getType(Uri uri) {
        return CBZ_PATH.equals(uri.getPath()) ? "application/vnd.comicbook+zip" : "application/pdf";
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ((!PATH.equals(uri.getPath()) && !CBZ_PATH.equals(uri.getPath())) || !mode.contains("r")) {
            throw new FileNotFoundException(uri.toString());
        }
        if (getContext() == null) throw new FileNotFoundException("provider context unavailable");
        boolean cbz = CBZ_PATH.equals(uri.getPath());
        File file = new File(getContext().getCacheDir(), cbz ? "fixture.cbz" : "fixture.pdf");
        try {
            if (cbz) writeCbzFixture(file);
            else writeFixture(file);
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

    private static void writeCbzFixture(File file) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file))) {
            put(output, "ComicInfo.xml", "<ComicInfo><Title>SAF CBZ</Title></ComicInfo>");
            put(output, "001.jpg", "first");
            put(output, "002.png", "second");
        }
    }

    private static void put(ZipOutputStream output, String name, String content) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(content.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
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
