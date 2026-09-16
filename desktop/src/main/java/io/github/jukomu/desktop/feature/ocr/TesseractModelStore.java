package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.bridge.ApiException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** 将随应用发布的 tessdata_fast 模型释放到用户数据目录，供原生 Tesseract 读取。 */
final class TesseractModelStore {
    private static final List<String> MODELS = List.of(
            "chi_sim.traineddata",
            "chi_sim_vert.traineddata",
            "eng.traineddata");

    private TesseractModelStore() {
    }

    static Path prepare(Path ocrDirectory) {
        Path tessdata = ocrDirectory.resolve("tessdata");
        try {
            Files.createDirectories(tessdata);
            for (String model : MODELS) {
                copyIfMissing(model, tessdata.resolve(model));
            }
            return tessdata;
        } catch (IOException exception) {
            throw new ApiException("unavailable", 503, "OCR 模型不可用");
        }
    }

    private static void copyIfMissing(String model, Path target) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) > 0) return;
        String resource = "/tessdata/" + model;
        try (InputStream input = TesseractModelStore.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("缺少 OCR 模型: " + model);
            }
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.copy(input, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
