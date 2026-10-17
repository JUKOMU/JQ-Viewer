package io.github.jukomu.desktop.feature.pdf.render;

import io.github.jukomu.desktop.feature.files.FileReferences;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Locale;

/**
 * 将 URL 中的 base64url FileRef 解析为原 PDF 流。
 */
public final class PdfResourceService {
    public Resource open(String encodedFileRef) throws ResourceException {
        final String fileRef;
        final Path file;
        try {
            fileRef = new String(Base64.getUrlDecoder().decode(encodedFileRef), StandardCharsets.UTF_8);
            file = FileReferences.parseFile(fileRef);
        } catch (RuntimeException exception) {
            throw new ResourceException(400, "invalid-path", "PDF 文件引用无效", exception);
        }
        if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ResourceException(400, "invalid-path", "文件扩展名不是 PDF", null);
        }
        if (!Files.exists(file)) {
            throw new ResourceException(404, "file-missing", "PDF 文件不存在", null);
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
            throw new ResourceException(403, "permission-denied", "没有权限读取 PDF", null);
        }
        try {
            BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file));
            input.mark(1024);
            byte[] header = input.readNBytes(1024);
            input.reset();
            if (!new String(header, StandardCharsets.ISO_8859_1).contains("%PDF-")) {
                input.close();
                throw new ResourceException(400, "invalid-content", "PDF 文件内容无效", null);
            }
            return new Resource(input, Files.size(file));
        } catch (NoSuchFileException exception) {
            throw new ResourceException(404, "file-missing", "PDF 文件不存在", exception);
        } catch (AccessDeniedException exception) {
            throw new ResourceException(403, "permission-denied", "没有权限读取 PDF", exception);
        } catch (ResourceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ResourceException(500, "open-failed", "PDF 文件打开失败", exception);
        }
    }

    public record Resource(InputStream input, long length) {
    }

    public static final class ResourceException extends Exception {
        private final int status;
        private final String code;

        private ResourceException(int status, String code, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }
}
