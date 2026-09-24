package io.github.jukomu.desktop.feature.cbz;

import io.github.jukomu.desktop.feature.export.archive.ComicInfo;
import io.github.jukomu.desktop.feature.export.archive.ComicInfoCodec;
import io.github.jukomu.desktop.feature.files.FileReferences;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** CBZ 索引、校验和按页随机读取。 */
public final class CbzDocumentService {
    private static final List<String> IMAGE_EXTENSIONS = List.of(
            ".jpg", ".jpeg", ".png", ".webp", ".gif");

    private final ConcurrentHashMap<Path, CachedIndex> indexes = new ConcurrentHashMap<>();

    public Info getInfo(String fileRef) throws CbzException {
        Index index = index(fileRef);
        ComicInfo comicInfo = index.comicInfo();
        return new Info(
                index.entries().size(),
                comicInfo == null ? null : comicInfo.title(),
                comicInfo == null ? null : comicInfo.series(),
                comicInfo == null ? null : comicInfo.number(),
                comicInfo == null ? null : comicInfo.writer(),
                comicInfo == null ? null : comicInfo.web(),
                index.coverPage(),
                index.metadataWarning()
        );
    }

    public ValidationReport validate(String fileRef, int expectedPages) throws CbzException {
        Path file = FileReferences.parseFile(fileRef);
        Index index = index(fileRef);
        if (expectedPages > 0 && index.entries().size() != expectedPages) {
            throw new CbzException("CBZ_PAGE_MISMATCH", 422,
                    "CBZ 页数与文件库记录不一致");
        }
        try {
            return new ValidationReport(Files.size(file), index.entries().size());
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    public PageResource openPage(String fileRef, int page) throws CbzException {
        Path file = FileReferences.parseFile(fileRef);
        Index index = index(fileRef);
        if (page < 1 || page > index.entries().size()) {
            throw new CbzException("CBZ_PAGE_OUT_OF_RANGE", 404, "CBZ 页面不存在");
        }
        String entryName = index.entries().get(page - 1);
        try {
            ZipFile zip = new ZipFile(file.toFile());
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                zip.close();
                indexes.remove(file);
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
            return new PageResource(owned, mimeType(entryName), Math.max(0L, entry.getSize()));
        } catch (CbzException exception) {
            throw exception;
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    private Index index(String fileRef) throws CbzException {
        Path file = FileReferences.parseFile(fileRef);
        try {
            long size = Files.size(file);
            long modified = Files.getLastModifiedTime(file).toMillis();
            CachedIndex cached = indexes.get(file);
            if (cached != null && cached.size() == size && cached.modified() == modified) {
                return cached.index();
            }
            Index loaded = readIndex(file);
            indexes.put(file, new CachedIndex(size, modified, loaded));
            return loaded;
        } catch (IOException exception) {
            throw translate(exception);
        }
    }

    private static Index readIndex(Path file) throws IOException, CbzException {
        List<String> entries = new ArrayList<>();
        ComicInfo comicInfo = null;
        String metadataWarning = null;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry comicInfoEntry = null;
            var iterator = zip.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
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
                    comicInfo = ComicInfoCodec.parse(input.readAllBytes());
                } catch (RuntimeException exception) {
                    metadataWarning = "ComicInfo.xml 无法解析，已改用文件名关联";
                }
            }
        } catch (ZipException exception) {
            throw new CbzException("CBZ_INVALID", 422, "CBZ 无法打开或已加密", exception);
        }
        return new Index(List.copyOf(entries), comicInfo, metadataWarning,
                coverPage(comicInfo, entries.size()));
    }

    private static int coverPage(ComicInfo info, int pageCount) {
        if (info != null) {
            for (ComicInfo.Page page : info.pages()) {
                if ("frontcover".equalsIgnoreCase(page.type())
                        && page.image() >= 0 && page.image() < pageCount) {
                    return page.image() + 1;
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
        return IMAGE_EXTENSIONS.stream().anyMatch(lower::endsWith);
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

    private static CbzException translate(IOException exception) {
        if (exception instanceof NoSuchFileException) {
            return new CbzException("CBZ_MISSING", 404, "CBZ 文件不存在或已移动", exception);
        }
        if (exception instanceof AccessDeniedException) {
            return new CbzException("CBZ_INACCESSIBLE", 403, "CBZ 文件读取权限已失效", exception);
        }
        return new CbzException("CBZ_INVALID", 422, "CBZ 无法打开或已损坏", exception);
    }

    public record Info(
            int pageCount,
            String title,
            String series,
            String number,
            String authors,
            String web,
            int coverPage,
            String metadataWarning
    ) {
    }

    public record ValidationReport(long fileSize, int pageCount) {
    }

    public record PageResource(InputStream input, String mimeType, long length) {
    }

    private record Index(
            List<String> entries,
            ComicInfo comicInfo,
            String metadataWarning,
            int coverPage
    ) {
    }

    private record CachedIndex(long size, long modified, Index index) {
    }

    public static final class CbzException extends Exception {
        private final String code;
        private final int status;

        public CbzException(String code, int status, String message) {
            super(message);
            this.code = code;
            this.status = status;
        }

        public CbzException(String code, int status, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.status = status;
        }

        public String code() {
            return code;
        }

        public int status() {
            return status;
        }
    }
}
