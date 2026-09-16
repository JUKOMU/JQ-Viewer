package io.github.jukomu.desktop.feature.ocr;

import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.ocr.model.OcrResponse;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OcrServiceTest {
    @TempDir
    Path root;

    @Test
    void cancellationIsAStableEmptyResultAndSettingIsPersisted() throws Exception {
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        Database database = new Database(paths);
        database.open();
        try {
            SettingsService settings = new SettingsService(database);
            AtomicBoolean closed = new AtomicBoolean();
            OcrService service = new OcrService(
                    settings,
                    () -> null,
                    new OcrEngine() {
                        @Override
                        public OcrResponse recognize(Path image) {
                            throw new AssertionError("取消时不应启动识别");
                        }

                        @Override
                        public void close() {
                            closed.set(true);
                        }
                    });

            service.setEnabled(false);
            assertEquals(false, settings.all().ocrEnabled());
            assertEquals(OcrResponse.cancelled(), service.pickImageAndOcr());

            service.close();
            assertEquals(true, closed.get());
            assertThrows(RuntimeException.class, service::pickImageAndOcr);
        } finally {
            database.close();
        }
    }

    @Test
    void concurrentRequestsAreRejectedBeforeStartingTheSecondPicker() throws Exception {
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        Database database = new Database(paths);
        database.open();
        try {
            SettingsService settings = new SettingsService(database);
            Object lock = new Object();
            AtomicBoolean entered = new AtomicBoolean();
            ImagePicker picker = () -> {
                entered.set(true);
                synchronized (lock) {
                    try {
                        lock.wait(2_000);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                return null;
            };
            OcrService service = new OcrService(settings, picker, new NoopOcrEngine());
            Thread first = new Thread(service::pickImageAndOcr);
            first.start();
            while (!entered.get()) Thread.yield();

            assertThrows(RuntimeException.class, service::pickImageAndOcr);
            synchronized (lock) {
                lock.notifyAll();
            }
            first.join(2_000);
        } finally {
            database.close();
        }
    }

    private static final class NoopOcrEngine implements OcrEngine {
        @Override
        public OcrResponse recognize(Path image) {
            return OcrResponse.cancelled();
        }

        @Override
        public void close() {
        }
    }
}
