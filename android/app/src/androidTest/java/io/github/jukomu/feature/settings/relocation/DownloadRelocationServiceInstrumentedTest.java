package io.github.jukomu.feature.settings.relocation;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public class DownloadRelocationServiceInstrumentedTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void returnsMinusOneWhenTargetDirectoryIsActive() throws Exception {
        File publicDir = temporaryFolder.newFolder("public");
        RelocationState state = new RelocationState(publicDir, null);
        TestFileOperations operations = new TestFileOperations();
        DownloadRelocationService service = createService(
            state, publicDir, temporaryFolder.newFolder("private"), operations);

        int moved = service.relocate(true, new RecordingSink());

        assertEquals(-1, moved);
        assertSame(publicDir, state.baseDir.get());
        assertTrue(state.checkpointWrites.isEmpty());
        assertTrue(operations.scannedBatches.isEmpty());
    }

    @Test
    public void switchesDirectoryWhenSourceHasNoFiles() throws Exception {
        File sourceDir = temporaryFolder.newFolder("source");
        File publicDir = new File(temporaryFolder.getRoot(), "public");
        RelocationState state = new RelocationState(sourceDir, null);
        DownloadRelocationService service = createService(
            state, publicDir, temporaryFolder.newFolder("private"),
            new TestFileOperations());

        int moved = service.relocate(true, new RecordingSink());

        assertEquals(0, moved);
        assertSame(publicDir, state.baseDir.get());
        assertTrue(publicDir.isDirectory());
        assertTrue(state.checkpointWrites.isEmpty());
    }

    @Test
    public void rejectsRelocationWhenTargetSpaceIsInsufficient() throws Exception {
        File sourceDir = temporaryFolder.newFolder("source");
        writeFile(sourceDir, "album/chapter/page.jpg", new byte[]{1});
        File publicDir = new File(temporaryFolder.getRoot(), "public");
        RelocationState state = new RelocationState(sourceDir, null);
        TestFileOperations operations = new TestFileOperations();
        operations.availableBytes = 0;
        DownloadRelocationService service = createService(
            state, publicDir, temporaryFolder.newFolder("private"), operations);

        IOException error = assertThrows(IOException.class,
            () -> service.relocate(true, new RecordingSink()));

        assertEquals("目标存储空间不足，需要 0 MB", error.getMessage());
        assertSame(sourceDir, state.baseDir.get());
        assertTrue(new File(sourceDir, "album/chapter/page.jpg").isFile());
        assertTrue(state.checkpointWrites.isEmpty());
    }

    @Test
    public void propagatesCopyFailureWithoutSwitchingDirectory() throws Exception {
        File sourceDir = temporaryFolder.newFolder("source");
        writeFile(sourceDir, "album/chapter/page.jpg", new byte[]{1});
        File publicDir = new File(temporaryFolder.getRoot(), "public");
        RelocationState state = new RelocationState(sourceDir, null);
        TestFileOperations operations = new TestFileOperations();
        operations.copyFailure = new IOException("copy failed");
        DownloadRelocationService service = createService(
            state, publicDir, temporaryFolder.newFolder("private"), operations);

        IOException error = assertThrows(IOException.class,
            () -> service.relocate(true, new RecordingSink()));

        assertEquals("copy failed", error.getMessage());
        assertSame(sourceDir, state.baseDir.get());
        assertTrue(new File(sourceDir, "album/chapter/page.jpg").isFile());
        assertTrue(state.checkpointWrites.isEmpty());
    }

    @Test
    public void rejectsMismatchedCopyBeforeDeletingSource() throws Exception {
        File sourceDir = temporaryFolder.newFolder("source");
        writeFile(sourceDir, "album/chapter/page.jpg", new byte[]{1});
        File publicDir = new File(temporaryFolder.getRoot(), "public");
        RelocationState state = new RelocationState(sourceDir, null);
        TestFileOperations operations = new TestFileOperations();
        operations.matchingCopy = false;
        DownloadRelocationService service = createService(
            state, publicDir, temporaryFolder.newFolder("private"), operations);

        IOException error = assertThrows(IOException.class,
            () -> service.relocate(true, new RecordingSink()));

        assertEquals("文件校验失败: album"
                + File.separator + "chapter" + File.separator + "page.jpg",
            error.getMessage());
        assertSame(sourceDir, state.baseDir.get());
        assertTrue(new File(sourceDir, "album/chapter/page.jpg").isFile());
        assertTrue(new File(publicDir, "album/chapter/page.jpg").isFile());
        assertTrue(state.checkpointWrites.isEmpty());
    }

    @Test
    public void resumesFromMatchingCheckpointCopy() throws Exception {
        File sourceDir = temporaryFolder.newFolder("source");
        byte[] content = {1, 2, 3};
        writeFile(sourceDir, "album/chapter/page.jpg", content);
        File privateDir = temporaryFolder.newFolder("private");
        writeFile(privateDir, "album/chapter/page.jpg", content);
        RelocationState state = new RelocationState(
            sourceDir, "{\"dest\":\"private\",\"current\":0,\"total\":1}");
        RecordingSink sink = new RecordingSink();
        DownloadRelocationService service = createService(
            state, temporaryFolder.newFolder("public"), privateDir,
            new TestFileOperations());

        int moved = service.relocate(false, sink);

        assertEquals(1, moved);
        assertSame(privateDir, state.baseDir.get());
        assertFalse(sourceDir.exists());
        assertEquals("", state.checkpoint.get());
        assertEquals(1, state.checkpointWrites.size());
        assertEquals("", state.checkpointWrites.get(0));
        assertTrue(sink.phases.isEmpty());
    }

    @Test
    public void relocatesInBatchesAndScansPublicFiles() throws Exception {
        File sourceDir = temporaryFolder.newFolder("source");
        for (int index = 0; index < 21; index++) {
            writeFile(sourceDir, "album/chapter/page-" + index + ".jpg",
                new byte[]{(byte) index});
        }
        File publicDir = new File(temporaryFolder.getRoot(), "public");
        RelocationState state = new RelocationState(sourceDir, null);
        TestFileOperations operations = new TestFileOperations();
        RecordingSink sink = new RecordingSink();
        DownloadRelocationService service = createService(
            state, publicDir, temporaryFolder.newFolder("private"), operations);

        int moved = service.relocate(true, sink);

        assertEquals(21, moved);
        assertSame(publicDir, state.baseDir.get());
        assertFalse(sourceDir.exists());
        assertTrue(new File(publicDir, "album/chapter/page-0.jpg").isFile());
        assertTrue(new File(publicDir, "album/chapter/page-20.jpg").isFile());

        assertEquals(3, state.checkpointWrites.size());
        JSONObject firstCheckpoint = new JSONObject(state.checkpointWrites.get(0));
        JSONObject secondCheckpoint = new JSONObject(state.checkpointWrites.get(1));
        assertEquals("public", firstCheckpoint.getString("dest"));
        assertEquals(20, firstCheckpoint.getInt("current"));
        assertEquals(21, firstCheckpoint.getInt("total"));
        assertEquals(21, secondCheckpoint.getInt("current"));
        assertEquals("", state.checkpointWrites.get(2));

        assertEquals(65, sink.phases.size());
        assertEquals("copying", sink.phases.get(0));
        assertEquals("copying", sink.phases.get(19));
        assertEquals("verifying", sink.phases.get(20));
        assertEquals("deleting", sink.phases.get(40));
        assertEquals("copying", sink.phases.get(60));
        assertEquals("verifying", sink.phases.get(61));
        assertEquals("deleting", sink.phases.get(62));
        assertEquals("scanning", sink.phases.get(63));
        assertEquals("scanning", sink.phases.get(64));

        assertEquals(1, operations.scannedBatches.size());
        assertEquals(21, operations.scannedBatches.get(0).length);
    }

    private DownloadRelocationService createService(
        RelocationState state, File publicDir, File privateDir,
        TestFileOperations operations) {
        return new DownloadRelocationService(
            null,
            state.baseDir::get,
            state.baseDir::set,
            state.checkpoint::get,
            checkpoint -> {
                state.checkpoint.set(checkpoint);
                state.checkpointWrites.add(checkpoint);
            },
            publicDir,
            privateDir,
            operations);
    }

    private static void writeFile(File root, String relativePath, byte[] content)
        throws IOException {
        File file = new File(root, relativePath);
        assertTrue(file.getParentFile().mkdirs() || file.getParentFile().isDirectory());
        Files.write(file.toPath(), content);
    }

    private static final class RelocationState {
        private final AtomicReference<File> baseDir;
        private final AtomicReference<String> checkpoint;
        private final List<String> checkpointWrites = new ArrayList<>();

        private RelocationState(File baseDir, String checkpoint) {
            this.baseDir = new AtomicReference<>(baseDir);
            this.checkpoint = new AtomicReference<>(checkpoint);
        }
    }

    private static final class RecordingSink implements RelocationEventSink {
        private final List<String> phases = new ArrayList<>();

        @Override
        public void onRelocationProgress(int current, int total, String phase,
                                         String currentFile) {
            phases.add(phase);
        }
    }

    private static final class TestFileOperations
        implements DownloadRelocationService.FileOperations {
        private long availableBytes = Long.MAX_VALUE;
        private IOException copyFailure;
        private boolean matchingCopy = true;
        private final List<String[]> scannedBatches = new ArrayList<>();

        @Override
        public long availableBytes(File directory) {
            return availableBytes;
        }

        @Override
        public void copy(File source, File target) throws IOException {
            if (copyFailure != null) {
                throw copyFailure;
            }
            Files.copy(source.toPath(), target.toPath(),
                StandardCopyOption.REPLACE_EXISTING);
        }

        @Override
        public boolean isMatchingCopy(File source, File target) {
            return matchingCopy && target.exists() && target.length() == source.length();
        }

        @Override
        public boolean delete(File file) {
            return file.delete();
        }

        @Override
        public void scan(Context context, String[] paths) {
            scannedBatches.add(paths);
        }
    }
}
