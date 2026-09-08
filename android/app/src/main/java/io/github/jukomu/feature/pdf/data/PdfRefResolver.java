package io.github.jukomu.feature.pdf.data;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/** 将 PDF ref 解析为当前运行时可访问的文件对象；不参与 ref 的持久化。 */
public final class PdfRefResolver {
    private PdfRefResolver() {
    }

    public static ParcelFileDescriptor openReadDescriptor(Context context, String fileRef)
        throws IOException {
        PdfRef.Parsed parsed = requireFile(fileRef);
        if (parsed.provider == PdfRef.Provider.SAF) {
            ContentResolver resolver = context.getContentResolver();
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(
                Uri.parse(parsed.payload), "r");
            if (descriptor == null) throw new FileNotFoundException(parsed.payload);
            return descriptor;
        }
        File file = new File(parsed.payload);
        if (!file.exists() || !file.isFile()) throw new FileNotFoundException(parsed.payload);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 以统一的可关闭流读取文件引用，调用方不需要区分 path 与 SAF。 */
    public static InputStream openReadStream(Context context, String fileRef)
        throws IOException {
        return new ParcelFileDescriptor.AutoCloseInputStream(
            openReadDescriptor(context, fileRef));
    }

    public static DocumentFile documentFile(Context context, String ref) {
        PdfRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider != PdfRef.Provider.SAF) return null;
        if (parsed.kind == PdfRef.Kind.FOLDER) {
            return DocumentFile.fromTreeUri(context, Uri.parse(parsed.payload));
        }
        return DocumentFile.fromSingleUri(context, Uri.parse(parsed.payload));
    }

    public static File pathFile(String ref) {
        PdfRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider != PdfRef.Provider.PATH) return null;
        return new File(parsed.payload);
    }

    public static boolean exists(Context context, String ref) {
        PdfRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider == PdfRef.Provider.PATH) return new File(parsed.payload).exists();
        DocumentFile document = documentFile(context, ref);
        return document != null && document.exists();
    }

    public static boolean canRead(Context context, String ref) {
        PdfRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider == PdfRef.Provider.PATH) return new File(parsed.payload).canRead();
        DocumentFile document = documentFile(context, ref);
        return document != null && document.canRead();
    }

    public static Uri uri(String ref) {
        PdfRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.provider != PdfRef.Provider.SAF) return null;
        return Uri.parse(parsed.payload);
    }

    private static PdfRef.Parsed requireFile(String ref) {
        PdfRef.Parsed parsed = requireFileOrFolder(ref);
        if (parsed.kind != PdfRef.Kind.FILE) throw new IllegalArgumentException("需要文件引用");
        return parsed;
    }

    private static PdfRef.Parsed requireFileOrFolder(String ref) {
        return PdfRef.parse(ref);
    }
}
