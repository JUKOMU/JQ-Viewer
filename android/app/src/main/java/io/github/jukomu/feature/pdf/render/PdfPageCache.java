package io.github.jukomu.feature.pdf.render;

import android.content.Context;
import android.graphics.Bitmap;

import androidx.documentfile.provider.DocumentFile;

import io.github.jukomu.feature.pdf.data.PdfRef;
import io.github.jukomu.feature.pdf.data.PdfRefResolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

/**
 * PDF 页面 PNG 的进程内磁盘缓存。
 * 页面资源只保存在 cacheDir，进程首次初始化会清理旧 PNG/tmp，写入后按固定容量淘汰。
 */
public final class PdfPageCache {
    public static final long MAX_BYTES = 128L * 1024L * 1024L;

    private static volatile PdfPageCache instance;

    private final Context context;
    private final File directory;

    private PdfPageCache(Context context) {
        this.context = context.getApplicationContext();
        this.directory = new File(this.context.getCacheDir(), "pdf-pages");
        initialize();
    }

    PdfPageCache(File directory) {
        this.context = null;
        this.directory = directory;
        initialize();
    }

    public static PdfPageCache getInstance(Context context) {
        if (instance == null) {
            synchronized (PdfPageCache.class) {
                if (instance == null) instance = new PdfPageCache(context);
            }
        }
        return instance;
    }

    public String resourceUrl(String resourceId) {
        return PdfPageResourceId.resourceUrl(resourceId);
    }

    public File fileForId(String resourceId) {
        if (!PdfPageResourceId.isValid(resourceId)) {
            throw new IllegalArgumentException("PDF 页面资源 id 无效");
        }
        return new File(directory, resourceId + ".png");
    }

    public boolean hasPage(String resourceId) {
        return fileForId(resourceId).isFile();
    }

    /** 打开已生成的 PNG，并尽力更新时间戳以维护最近访问顺序。 */
    public FileInputStream openPage(String resourceId) throws IOException {
        File file = fileForId(resourceId);
        if (!file.isFile()) throw new FileNotFoundException(file.getAbsolutePath());
        FileInputStream stream = new FileInputStream(file);
        try {
            file.setLastModified(System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            // 时间戳只用于淘汰顺序，更新失败不应影响 PNG 读取。
        }
        return stream;
    }

    /**
     * 读取 PDF 源文件的长度和修改时间。SAF provider 不提供对应元数据时使用 0，
     * 该过程只在实际渲染调用中执行，不在页面拦截线程中访问源 PDF。
     */
    public SourceStamp getSourceStamp(String fileRef) throws IOException {
        if (context == null) throw new IllegalStateException("context is required");
        PdfRef.Parsed parsed = PdfRef.parse(fileRef);
        if (parsed.kind != PdfRef.Kind.FILE) {
            throw new IllegalArgumentException("需要文件引用");
        }
        if (parsed.provider == PdfRef.Provider.PATH) {
            File file = new File(parsed.payload);
            if (!file.exists() || !file.isFile()) {
                throw new FileNotFoundException(parsed.payload);
            }
            if (!file.canRead()) throw new SecurityException(parsed.payload);
            return new SourceStamp(file.length(), file.lastModified());
        }

        DocumentFile document = PdfRefResolver.documentFile(context, fileRef);
        if (document == null || !document.exists() || !document.isFile()) {
            throw new FileNotFoundException(parsed.payload);
        }
        if (!document.canRead()) throw new SecurityException(parsed.payload);
        long length = document.length();
        long lastModified = document.lastModified();
        return new SourceStamp(Math.max(0L, length), Math.max(0L, lastModified));
    }

    /** 将 Bitmap 先写入随机 tmp，关闭后再替换为最终 PNG 文件。 */
    public void writePngAtomically(String resourceId, Bitmap bitmap) throws IOException {
        if (bitmap == null) throw new IllegalArgumentException("bitmap is required");
        File target = fileForId(resourceId);
        synchronized (this) {
            ensureDirectory();
            File temporary = File.createTempFile(resourceId + ".", ".tmp", directory);
            try {
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IOException("PDF 页面 PNG 压缩失败");
                    }
                    output.flush();
                } catch (IOException error) {
                    throw new IOException("PDF 页面缓存写入失败", error);
                }

                if (!temporary.renameTo(target)) {
                    if (!target.isFile()) {
                        throw new IOException("PDF 页面缓存文件替换失败");
                    }
                }
                enforceCapacityLocked();
            } finally {
                // 临时文件只可能由当前写入操作创建，成功替换或失败退出都应清理。
                if (temporary.exists()) temporary.delete();
            }
        }
    }

    public void clear() {
        synchronized (this) {
            deletePageFilesLocked();
        }
    }

    private void initialize() {
        synchronized (this) {
            ensureDirectory();
            deletePageFilesLocked();
        }
    }

    private void ensureDirectory() {
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("PDF 页面缓存目录不可用");
        }
    }

    private void deletePageFilesLocked() {
        File[] files = directory.listFiles((dir, name) ->
            name.endsWith(".png") || name.endsWith(".tmp"));
        if (files == null) return;
        for (File file : files) file.delete();
    }

    void enforceCapacity() {
        synchronized (this) {
            enforceCapacityLocked();
        }
    }

    private void enforceCapacityLocked() {
        File[] pages = directory.listFiles((dir, name) -> name.endsWith(".png"));
        if (pages == null || pages.length == 0) return;

        Arrays.sort(pages, Comparator
            .comparingLong(File::lastModified)
            .thenComparing(File::getName));
        long total = 0L;
        for (File page : pages) total = addSaturated(total, page.length());
        for (File page : pages) {
            if (total <= MAX_BYTES) break;
            long size = page.length();
            if (page.delete()) total = Math.max(0L, total - size);
        }
    }

    private static long addSaturated(long left, long right) {
        if (right <= 0L || left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    public static final class SourceStamp {
        public final long length;
        public final long lastModified;

        SourceStamp(long length, long lastModified) {
            this.length = length;
            this.lastModified = lastModified;
        }
    }
}
