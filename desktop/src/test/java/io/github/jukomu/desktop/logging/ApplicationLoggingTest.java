package io.github.jukomu.desktop.logging;

import io.github.jukomu.desktop.data.Paths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationLoggingTest {
    private static final int MAX_READ_BYTES = 256 * 1024;

    @Test
    void keepsIncompleteTailLineAtItsStartOffset() throws Exception {
        Paths paths = testPaths();
        ApplicationLogging.initialize(paths);
        Path file = ApplicationLogging.currentLogFile(paths);
        Files.writeString(file, "complete\npending", StandardCharsets.UTF_8);

        ApplicationLogging.LogSnapshot initial = ApplicationLogging.readCurrent(paths, 0L, 0L);

        assertEquals("", initial.content());
        assertEquals(9L, initial.nextOffset());

        Files.writeString(file, " done\n", StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND);
        ApplicationLogging.LogSnapshot completed = ApplicationLogging.readCurrent(
            paths, initial.nextLine(), initial.nextOffset());

        assertEquals("pending done\n", completed.content());
    }

    @Test
    void advancesThroughOversizedLineWithoutSplittingUtf8() throws Exception {
        Paths paths = testPaths();
        ApplicationLogging.initialize(paths);
        Path file = ApplicationLogging.currentLogFile(paths);
        String longLine = "界".repeat(MAX_READ_BYTES);
        Files.writeString(file, longLine, StandardCharsets.UTF_8);

        ApplicationLogging.LogSnapshot first = ApplicationLogging.readCurrent(paths, 0L, 0L);

        assertFalse(first.content().isEmpty());
        assertTrue(first.nextOffset() > 0L);
        assertFalse(first.content().contains("\uFFFD"));
        assertEquals(first.nextOffset(), first.content().getBytes(StandardCharsets.UTF_8).length);

        Files.writeString(file, "\nnext\n", StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND);
        StringBuilder combined = new StringBuilder(first.content());
        long nextLine = first.nextLine();
        long nextOffset = first.nextOffset();
        while (nextOffset < Files.size(file)) {
            ApplicationLogging.LogSnapshot next = ApplicationLogging.readCurrent(
                paths, nextLine, nextOffset);
            assertTrue(next.nextOffset() > nextOffset);
            combined.append(next.content());
            nextLine = next.nextLine();
            nextOffset = next.nextOffset();
        }

        assertEquals(longLine + "\nnext\n", combined.toString());
    }

    private static Paths testPaths() throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-logging-");
        return new Paths(
            root.resolve("program"),
            root.resolve("home"),
            Map.of(
                "XDG_DATA_HOME", root.resolve("data").toString(),
                "XDG_CACHE_HOME", root.resolve("cache").toString(),
                "XDG_STATE_HOME", root.resolve("state").toString()
            ),
            "Linux"
        );
    }
}
