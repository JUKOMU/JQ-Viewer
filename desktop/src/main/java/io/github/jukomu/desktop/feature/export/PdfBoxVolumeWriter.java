package io.github.jukomu.desktop.feature.export;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 使用 PDFBox 将下载图片顺序写入同目录临时 PDF。 */
public final class PdfBoxVolumeWriter implements PdfVolumeWriter {
    private static final float MAX_PAGE_DIMENSION = 14_400F;
    private static final float COMPRESSED_JPEG_QUALITY = 0.8F;

    @Override
    public void write(
            List<Path> images,
            Path temporaryFile,
            boolean useOriginal,
            double compressionRatio,
            Progress progress
    ) throws Exception {
        Files.createDirectories(temporaryFile.getParent());
        Files.deleteIfExists(temporaryFile);
        try (PDDocument document = new PDDocument()) {
            int written = 0;
            for (Path imagePath : images) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                BufferedImage source = readImage(imagePath);
                double requestedScale = useOriginal ? 1D
                        : Math.max(0.1D, Math.min(1D, compressionRatio));
                double scale = Math.min(requestedScale, MAX_PAGE_DIMENSION
                        / Math.max(source.getWidth(), source.getHeight()));
                BufferedImage image = useOriginal ? source : scaledRgbImage(source, scale);
                float width = useOriginal
                        ? Math.max(1F, (float) (source.getWidth() * scale)) : image.getWidth();
                float height = useOriginal
                        ? Math.max(1F, (float) (source.getHeight() * scale)) : image.getHeight();
                PDPage page = new PDPage(new PDRectangle(width, height));
                document.addPage(page);
                PDImageXObject pdfImage = useOriginal
                        ? LosslessFactory.createFromImage(document, image)
                        : JPEGFactory.createFromImage(document, image, COMPRESSED_JPEG_QUALITY);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(pdfImage, 0, 0, width, height);
                }
                progress.pageWritten(++written);
            }
            document.save(temporaryFile.toFile());
        } catch (Exception | Error failure) {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static BufferedImage readImage(Path path) throws IOException {
        BufferedImage image = ImageIO.read(path.toFile());
        if (image == null) throw new IOException("PDF_IMAGE_UNREADABLE: 下载图片无法解析: "
                + path.getFileName());
        return image;
    }

    private static BufferedImage scaledRgbImage(BufferedImage source, double scale) {
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }
}
