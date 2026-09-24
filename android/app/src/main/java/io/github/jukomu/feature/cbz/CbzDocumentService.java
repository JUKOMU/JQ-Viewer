package io.github.jukomu.feature.cbz;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.os.StatFs;
import io.github.jukomu.feature.export.archive.ComicInfo;
import io.github.jukomu.feature.export.archive.ComicInfoCodec;
import io.github.jukomu.feature.localfile.data.LocalFileRef;
import io.github.jukomu.feature.localfile.data.LocalFileRefResolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** Android CBZ 索引、SAF 受控缓存、校验和按页随机读取。 */
public final class CbzDocumentService {
    private static final long STALE_CACHE_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final List<String> IMAGE_EXTENSIONS = List.of(
        ".jpg", ".jpeg", ".png", ".webp", ".gif");
    private static CbzDocumentService instance;

    private final Context context;
    private final File cacheDirectory;
    private final Map<String, CachedIndex> indexes = new HashMap<>();

    private CbzDocumentService(Context context) {
        this.context = context.getApplicationContext();
        this.cacheDirectory = new File(this.context.getCacheDir(), "cbz-archives");
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建 CBZ 缓存目录");
        }
        cleanStaleCache();
    }

    public static synchronized CbzDocumentService getInstance(Context context) {
        if (instance == null) instance = new CbzDocumentService(context);
        return instance;
    }

    public static synchronized void clearInstanceForTest() {
        instance = null;
    }

    public synchronized Info getInfo(String fileRef) throws CbzException {
        Index index = index(fileRef, true);
        ComicInfo info = index.comicInfo;
        return new Info(
            index.entries.size(),
            info == null ? null : info.title,
            info == null ? null : info.series,
            info == null ? null : info.number,
            info == null ? null : info.writer,
            info == null ? null : info.web,
            index.coverPage,
            index.metadataWarning
        );
    }

    public synchronized ValidationReport validate(String fileRef, int expectedPages)
        throws CbzException {
        File file = materialize(fileRef, true);
        Index index = indexForFile(fileRef, file);
        if (expectedPages > 0 && index.entries.size() != expectedPages) {
            throw new CbzException("CBZ_PAGE_MISMATCH", 422,
                "CBZ 页数与文件库记录不一致");
        }
        return new ValidationReport(file.length(), index.entries.size());
    }

    public synchronized PageResource openPage(String fileRef, int page) throws CbzException {
        File file = materialize(fileRef, false);
        Index index = indexForFile(fileRef, file);
        if (page < 1 || page > index.entries.size()) {
            throw new CbzException("CBZ_PAGE_OUT_OF_RANGE", 404, "CBZ 页面不存在");
        }
        String name = index.entries.get(page - 1);
        try {
            ZipFile zip = new ZipFile(file);
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                zip.close();
                indexes.remove(fileRef);
                throw new CbzException("CBZ_INVALID", 422, "CBZ 页面条目缺失");
            }
            InputStream input = zip.getInputStream(entry);
            InputStream owned = new FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        zip.close();
                    }
                }
            };
            return new PageResource(owned, mimeType(name), Math.max(0L, entry.getSize()));
        } catch (CbzException error) {
            throw error;
        } catch (IOException error) {
            throw translate(error);
        }
    }

    private Index index(String fileRef, boolean refreshSaf) throws CbzException {
        File file = materialize(fileRef, refreshSaf);
        return indexForFile(fileRef, file);
    }

    private Index indexForFile(String fileRef, File file) throws CbzException {
        long size = file.length();
        long modified = file.lastModified();
        CachedIndex cached = indexes.get(fileRef);
        if (cached != null && cached.size == size && cached.modified == modified) {
            return cached.index;
        }
        Index loaded = readIndex(file);
        indexes.put(fileRef, new CachedIndex(size, modified, loaded));
        return loaded;
    }

    private File materialize(String fileRef, boolean refreshSaf) throws CbzException {
        LocalFileRef.Parsed parsed;
        try {
            parsed = LocalFileRef.parse(fileRef);
        } catch (IllegalArgumentException error) {
            throw new CbzException("CBZ_INVALID_REF", 400, "CBZ 文件引用无效", error);
        }
        if (parsed.kind != LocalFileRef.Kind.FILE) {
            throw new CbzException("CBZ_INVALID_REF", 400, "需要 CBZ 文件引用");
        }
        if (parsed.provider == LocalFileRef.Provider.PATH) {
            try {
                File file = LocalFileRefResolver.pathFile(fileRef);
                if (!file.isFile()) {
                    throw new CbzException("CBZ_MISSING", 404, "CBZ 文件不存在或已移动");
                }
                return file;
            } catch (CbzException error) {
                throw error;
            } catch (Exception error) {
                throw new CbzException("CBZ_INVALID_REF", 400, "CBZ 文件引用无效", error);
            }
        }

        File cached = new File(cacheDirectory, digest(fileRef) + ".cbz");
        if (!refreshSaf && cached.isFile()) {
            cached.setLastModified(System.currentTimeMillis());
            return cached;
        }
        copySafToCache(fileRef, cached);
        indexes.remove(fileRef);
        return cached;
    }

    private void copySafToCache(String fileRef, File target) throws CbzException {
        long declaredSize = -1L;
        try (AssetFileDescriptor descriptor = context.getContentResolver()
            .openAssetFileDescriptor(LocalFileRefResolver.uri(fileRef), "r")) {
            if (descriptor != null) declaredSize = descriptor.getLength();
        } catch (SecurityException error) {
            throw new CbzException("CBZ_INACCESSIBLE", 403,
                "CBZ 文件读取权限已失效，请重新导入", error);
        } catch (Exception ignored) {
            // 部分 SAF 提供方不报告长度，复制流仍可正常工作。
        }
        if (declaredSize > 0L) {
            long available = new StatFs(cacheDirectory.getAbsolutePath()).getAvailableBytes();
            if (available < declaredSize) {
                throw new CbzException("CBZ_CACHE_FULL", 507, "缓存空间不足，无法读取 CBZ");
            }
        }

        File temporary;
        try {
            temporary = File.createTempFile(target.getName(), ".tmp", cacheDirectory);
        } catch (IOException error) {
            throw translate(error);
        }
        try (InputStream input = LocalFileRefResolver.openReadStream(context, fileRef);
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
            output.getFD().sync();
            if (target.exists() && !target.delete()) {
                throw new IOException("无法替换旧 CBZ 缓存");
            }
            if (!temporary.renameTo(target)) {
                throw new IOException("无法发布 CBZ 缓存");
            }
        } catch (SecurityException error) {
            temporary.delete();
            throw new CbzException("CBZ_INACCESSIBLE", 403,
                "CBZ 文件读取权限已失效，请重新导入", error);
        } catch (FileNotFoundException error) {
            temporary.delete();
            throw new CbzException("CBZ_MISSING", 404, "CBZ 文件不存在或已移动", error);
        } catch (IOException error) {
            temporary.delete();
            throw translate(error);
        }
    }

    private static Index readIndex(File file) throws CbzException {
        List<String> entries = new ArrayList<>();
        ComicInfo comicInfo = null;
        String warning = null;
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry comicInfoEntry = null;
            Enumeration<? extends ZipEntry> values = zip.entries();
            while (values.hasMoreElements()) {
                ZipEntry entry = values.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || hidden(name)) continue;
                if (!name.contains("/") && "comicinfo.xml".equalsIgnoreCase(name)) {
                    comicInfoEntry = entry;
                } else if (isImage(name)) {
                    entries.add(name);
                }
            }
            entries.sort(CbzDocumentService::naturalCompare);
            if (entries.isEmpty()) {
                throw new CbzException("CBZ_NO_IMAGES", 422, "CBZ 中没有受支持的图片");
            }
            for (String name : entries) {
                try (InputStream input = zip.getInputStream(zip.getEntry(name))) {
                    input.read();
                }
            }
            if (comicInfoEntry != null) {
                try (InputStream input = zip.getInputStream(comicInfoEntry)) {
                    comicInfo = ComicInfoCodec.parse(readAll(input));
                } catch (RuntimeException error) {
                    warning = "ComicInfo.xml 无法解析，已改用文件名关联";
                }
            }
        } catch (CbzException error) {
            throw error;
        } catch (ZipException error) {
            throw new CbzException("CBZ_INVALID", 422, "CBZ 无法打开或已加密", error);
        } catch (IOException error) {
            throw translate(error);
        }
        return new Index(Collections.unmodifiableList(entries), comicInfo, warning,
            coverPage(comicInfo, entries.size()));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private void cleanStaleCache() {
        File[] files = cacheDirectory.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - STALE_CACHE_MS;
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) file.delete();
        }
    }

    private static int coverPage(ComicInfo info, int pageCount) {
        if (info != null) {
            for (ComicInfo.Page page : info.pages) {
                if ("frontcover".equalsIgnoreCase(page.type)
                    && page.image >= 0 && page.image < pageCount) {
                    return page.image + 1;
                }
            }
        }
        return 1;
    }

    private static boolean hidden(String name) {
        for (String segment : name.replace('\\', '/').split("/")) {
            if (segment.startsWith(".")) return true;
        }
        return false;
    }

    private static boolean isImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : IMAGE_EXTENSIONS) {
            if (lower.endsWith(extension)) return true;
        }
        return false;
    }

    static int naturalCompare(String left, String right) {
        int a = 0;
        int b = 0;
        while (a < left.length() && b < right.length()) {
            char ca = left.charAt(a);
            char cb = right.charAt(b);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ae = a;
                int be = b;
                while (ae < left.length() && Character.isDigit(left.charAt(ae))) ae++;
                while (be < right.length() && Character.isDigit(right.charAt(be))) be++;
                String an = left.substring(a, ae).replaceFirst("^0+(?!$)", "");
                String bn = right.substring(b, be).replaceFirst("^0+(?!$)", "");
                int result = Integer.compare(an.length(), bn.length());
                if (result == 0) result = an.compareTo(bn);
                if (result != 0) return result;
                a = ae;
                b = be;
                continue;
            }
            int result = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
            if (result != 0) return result;
            a++;
            b++;
        }
        return Integer.compare(left.length(), right.length());
    }

    private static String mimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    private static String digest(String value) throws CbzException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte current : digest) text.append(String.format(Locale.ROOT, "%02x", current));
            return text.toString();
        } catch (Exception error) {
            throw new CbzException("CBZ_CACHE_FAILED", 500, "无法建立 CBZ 缓存", error);
        }
    }

    private static CbzException translate(IOException error) {
        return new CbzException("CBZ_INVALID", 422, "CBZ 无法打开或已损坏", error);
    }

    public static final class Info {
        public final int pageCount;
        public final String title;
        public final String series;
        public final String number;
        public final String authors;
        public final String web;
        public final int coverPage;
        public final String metadataWarning;

        Info(int pageCount, String title, String series, String number, String authors,
             String web, int coverPage, String metadataWarning) {
            this.pageCount = pageCount;
            this.title = title;
            this.series = series;
            this.number = number;
            this.authors = authors;
            this.web = web;
            this.coverPage = coverPage;
            this.metadataWarning = metadataWarning;
        }
    }

    public static final class ValidationReport {
        public final long fileSize;
        public final int pageCount;

        ValidationReport(long fileSize, int pageCount) {
            this.fileSize = fileSize;
            this.pageCount = pageCount;
        }
    }

    public static final class PageResource {
        public final InputStream input;
        public final String mimeType;
        public final long length;

        PageResource(InputStream input, String mimeType, long length) {
            this.input = input;
            this.mimeType = mimeType;
            this.length = length;
        }
    }

    private static final class Index {
        final List<String> entries;
        final ComicInfo comicInfo;
        final String metadataWarning;
        final int coverPage;

        Index(List<String> entries, ComicInfo comicInfo, String metadataWarning, int coverPage) {
            this.entries = entries;
            this.comicInfo = comicInfo;
            this.metadataWarning = metadataWarning;
            this.coverPage = coverPage;
        }
    }

    private static final class CachedIndex {
        final long size;
        final long modified;
        final Index index;

        CachedIndex(long size, long modified, Index index) {
            this.size = size;
            this.modified = modified;
            this.index = index;
        }
    }

    public static final class CbzException extends IOException {
        public final String code;
        public final int status;

        CbzException(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        CbzException(String code, int status, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.status = status;
        }
    }
}
