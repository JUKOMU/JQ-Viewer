package io.github.jukomu.desktop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.pdf.data.PdfStore;
import io.github.jukomu.desktop.feature.pdf.export.PdfExportStore;
import io.github.jukomu.desktop.feature.settings.SettingsService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StoreConnectionIsolationTest {
    @Test
    void storesDoNotShareThePrimaryConnectionTransaction() throws Exception {
        Path databasePath = Files.createTempDirectory("jq-viewer-store-isolation-")
                .resolve("desktop.sqlite3");
        try (Database database = new Database(databasePath)) {
            database.open();
            enableWal(database.connection());

            SettingsService settings = new SettingsService(database, new ObjectMapper());
            DownloadStore downloads = new DownloadStore(database);
            PdfStore pdfs = new PdfStore(database);
            PdfExportStore exports = new PdfExportStore(database);

            assertIsolatedWrite(
                    database.connection(),
                    "SELECT COUNT(*) FROM settings WHERE key='reader_preload_pages'",
                    () -> settings.setReaderPreloadPages(21)
            );
            assertIsolatedWrite(
                    database.connection(),
                    "SELECT COUNT(*) FROM download_tasks WHERE task_id='download-1'",
                    () -> downloads.createOrResetTask(
                            "download-1", "album-1", "chapter-1",
                            "Album", "Chapter", "", "album-1/chapter-1", 1L)
            );
            assertIsolatedWrite(
                    database.connection(),
                    "SELECT COUNT(*) FROM pdf_files WHERE file_ref='file-ref-1'",
                    () -> pdfs.insertImported(
                            "file-ref-1", "/sample.pdf", "sample.pdf",
                            "album-1", "Album", "", "Author",
                            "chapter-1", "Chapter", 1, false,
                            null, 10L, 1, 2L)
            );
            assertIsolatedWrite(
                    database.connection(),
                    "SELECT COUNT(*) FROM pdf_export_tasks WHERE export_id='export-1'",
                    () -> exports.reserve(
                            new PdfExportStore.ReserveTask(
                                    "export-1", "batch-1", "chapter", "album-1",
                                    "Album", "", "Author", false, "chapter-1",
                                    "Chapter", "folder-ref", "sample.pdf", "/sample.pdf",
                                    false, true, 1D, 0, "queued", "queued",
                                    1, null, null, 3L
                            ),
                            List.of(new PdfExportStore.Chapter(
                                    0, "album-1", "chapter-1", "Chapter", 1, 1)),
                            List.of(new PdfExportStore.Volume(
                                    0, 1, 1, 1, "sample.pdf", "/sample.pdf", "/sample.tmp"))
                    )
            );
        }
    }

    private static void enableWal(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA journal_mode=WAL")) {
            result.next();
            assertEquals("wal", result.getString(1));
        }
    }

    private static void assertIsolatedWrite(
            Connection primary,
            String countSql,
            Runnable write
    ) throws SQLException {
        boolean autoCommit = primary.getAutoCommit();
        primary.setAutoCommit(false);
        try {
            assertEquals(0, count(primary, countSql));
            write.run();
            assertEquals(0, count(primary, countSql));
            primary.rollback();
        } finally {
            if (!primary.getAutoCommit()) {
                primary.rollback();
                primary.setAutoCommit(autoCommit);
            }
        }
        assertEquals(1, count(primary, countSql));
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
