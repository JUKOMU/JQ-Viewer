package io.github.jukomu.jqviewer.desktop.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.jukomu.jqviewer.desktop.store.DesktopPdfStore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DesktopPdfService {
    private final DesktopPdfStore store;
    private final DesktopDownloadService downloadService;
    private final DesktopEventBroker events;
    private volatile Path pdfRootDir;
    private volatile Path pdfExportDir;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    public DesktopPdfService(DesktopPdfStore store, DesktopDownloadService downloadService,
                             DesktopEventBroker events, Path pdfRootDir, Path pdfExportDir) throws IOException {
        this.store = store;
        this.downloadService = downloadService;
        this.events = events;
        this.pdfRootDir = pdfRootDir.toAbsolutePath().normalize();
        this.pdfExportDir = pdfExportDir.toAbsolutePath().normalize();
        Files.createDirectories(this.pdfRootDir);
        Files.createDirectories(this.pdfExportDir);
    }

    public JsonObject roots() {
        JsonObject result = new JsonObject();
        result.addProperty("pdfRootDir", pdfRootDir.toString());
        result.addProperty("pdfExportDir", pdfExportDir.toString());
        return result;
    }

    public JsonObject updateRoots(JsonObject body) throws IOException {
        if (body != null && body.has("pdfRootDir") && !body.get("pdfRootDir").isJsonNull()) {
            Path nextRoot = normalizePath(body.get("pdfRootDir").getAsString());
            Files.createDirectories(nextRoot);
            pdfRootDir = nextRoot;
        }
        if (body != null && body.has("pdfExportDir") && !body.get("pdfExportDir").isJsonNull()) {
            Path nextExport = normalizePath(body.get("pdfExportDir").getAsString());
            Files.createDirectories(nextExport);
            pdfExportDir = nextExport;
        }
        return roots();
    }

    public JsonObject scan(JsonObject body) throws IOException {
        Path dir = resolvePdfRootPath(stringValue(body, "path", ""));
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("PDF scan directory not found");
        }

        JsonArray files = new JsonArray();
        try (var stream = Files.list(dir)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                .forEach(path -> {
                    JsonObject item = new JsonObject();
                    item.addProperty("fileName", path.getFileName().toString());
                    item.addProperty("filePath", path.toAbsolutePath().normalize().toString());
                    files.add(item);
                });
        }

        JsonObject result = new JsonObject();
        result.add("files", files);
        return result;
    }

    public JsonObject importPdfs(JsonObject body) throws IOException {
        JsonArray items = body != null && body.has("items") && body.get("items").isJsonArray()
            ? body.getAsJsonArray("items")
            : new JsonArray();
        int imported = 0;
        int skipped = 0;
        int duplicateCount = 0;
        int errorCount = 0;

        for (JsonElement element : items) {
            if (!element.isJsonObject()) {
                skipped++;
                errorCount++;
                continue;
            }
            JsonObject item = element.getAsJsonObject();
            try {
                Path file = resolvePdfRootPath(stringValue(item, "filePath", ""));
                if (!Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                    skipped++;
                    errorCount++;
                    continue;
                }
                if (store.containsPath(file)) {
                    skipped++;
                    duplicateCount++;
                    continue;
                }

                JsonObject pdf = new JsonObject();
                pdf.addProperty("filePath", file.toString());
                pdf.addProperty("fileName", stringValue(item, "fileName", file.getFileName().toString()));
                pdf.addProperty("albumId", stringValue(item, "albumId", ""));
                pdf.addProperty("albumTitle", stringValue(item, "albumTitle", ""));
                pdf.addProperty("coverUrl", stringValue(item, "coverUrl", ""));
                pdf.addProperty("authors", stringValue(item, "authors", ""));
                pdf.addProperty("chapterId", stringValue(item, "chapterId", ""));
                pdf.addProperty("chapterTitle", stringValue(item, "chapterTitle", ""));
                pdf.addProperty("chapterSortOrder", intValue(item, "chapterSortOrder", 0));
                if (item.has("isSingleEpisode") && !item.get("isSingleEpisode").isJsonNull()) {
                    pdf.addProperty("isSingleEpisode", item.get("isSingleEpisode").getAsBoolean());
                }
                pdf.addProperty("createdAt", System.currentTimeMillis());
                if (item.has("folderId") && !item.get("folderId").isJsonNull()) {
                    pdf.addProperty("folderId", item.get("folderId").getAsString());
                }
                pdf.addProperty("fileSize", Files.size(file));
                pdf.addProperty("pageCount", pageCount(file));

                JsonObject inserted = store.insert(pdf);
                if (inserted == null) {
                    skipped++;
                    duplicateCount++;
                } else {
                    imported++;
                }
            } catch (Exception e) {
                skipped++;
                errorCount++;
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("imported", imported);
        result.addProperty("skipped", skipped);
        result.addProperty("duplicateCount", duplicateCount);
        result.addProperty("errorCount", errorCount);
        return result;
    }

    public JsonObject importedPdfs() {
        JsonArray pdfs = new JsonArray();
        for (JsonObject pdf : store.allPdfs()) {
            pdfs.add(pdf);
        }
        JsonObject result = new JsonObject();
        result.add("pdfs", pdfs);
        return result;
    }

    public JsonObject deleteImportedPdf(long id) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("success", store.delete(id));
        return result;
    }

    public JsonObject updateAlbumEpisodeType(String albumId, boolean isSingleEpisode) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("updatedPdfs", store.updateAlbumEpisodeType(albumId, isSingleEpisode));
        return result;
    }

    public JsonObject checkFiles(JsonObject body) {
        JsonArray paths = body != null && body.has("paths") && body.get("paths").isJsonArray()
            ? body.getAsJsonArray("paths")
            : new JsonArray();
        JsonArray existing = new JsonArray();
        for (JsonElement element : paths) {
            try {
                Path path = resolveAnyKnownPath(element.getAsString());
                if (Files.exists(path)) {
                    existing.add(element.getAsString());
                }
            } catch (Exception ignored) {
            }
        }
        JsonObject result = new JsonObject();
        result.add("existing", existing);
        return result;
    }

    public JsonObject info(String path) throws IOException {
        JsonObject result = new JsonObject();
        result.addProperty("pageCount", pageCount(resolveReadablePdf(path)));
        return result;
    }

    public byte[] readPdf(String path) throws IOException {
        return Files.readAllBytes(resolveReadablePdf(path));
    }

    public ImageResource renderPage(String path, int page, int targetWidth) throws IOException {
        Path file = resolveReadablePdf(path);
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            int pageIndex = Math.max(0, Math.min(document.getNumberOfPages() - 1, page - 1));
            PDRectangle box = document.getPage(pageIndex).getCropBox();
            float width = Math.max(1f, box.getWidth());
            float scale = Math.max(1f, Math.min(4f, targetWidth / width));
            BufferedImage image = new PDFRenderer(document).renderImage(pageIndex, scale, ImageType.RGB);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return new ImageResource(output.toByteArray(), "image/png");
        }
    }

    public JsonObject exportPdfBatch(JsonObject body) {
        JsonArray tasks = body != null && body.has("tasks") && body.get("tasks").isJsonArray()
            ? body.getAsJsonArray("tasks")
            : new JsonArray();
        List<JsonObject> queued = new ArrayList<>();
        for (JsonElement element : tasks) {
            if (element.isJsonObject()) {
                queued.add(element.getAsJsonObject().deepCopy());
            }
        }
        if (!queued.isEmpty()) {
            executor.submit(() -> runExportBatch(queued));
        }
        JsonObject result = new JsonObject();
        result.addProperty("accepted", true);
        return result;
    }

    public void stop() {
        executor.shutdownNow();
    }

    private void runExportBatch(List<JsonObject> tasks) {
        for (JsonObject task : tasks) {
            String taskId = "pdf_" + safeTaskPart(stringValue(task, "albumId", ""))
                + "_" + safeTaskPart(stringValue(task, "chapterId", ""));
            try {
                publish(taskId, task, "queued", 0, 0, null);
                exportOne(taskId, task);
            } catch (Exception e) {
                publish(taskId, task, "failed", 0, 0, e.getMessage());
            }
        }
    }

    private void exportOne(String taskId, JsonObject task) throws IOException {
        String albumId = stringValue(task, "albumId", "");
        String chapterId = stringValue(task, "chapterId", "");
        JsonObject photo = downloadService.getDownloadedPhoto(albumId, chapterId);
        JsonArray images = photo.has("images") && photo.get("images").isJsonArray()
            ? photo.getAsJsonArray("images")
            : new JsonArray();
        int total = images.size();
        if (total == 0) {
            throw new IllegalArgumentException("downloaded chapter has no images");
        }

        int splitPages = Math.max(0, intValue(task, "splitPages", 0));
        if (splitPages <= 0) {
            exportRange(taskId, task, images, 0, total, resolveExportPath(stringValue(task, "savePath", "")));
            return;
        }

        Path base = resolveExportPath(stringValue(task, "savePath", ""));
        String fileName = base.getFileName().toString();
        String baseName = fileName.toLowerCase(Locale.ROOT).endsWith(".pdf")
            ? fileName.substring(0, fileName.length() - 4)
            : fileName;
        Path parent = base.getParent();
        for (int start = 0; start < total; start += splitPages) {
            int end = Math.min(total, start + splitPages);
            Path volume = parent.resolve(String.format(Locale.ROOT, "%s_%03d-%03d.pdf", baseName, start + 1, end));
            exportRange(taskId, task, images, start, end, volume);
        }
    }

    private void exportRange(String taskId, JsonObject task, JsonArray images,
                             int start, int end, Path outputFile) throws IOException {
        Files.createDirectories(outputFile.getParent());
        try (PDDocument document = new PDDocument()) {
            for (int i = start; i < end; i++) {
                JsonObject image = images.get(i).getAsJsonObject();
                int sortOrder = intValue(image, "sortOrder", i + 1);
                DesktopImageCache.ImageResource resource =
                    downloadService.getDownloadedImage(stringValue(task, "chapterId", ""), sortOrder);
                if (resource == null) continue;
                byte[] bytes = preparePdfImageBytes(resource.data(), boolValue(task, "useOriginal", true),
                    doubleValue(task, "compressionRatio", 1.0));
                PDImageXObject pdImage = PDImageXObject.createFromByteArray(document, bytes, "page-" + sortOrder);
                PDPage page = new PDPage(new PDRectangle(pdImage.getWidth(), pdImage.getHeight()));
                document.addPage(page);
                try (var content = new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                    content.drawImage(pdImage, 0, 0, pdImage.getWidth(), pdImage.getHeight());
                }
                publish(taskId, task, "downloading", i + 1, images.size(), null);
            }
            document.save(outputFile.toFile());
        }
        publish(taskId, task, "completed", images.size(), images.size(), null);
    }

    private byte[] preparePdfImageBytes(byte[] source, boolean useOriginal, double ratio) throws IOException {
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(source));
        if (decoded == null) {
            throw new IOException("unsupported image format for PDF export");
        }
        BufferedImage outputImage = decoded;
        if (!useOriginal && ratio < 0.999) {
            int width = Math.max(1, (int) Math.round(decoded.getWidth() * ratio));
            int height = Math.max(1, (int) Math.round(decoded.getHeight() * ratio));
            outputImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = outputImage.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(decoded, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
        } else if (decoded.getType() != BufferedImage.TYPE_INT_RGB) {
            BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = rgb.createGraphics();
            try {
                graphics.drawImage(decoded, 0, 0, null);
            } finally {
                graphics.dispose();
            }
            outputImage = rgb;
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(outputImage, "png", output);
        return output.toByteArray();
    }

    private void publish(String taskId, JsonObject task, String status, int current, int total, String error) {
        JsonObject event = new JsonObject();
        event.addProperty("taskId", taskId);
        event.addProperty("albumId", stringValue(task, "albumId", ""));
        event.addProperty("chapterId", stringValue(task, "chapterId", ""));
        event.addProperty("albumTitle", stringValue(task, "albumTitle", stringValue(task, "albumId", "")));
        event.addProperty("chapterTitle", stringValue(task, "chapterTitle", stringValue(task, "chapterId", "")));
        event.addProperty("downloadedPages", current);
        event.addProperty("totalPages", total);
        event.addProperty("status", status);
        event.addProperty("speed", 0);
        event.addProperty("downloadedBytes", 0);
        event.addProperty("totalSize", 0);
        if (error != null && !error.isBlank()) {
            event.addProperty("error", error);
        }
        events.publishDownloadProgress(event);
    }

    private int pageCount(Path file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.toFile())) {
            return document.getNumberOfPages();
        }
    }

    private Path resolveReadablePdf(String rawPath) {
        Path file = resolveAnyKnownPath(rawPath);
        if (!Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new IllegalArgumentException("PDF file not found");
        }
        return file;
    }

    private Path resolveAnyKnownPath(String rawPath) {
        Path path = normalizePath(rawPath);
        if (path.startsWith(pdfRootDir) || path.startsWith(pdfExportDir) || store.containsPath(path)) {
            return path;
        }
        throw new IllegalArgumentException("PDF path is outside allowed directories");
    }

    private Path resolvePdfRootPath(String rawPath) {
        Path path = rawPath == null || rawPath.isBlank()
            ? pdfRootDir
            : normalizePath(rawPath);
        if (!path.startsWith(pdfRootDir)) {
            throw new IllegalArgumentException("PDF path is outside configured import root");
        }
        return path;
    }

    private Path resolveExportPath(String rawPath) {
        String raw = rawPath == null || rawPath.isBlank() ? "export.pdf" : rawPath;
        Path path = Path.of(raw);
        Path resolved = path.isAbsolute()
            ? path.toAbsolutePath().normalize()
            : pdfExportDir.resolve(path).toAbsolutePath().normalize();
        if (!resolved.startsWith(pdfExportDir)) {
            throw new IllegalArgumentException("PDF export path is outside configured export directory");
        }
        if (!resolved.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            resolved = resolved.resolveSibling(resolved.getFileName() + ".pdf");
        }
        return resolved;
    }

    private static Path normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required");
        }
        return Path.of(rawPath).toAbsolutePath().normalize();
    }

    private static String safeTaskPart(String value) {
        String raw = value == null || value.isBlank() ? "unknown" : value;
        return raw.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String stringValue(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int intValue(JsonObject obj, String key, int fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double doubleValue(JsonObject obj, String key, double fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsDouble();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static boolean boolValue(JsonObject obj, String key, boolean fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    public record ImageResource(byte[] data, String mimeType) {
    }
}
