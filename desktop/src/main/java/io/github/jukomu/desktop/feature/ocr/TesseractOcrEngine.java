package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.ocr.model.OcrResponse;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.tesseract.TessBaseAPI;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 基于 JavaCPP 随包原生程序的 Tesseract fast OCR 实现。
 */
public final class TesseractOcrEngine implements OcrEngine {
    static final long MAX_IMAGE_BYTES = 32L * 1024L * 1024L;

    private static final String LANGUAGES = "chi_sim+chi_sim_vert+eng";
    private static final long MAX_OUTPUT_BYTES = 8L * 1024L * 1024L;
    private static final int TIMEOUT_SECONDS = 30;

    private final Path ocrDirectory;
    private Path executable;
    private boolean closed;

    public TesseractOcrEngine(Path ocrDirectory) {
        this.ocrDirectory = ocrDirectory.toAbsolutePath().normalize();
    }

    @Override
    public synchronized OcrResponse recognize(Path image) {
        if (closed) throw ApiException.unavailable("OCR 服务已关闭");
        if (image == null || !Files.isRegularFile(image)) {
            throw ApiException.notFound("图片文件不存在");
        }

        Path output = null;
        Process process = null;
        try {
            if (Files.size(image) > MAX_IMAGE_BYTES) {
                return OcrResponse.failure("图片文件过大");
            }

            Path dataPath = TesseractModelStore.prepare(ocrDirectory);
            output = Files.createTempFile(ocrDirectory, "ocr-result-", ".txt");
            process = new ProcessBuilder(
                executable().toString(),
                image.toAbsolutePath().normalize().toString(),
                "stdout",
                "--tessdata-dir", dataPath.toString(),
                "-l", LANGUAGES,
                "--oem", "1",
                "--psm", "3")
                .redirectOutput(output.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminateAndWait(process);
                return OcrResponse.failure("识别超时，请重试");
            }
            if (process.exitValue() != 0 || Files.size(output) > MAX_OUTPUT_BYTES) {
                return OcrResponse.failure("识别失败，请重试");
            }

            String text = Files.readString(output, StandardCharsets.UTF_8).trim();
            return text.isEmpty()
                ? OcrResponse.failure("未识别到文字")
                : new OcrResponse(text, "");
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            terminateAndWait(process);
            Thread.currentThread().interrupt();
            return OcrResponse.failure("识别失败，请重试");
        } catch (IOException | RuntimeException exception) {
            terminateAndWait(process);
            return OcrResponse.failure("识别失败，请重试");
        } finally {
            deleteIfExists(output);
        }
    }

    private Path executable() {
        if (executable != null) return executable;
        try {
            Loader.load(TessBaseAPI.class);
            String platform = Loader.getPlatform();
            String suffix = platform.startsWith("windows") ? ".exe" : "";
            URL resource = TessBaseAPI.class.getResource(platform + "/tesseract" + suffix);
            if (resource == null) throw new IOException("缺少 Tesseract 原生程序");

            File cached = Loader.cacheResource(resource);
            if (cached == null) throw new IOException("无法释放 Tesseract 原生程序");
            if (!platform.startsWith("windows") && !cached.canExecute() && !cached.setExecutable(true)) {
                throw new IOException("Tesseract 原生程序不可执行");
            }
            executable = cached.toPath().toAbsolutePath().normalize();
            return executable;
        } catch (IOException | RuntimeException | LinkageError exception) {
            throw ApiException.unavailable("OCR 运行环境不可用");
        }
    }

    private static void terminateAndWait(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroyForcibly();
        boolean interrupted = false;
        while (process.isAlive()) {
            try {
                process.waitFor();
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void deleteIfExists(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 清理失败不覆盖本次识别结果。
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
    }
}
