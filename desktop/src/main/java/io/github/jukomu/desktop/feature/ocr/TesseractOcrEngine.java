package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.feature.ocr.model.OcrResponse;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.tesseract.TessBaseAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger(TesseractOcrEngine.class);
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

        long startedNanos = System.nanoTime();
        Path output = null;
        Process process = null;
        try {
            long imageSize = Files.size(image);
            LOGGER.debug("ocr_engine event=started imageSizeBytes={} elapsedMs=0", imageSize);
            if (imageSize > MAX_IMAGE_BYTES) {
                LOGGER.warn("ocr_engine event=completed status=failed error=image_too_large elapsedMs={}",
                    elapsedMs(startedNanos));
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
            LOGGER.debug("ocr_engine event=process-started status=running");

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                terminateAndWait(process);
                LOGGER.warn("ocr_engine event=completed status=failed error=timeout elapsedMs={}",
                    elapsedMs(startedNanos));
                return OcrResponse.failure("识别超时，请重试");
            }
            long outputSize = Files.size(output);
            if (process.exitValue() != 0 || outputSize > MAX_OUTPUT_BYTES) {
                LOGGER.warn("ocr_engine event=completed status=failed error=process_output_invalid exitCode={} outputSizeBytes={} elapsedMs={}",
                    process.exitValue(), outputSize, elapsedMs(startedNanos));
                return OcrResponse.failure("识别失败，请重试");
            }

            String text = Files.readString(output, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                LOGGER.info("ocr_engine event=completed status=failed error=empty_result outputSizeBytes={} elapsedMs={}",
                    outputSize, elapsedMs(startedNanos));
                return OcrResponse.failure("未识别到文字");
            }
            LOGGER.info("ocr_engine event=completed status=success textLength={} outputSizeBytes={} elapsedMs={}",
                text.length(), outputSize, elapsedMs(startedNanos));
            return new OcrResponse(text, "");
        } catch (ApiException exception) {
            LOGGER.warn("ocr_engine event=completed status=failed error={} errorClass={} elapsedMs={}",
                exception.code(), exception.getClass().getSimpleName(), elapsedMs(startedNanos));
            throw exception;
        } catch (InterruptedException exception) {
            terminateAndWait(process);
            Thread.currentThread().interrupt();
            LOGGER.warn("ocr_engine event=completed status=failed error=interrupted errorClass={} elapsedMs={}",
                exception.getClass().getSimpleName(), elapsedMs(startedNanos));
            return OcrResponse.failure("识别失败，请重试");
        } catch (IOException | RuntimeException exception) {
            terminateAndWait(process);
            LOGGER.warn("ocr_engine event=completed status=failed error=runtime errorClass={} elapsedMs={}",
                exception.getClass().getSimpleName(), elapsedMs(startedNanos));
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
            LOGGER.info("ocr_engine event=runtime-loaded status=success platform={}", platform);
            return executable;
        } catch (IOException | RuntimeException | LinkageError exception) {
            LOGGER.error("ocr_engine event=runtime-loaded status=failed errorClass={}",
                exception.getClass().getSimpleName());
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
            LOGGER.warn("ocr_engine event=temporary-output-cleanup status=failed errorClass={}",
                ignored.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        LOGGER.info("ocr_engine operation=close status=success");
    }

    private static long elapsedMs(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
