package io.github.jukomu.feature.pdf.data;

import java.io.File;
import java.io.IOException;

/**
 * PDF 文件与目录引用的唯一编码规则。
 *
 * <p>引用只由 kind、provider 和原始 payload 组成，不携带版本、编码或权限快照。
 * path 引用仅在创建时规范化；解析已有引用时只校验边界，避免改变持久化值。</p>
 */
public final class PdfRef {
    public enum Kind { FILE, FOLDER }

    public enum Provider { PATH, SAF }

    public static final class Parsed {
        public final Kind kind;
        public final Provider provider;
        public final String payload;

        private Parsed(Kind kind, Provider provider, String payload) {
            this.kind = kind;
            this.provider = provider;
            this.payload = payload;
        }
    }

    private PdfRef() {
    }

    public static Parsed parse(String ref) {
        if (ref == null || ref.isEmpty()) {
            throw new IllegalArgumentException("PDF 引用不能为空");
        }
        int firstSeparator = ref.indexOf(':');
        int secondSeparator = firstSeparator < 0 ? -1 : ref.indexOf(':', firstSeparator + 1);
        if (firstSeparator <= 0 || secondSeparator <= firstSeparator + 1
            || secondSeparator >= ref.length() - 1) {
            throw new IllegalArgumentException("PDF 引用格式无效");
        }

        String kindText = ref.substring(0, firstSeparator);
        String providerText = ref.substring(firstSeparator + 1, secondSeparator);
        String payload = ref.substring(secondSeparator + 1);
        Kind kind;
        Provider provider;
        if ("file".equals(kindText)) kind = Kind.FILE;
        else if ("folder".equals(kindText)) kind = Kind.FOLDER;
        else throw new IllegalArgumentException("PDF 引用类型无效");
        if ("path".equals(providerText)) provider = Provider.PATH;
        else if ("saf".equals(providerText)) provider = Provider.SAF;
        else throw new IllegalArgumentException("PDF 引用提供方无效");

        if (provider == Provider.PATH && !new File(payload).isAbsolute()) {
            throw new IllegalArgumentException("path 引用必须是绝对路径");
        }
        if (provider == Provider.SAF && !payload.startsWith("content://")) {
            throw new IllegalArgumentException("SAF 引用必须是 content URI");
        }
        if (provider == Provider.SAF
            && kind == Kind.FILE
            && payload.indexOf("/document/") < 0) {
            throw new IllegalArgumentException("文件 SAF 引用必须是 document URI");
        }
        if (provider == Provider.SAF
            && kind == Kind.FOLDER
            && payload.indexOf("/tree/") < 0) {
            throw new IllegalArgumentException("目录 SAF 引用必须是 tree URI");
        }
        return new Parsed(kind, provider, payload);
    }

    public static String createPathFileRef(String path) throws IOException {
        return createPathRef(Kind.FILE, path);
    }

    public static String createPathFolderRef(String path) throws IOException {
        return createPathRef(Kind.FOLDER, path);
    }

    private static String createPathRef(Kind kind, String path) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IllegalArgumentException("path 引用不能为空");
        }
        File file = new File(path);
        if (!file.isAbsolute()) {
            throw new IllegalArgumentException("path 引用必须是绝对路径");
        }
        String canonical = file.getCanonicalPath();
        return prefix(kind, Provider.PATH) + canonical;
    }

    public static String createSafFileRef(String documentUri) {
        return createSafRef(Kind.FILE, documentUri);
    }

    public static String createSafFolderRef(String treeUri) {
        return createSafRef(Kind.FOLDER, treeUri);
    }

    private static String createSafRef(Kind kind, String uri) {
        if (uri == null || uri.isEmpty() || !uri.startsWith("content://")
            || (kind == Kind.FILE && uri.indexOf("/document/") < 0)
            || (kind == Kind.FOLDER && uri.indexOf("/tree/") < 0)) {
            throw new IllegalArgumentException("SAF 引用必须是 content URI");
        }
        return prefix(kind, Provider.SAF) + uri;
    }

    public static String fromLegacyFileLocator(String locator) throws IOException {
        if (locator != null && locator.startsWith("content://")) {
            return createSafFileRef(locator);
        }
        return createPathFileRef(locator);
    }

    /** 迁移旧记录时只做字符串转换，避免升级事务访问文件系统或 SAF provider。 */
    public static String fromLegacyFileLocatorForMigration(String locator) {
        if (locator == null || locator.isEmpty()) {
            throw new IllegalArgumentException("旧 PDF locator 为空");
        }
        if (locator.startsWith("content://")) return createSafFileRef(locator);
        if (!new File(locator).isAbsolute()) {
            throw new IllegalArgumentException("旧 PDF locator 不是绝对路径");
        }
        return prefix(Kind.FILE, Provider.PATH) + locator;
    }

    public static String fromLegacyFolderLocator(String locator) throws IOException {
        if (locator != null && locator.startsWith("content://")) {
            return createSafFolderRef(locator);
        }
        return createPathFolderRef(locator);
    }

    public static String prefix(Kind kind, Provider provider) {
        return (kind == Kind.FILE ? "file:" : "folder:")
            + (provider == Provider.PATH ? "path:" : "saf:");
    }

    public static String payload(String ref) {
        return parse(ref).payload;
    }

    public static String fileName(String ref) {
        Parsed parsed = parse(ref);
        if (parsed.kind != Kind.FILE) throw new IllegalArgumentException("目录引用不能作为文件");
        if (parsed.provider == Provider.PATH) return new File(parsed.payload).getName();
        int slash = parsed.payload.lastIndexOf('/');
        return slash >= 0 && slash + 1 < parsed.payload.length()
            ? parsed.payload.substring(slash + 1) : "document.pdf";
    }
}
