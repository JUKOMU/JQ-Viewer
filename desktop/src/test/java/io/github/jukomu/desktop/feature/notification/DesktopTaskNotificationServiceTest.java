package io.github.jukomu.desktop.feature.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.model.DownloadProgressEvent;
import io.github.jukomu.desktop.feature.export.ExportStore;
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
    private ExportStore exports;
    private LaunchRouteService launchRoutes;
    private DesktopTaskNotificationService notifications;
    private List<String> openedFolders;

    @BeforeEach
    void setUp() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-notifications-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        database = new Database(paths);
        database.open();
        events = new EventHub(new ObjectMapper());
        downloads = new DownloadStore(database);
        exports = new ExportStore(database);
        launchRoutes = new LaunchRouteService(events);
        openedFolders = new ArrayList<>();
        notifications = new DesktopTaskNotificationService(
                downloads, exports, launchRoutes, events, openedFolders::add);
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
        publishDownload("album_chapter", "completed");
        publishDownload("album_chapter", "completed");

        assertEquals(1, sink.entries.size());
        assertEquals("下载完成", sink.entries.getFirst().notification().title());
        assertEquals("漫画 - 第一话", sink.entries.getFirst().notification().message());
        sink.entries.getFirst().click().run();
        sink.entries.getFirst().click().run();
        assertEquals(Map.of("route", "/download"), launchRoutes.consume());
        assertTrue(launchRoutes.consume().isEmpty());

        reserveExport("pdf id/1", "pdf", "测试导出", "file:path:/tmp/output.pdf");
        exports.updateProgress("pdf id/1", "completed", "completed", 8, 8,
                1, 1, null, null);
        events.publish("exportProgress", exports.find("pdf id/1"));
        events.publish("exportProgress", exports.find("pdf id/1"));

        assertEquals(2, sink.entries.size());
        assertEquals("PDF 导出完成", sink.entries.get(1).notification().title());
        assertEquals("测试导出", sink.entries.get(1).notification().message());
        sink.entries.get(1).click().run();
        String route = launchRoutes.consume().get("route");
        assertTrue(route.startsWith("/pdf-reader?"));
        assertTrue(route.contains("fileRef=file%3Apath%3A%2Ftmp%2Foutput.pdf"));
    }

    @Test
    void opensCbzInReaderAndZipInContainingFolder() {
        RecordingSink sink = new RecordingSink();
        notifications.attach(sink);

        reserveExport("cbz-1", "cbz", "CBZ", "file:path:/tmp/output.cbz");
        exports.updateProgress("cbz-1", "completed", "completed", 8, 8,
                1, 1, null, null);
        events.publish("exportProgress", exports.find("cbz-1"));
        sink.entries.getFirst().click().run();
        assertTrue(launchRoutes.consume().get("route").startsWith("/cbz-reader?"));

        reserveExport("zip-1", "zip", "ZIP", "file:path:/tmp/output.zip");
        exports.updateProgress("zip-1", "completed", "completed", 8, 8,
                1, 1, null, null);
        events.publish("exportProgress", exports.find("zip-1"));
        sink.entries.get(1).click().run();
        assertEquals(List.of("file:path:/tmp/output.zip"), openedFolders);
        assertTrue(launchRoutes.consume().isEmpty());
    }

    @Test
    void queuesBeforeHostAttachAndRejectsMissingOrClosedTargetsOnClick() {
        createDownload("queued_target");
        downloads.fail("queued_target", 2, 128, 128, "网络错误");
        publishDownload("queued_target", "failed");

        RecordingSink sink = new RecordingSink();
        notifications.attach(sink);
        assertEquals(1, sink.entries.size());
        assertEquals("下载失败", sink.entries.getFirst().notification().title());

        downloads.deleteTask("queued_target");
        sink.entries.getFirst().click().run();
        assertTrue(launchRoutes.consume().isEmpty());

        createDownload("closed_target");
        downloads.complete("closed_target", 1, 1, 10, 20);
        publishDownload("closed_target", "completed");
        Runnable click = sink.entries.get(1).click();
        notifications.close();
        click.run();
        assertTrue(launchRoutes.consume().isEmpty());

        createDownload("after_close");
        downloads.complete("after_close", 1, 1, 10, 20);
        publishDownload("after_close", "completed");
        assertEquals(2, sink.entries.size());
    }

    @Test
    void retainsPendingRoutesInActivationOrderUntilFrontendConsumesThem() {
        List<String> opened = new ArrayList<>();
        launchRoutes.activate("/download");
        launchRoutes.activate("/about");
        launchRoutes.attachRouteOpener(opened::add);

        assertEquals(Map.of("route", "/download"), launchRoutes.consume());
        assertEquals(Map.of("route", "/about"), launchRoutes.consume());
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

    private void publishDownload(String taskId, String status) {
        events.publish("downloadProgress", new DownloadProgressEvent(
                taskId, "album", taskId, 1, 1, status, null, 0, 10, 20L
        ));
    }

    private void reserveExport(String exportId, String format, String title, String outputFileRef) {
        String extension = format.equals("pdf") ? "pdf" : format;
        exports.reserve(new ExportStore.ReserveTask(
                exportId,
                "batch",
                format,
                "chapter",
                "album",
                "漫画",
                "",
                "作者",
                false,
                "chapter",
                title,
                "folder:path:/tmp",
                "output." + extension,
                "/tmp/output." + extension,
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
        ), List.of(new ExportStore.Chapter(
                0, "album", "chapter", title, 1, 8)), List.of(new ExportStore.Volume(
                1, 0, 8, 8, "output." + extension,
                "/tmp/output." + extension, "/tmp/output.tmp")));
        exports.completeVolumeAndRegisterFile(
                exportId, 1, outputFileRef, "/tmp/output." + extension,
                "output." + extension, 1024, 8);
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
