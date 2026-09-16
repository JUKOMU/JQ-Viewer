package io.github.jukomu.desktop.feature.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.pdf.export.PdfExportStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopTaskNotificationServiceTest {
    private Database database;
    private EventHub events;
    private DownloadStore downloads;
    private PdfExportStore pdfExports;
    private LaunchRouteService launchRoutes;
    private DesktopTaskNotificationService notifications;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-notifications-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        database = new Database(paths);
        database.open();
        events = new EventHub(new ObjectMapper());
        downloads = new DownloadStore(database);
        pdfExports = new PdfExportStore(database);
        launchRoutes = new LaunchRouteService(events);
        notifications = new DesktopTaskNotificationService(downloads, pdfExports, launchRoutes);
        notifications.start();
    }

    @AfterEach
    void tearDown() {
        notifications.close();
        launchRoutes.close();
        events.close();
        database.close();
    }

    @Test
    void mapsPersistedTerminalSnapshotsAndDeduplicatesRepeatedPublication() {
        RecordingSink sink = new RecordingSink();
        notifications.attach(sink);

        createDownload("album_chapter");
        downloads.complete("album_chapter", 8, 1, 1024, 10);
        notifications.downloadChanged("album_chapter");
        notifications.downloadChanged("album_chapter");

        assertEquals(1, sink.entries.size());
        assertEquals("下载完成", sink.entries.getFirst().notification().title());
        assertEquals("漫画 - 第一话", sink.entries.getFirst().notification().message());
        sink.entries.getFirst().click().run();
        sink.entries.getFirst().click().run();
        assertEquals(Map.of("route", "/download"), launchRoutes.consume());
        assertTrue(launchRoutes.consume().isEmpty());

        reservePdf("pdf id/1", "测试导出");
        pdfExports.updateProgress("pdf id/1", "completed", "completed", 8, 8,
                1, 1, null, null);
        notifications.pdfExportChanged("pdf id/1");
        notifications.pdfExportChanged("pdf id/1");

        assertEquals(2, sink.entries.size());
        assertEquals("PDF 导出完成", sink.entries.get(1).notification().title());
        assertEquals("测试导出", sink.entries.get(1).notification().message());
        sink.entries.get(1).click().run();
        assertEquals(Map.of("route", "/download?view=pdf&tab=tasks&exportId=pdf+id%2F1"),
                launchRoutes.consume());
    }

    @Test
    void queuesBeforeHostAttachAndRejectsMissingOrClosedTargetsOnClick() {
        createDownload("queued_target");
        downloads.fail("queued_target", 2, 128, 128, "网络错误");
        notifications.downloadChanged("queued_target");

        RecordingSink sink = new RecordingSink();
        notifications.attach(sink);
        assertEquals(1, sink.entries.size());
        assertEquals("下载失败", sink.entries.getFirst().notification().title());

        downloads.deleteTask("queued_target");
        sink.entries.getFirst().click().run();
        assertTrue(launchRoutes.consume().isEmpty());

        createDownload("closed_target");
        downloads.complete("closed_target", 1, 1, 10, 20);
        notifications.downloadChanged("closed_target");
        Runnable click = sink.entries.get(1).click();
        notifications.close();
        click.run();
        assertTrue(launchRoutes.consume().isEmpty());
    }

    @Test
    void retainsOnePendingRouteUntilFrontendConsumesIt() {
        List<String> opened = new ArrayList<>();
        launchRoutes.activate("/download");
        launchRoutes.attachRouteOpener(opened::add);

        assertEquals(Map.of("route", "/download"), launchRoutes.consume());
        assertTrue(launchRoutes.consume().isEmpty());
        launchRoutes.activate("//invalid.example");
        assertTrue(launchRoutes.consume().isEmpty());
        assertTrue(opened.isEmpty());
    }

    private void createDownload(String taskId) {
        downloads.createOrResetTask(
                taskId,
                "album",
                taskId,
                "漫画",
                "第一话",
                "",
                taskId,
                1
        );
    }

    private void reservePdf(String exportId, String title) {
        pdfExports.reserve(new PdfExportStore.ReserveTask(
                exportId,
                "batch",
                "chapter",
                "album",
                "漫画",
                "",
                "作者",
                false,
                "chapter",
                title,
                "folder:path:/tmp",
                "output.pdf",
                "/tmp/output.pdf",
                false,
                true,
                1,
                0,
                "queued",
                "queued",
                8,
                null,
                null,
                1
        ), List.of(), List.of());
    }

    private static final class RecordingSink implements DesktopNotificationSink {
        private final List<Entry> entries = new ArrayList<>();

        @Override
        public void show(DesktopNotification notification, Runnable onClick) {
            entries.add(new Entry(notification, onClick));
        }
    }

    private record Entry(DesktopNotification notification, Runnable click) {
    }
}
