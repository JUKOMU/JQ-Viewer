package io.github.jukomu.desktop.feature.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jukomu.desktop.bridge.EventHub;
import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.download.data.StoredDownloadTask;
import io.github.jukomu.desktop.feature.download.model.DownloadChapterRequest;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.client.JmDownloadClient;
import io.github.jukomu.jmcomic.api.download.DownloadProgress;
import io.github.jukomu.jmcomic.api.download.IDownloadManager;
import io.github.jukomu.jmcomic.api.download.enums.TaskState;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadServiceTest {
    @Test
    void completesListsReadsAndDeletesDownloadedChapter() throws Exception {
        try (Fixture fixture = fixture(Mode.COMPLETE)) {
            fixture.service.downloadChapter(request());

            StoredDownloadTask task = fixture.store.findTask("album-1_photo-1");
            assertNotNull(task);
            assertEquals(DownloadService.STATUS_COMPLETED, task.status());
            assertEquals(1, task.downloadedPages());
            assertTrue(task.totalSize() > 0);
            assertEquals(1, fixture.service.getDownloadTasks().tasks().size());
            assertTrue(fixture.service.findCompletedImage("photo-1", 1).isPresent());
            assertEquals("photo-1",
                    fixture.service.getDownloadedPhoto("album-1", "photo-1").id());

            fixture.service.deleteDownloaded("album-1", "photo-1");

            assertNull(fixture.store.findTask("album-1_photo-1"));
            assertFalse(Files.exists(fixture.files.chapterDirectory("album-1/photo-1")));
        }
    }

    @Test
    void pausesResumesAndCancelsCurrentProcessTask() throws Exception {
        try (Fixture fixture = fixture(Mode.RUNNING)) {
            fixture.service.downloadChapter(request());
            assertEquals(DownloadService.STATUS_DOWNLOADING,
                    fixture.store.findTask("album-1_photo-1").status());

            fixture.service.pauseDownload("album-1_photo-1");
            assertEquals(DownloadService.STATUS_PAUSED,
                    fixture.store.findTask("album-1_photo-1").status());

            fixture.service.resumeDownload("album-1_photo-1");
            assertEquals(DownloadService.STATUS_DOWNLOADING,
                    fixture.store.findTask("album-1_photo-1").status());

            fixture.service.cancelDownload("album-1_photo-1");
            assertNull(fixture.store.findTask("album-1_photo-1"));
            assertFalse(Files.exists(fixture.files.chapterDirectory("album-1/photo-1")));
        }
    }

    @Test
    void persistsLibraryFailureWithoutPublishingCompletedFiles() throws Exception {
        try (Fixture fixture = fixture(Mode.FAIL)) {
            fixture.service.downloadChapter(request());

            StoredDownloadTask task = fixture.store.findTask("album-1_photo-1");
            assertNotNull(task);
            assertEquals(DownloadService.STATUS_FAILED, task.status());
            assertEquals("下载失败", task.error());
            assertTrue(fixture.service.findCompletedImage("photo-1", 1).isEmpty());
            assertThrows(RuntimeException.class,
                    () -> fixture.service.getDownloadedPhoto("album-1", "photo-1"));
        }
    }

    @Test
    void leavesActiveStateOnCloseAndReconcilesItOnNextStartup() throws Exception {
        try (Fixture fixture = fixture(Mode.RUNNING)) {
            fixture.service.downloadChapter(request());
            Path directory = fixture.files.chapterDirectory("album-1/photo-1");
            assertTrue(Files.exists(directory));

            fixture.service.close();
            fixture.downloadClient.close();
            assertEquals(DownloadService.STATUS_DOWNLOADING,
                    fixture.store.findTask("album-1_photo-1").status());

            DownloadService restarted = fixture.newService(Mode.RUNNING);
            restarted.reconcileOnStartup();

            StoredDownloadTask recovered = fixture.store.findTask("album-1_photo-1");
            assertEquals(DownloadService.STATUS_FAILED, recovered.status());
            assertTrue(recovered.error().contains("应用重启"));
            assertTrue(fixture.store.pages("album-1_photo-1").isEmpty());
            assertFalse(Files.exists(directory));
            restarted.close();
        }
    }

    @Test
    void rejectsPathSegmentsThatEscapeDownloadRoot() throws Exception {
        try (Fixture fixture = fixture(Mode.RUNNING)) {
            assertThrows(RuntimeException.class, () -> fixture.service.downloadChapter(
                    new DownloadChapterRequest("..", "photo-1", "Album", "Photo", "")));
            assertTrue(fixture.store.listTasks().isEmpty());
        }
    }

    @Test
    void rejectsNewOnlineDownloadWithoutCreatingLocalTaskWhenClientIsUnavailable() throws Exception {
        try (Fixture fixture = fixture(Mode.RUNNING)) {
            DownloadService unavailable = new DownloadService(
                    fixture.store,
                    fixture.files,
                    () -> null,
                    () -> null,
                    Runnable::run,
                    fixture.events,
                    fixture.mapper);

            ApiException failure = assertThrows(
                    ApiException.class, () -> unavailable.downloadChapter(request()));

            assertEquals("unavailable", failure.code());
            assertTrue(fixture.store.listTasks().isEmpty());
            unavailable.close();
        }
    }

    private static DownloadChapterRequest request() {
        return new DownloadChapterRequest(
                "album-1", "photo-1", "Album", "Photo", "https://cover.invalid/album-1.jpg");
    }

    private static Fixture fixture(Mode mode) throws Exception {
        Path root = Files.createTempDirectory("jq-viewer-download-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        Database database = new Database(paths);
        database.open();
        return new Fixture(paths, database, mode);
    }

    private enum Mode {
        COMPLETE,
        RUNNING,
        FAIL
    }

    private static final class Fixture implements AutoCloseable {
        private final Paths paths;
        private final Database database;
        private final DownloadStore store;
        private final DownloadFiles files;
        private final ObjectMapper mapper = new ObjectMapper();
        private final EventHub events = new EventHub(mapper);
        private final TestClient client;
        private final JmDownloadClient downloadClient;
        private final DownloadService service;

        private Fixture(Paths paths, Database database, Mode mode) throws Exception {
            this.paths = paths;
            this.database = database;
            this.store = new DownloadStore(database);
            this.files = new DownloadFiles(paths);
            this.client = new TestClient(mode, pngBytes());
            this.downloadClient = client.downloadClient();
            this.service = new DownloadService(
                    store, files, client.client(), downloadClient, Runnable::run, events, mapper);
        }

        private DownloadService newService(Mode mode) throws Exception {
            TestClient restarted = new TestClient(mode, pngBytes());
            return new DownloadService(
                    store, files, restarted.client(), restarted.downloadClient(),
                    Runnable::run, events, mapper);
        }

        @Override
        public void close() {
            service.close();
            downloadClient.close();
            events.close();
            database.close();
        }
    }

    private static final class TestClient {
        private final Mode mode;
        private final byte[] bytes;
        private final TestDownloadManager manager = new TestDownloadManager();
        private final Object proxy;

        private TestClient(Mode mode, byte[] bytes) {
            this.mode = mode;
            this.bytes = bytes;
            this.proxy = Proxy.newProxyInstance(
                    JmClient.class.getClassLoader(),
                    new Class<?>[]{JmClient.class, JmDownloadClient.class},
                    this::invoke);
        }

        private JmClient client() {
            return (JmClient) proxy;
        }

        private JmDownloadClient downloadClient() {
            return (JmDownloadClient) proxy;
        }

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "TestDownloadClient";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError(method.getName());
                };
            }
            return switch (method.getName()) {
                case "getPhoto" -> photo((String) arguments[0]);
                case "createDownloadTask" -> new TestDownloadTask(
                        photo(((JmPhoto) arguments[0]).getId()),
                        (Path) arguments[1], bytes, mode, manager);
                case "downloadManager" -> manager;
                case "close" -> {
                    manager.close();
                    yield null;
                }
                default -> throw new AssertionError("Unexpected client call: " + method.getName());
            };
        }

        private static JmPhoto photo(String id) {
            return new JmPhoto(
                    id,
                    "Photo",
                    "album-1",
                    "1",
                    2,
                    "Alice",
                    List.of("tag"),
                    List.of(new JmImage(
                            id, "1", "001.png", "https://example.invalid/001.png", "", 1)),
                    false
            );
        }
    }

    private static final class TestDownloadManager implements IDownloadManager {
        private final Map<String, BaseDownloadTask> tasks = new HashMap<>();

        @Override
        public BaseDownloadTask getTask(String taskId) {
            return tasks.get(taskId);
        }

        @Override
        public List<BaseDownloadTask> getActiveTasks() {
            return tasks.values().stream().filter(task -> !task.currentState().isTerminal()).toList();
        }

        @Override
        public List<BaseDownloadTask> getTaskRegistry() {
            return List.copyOf(tasks.values());
        }

        @Override
        public void submit(BaseDownloadTask task) {
            if (task.transitState(TaskState.PENDING, TaskState.QUEUED)
                    || task.transitState(TaskState.PAUSED, TaskState.QUEUED)) {
                tasks.put(task.getTaskId(), task);
                task.notifyStateChanged(TaskState.QUEUED);
                task.run();
            }
        }

        @Override
        public void pause(String taskId) {
            BaseDownloadTask task = tasks.get(taskId);
            if (task != null) task.pause();
        }

        @Override
        public void resume(String taskId) {
            BaseDownloadTask task = tasks.get(taskId);
            if (task != null) task.resume();
        }

        @Override
        public void cancel(String taskId) {
            BaseDownloadTask task = tasks.get(taskId);
            if (task != null) task.cancel();
        }

        @Override
        public void close() {
            for (BaseDownloadTask task : List.copyOf(tasks.values())) task.cancel();
        }
    }

    private static final class TestDownloadTask extends BaseDownloadTask {
        private final JmPhoto photo;
        private final Path directory;
        private final byte[] bytes;
        private final Mode mode;
        private final IDownloadManager manager;

        private TestDownloadTask(
                JmPhoto photo,
                Path path,
                byte[] bytes,
                Mode mode,
                IDownloadManager manager
        ) {
            this.photo = photo;
            this.directory = photo.isSingleAlbum() ? path : path.resolve(photo.getId());
            this.bytes = bytes;
            this.mode = mode;
            this.manager = manager;
            this.totalBytes = bytes.length;
        }

        @Override
        public void start() {
            if (!transitState(TaskState.QUEUED, TaskState.RUNNING)) return;
            notifyStateChanged(TaskState.RUNNING);
            try {
                Files.createDirectories(directory);
                if (mode == Mode.RUNNING) {
                    Files.write(directory.resolve("001.png.tmp"), new byte[]{1, 2, 3});
                    return;
                }
                if (mode == Mode.FAIL) {
                    if (transitState(TaskState.RUNNING, TaskState.FAILED)) {
                        notifyStateChanged(TaskState.FAILED);
                    }
                    return;
                }
                Path target = directory.resolve("001.png");
                Files.write(target, bytes);
                addSuccessfulFile(target);
                completedCount = 1;
                downloadedBytes = bytes.length;
                notifyProgressUpdate(new DownloadProgress(
                        photo.getAlbumId(), null, photo.getId(), photo.getTitle(),
                        1, 0, 1, 0, 0, 0, false, downloadedBytes,
                        String.valueOf(System.currentTimeMillis())));
                if (transitState(TaskState.RUNNING, TaskState.COMPLETED)) {
                    notifyStateChanged(TaskState.COMPLETED);
                    notifyFinish(getCurrentDownloadResult());
                }
            } catch (Exception exception) {
                notifyError(exception);
                if (transitState(TaskState.RUNNING, TaskState.FAILED)) {
                    notifyStateChanged(TaskState.FAILED);
                }
            }
        }

        @Override
        public void pause() {
            if (transitState(TaskState.RUNNING, TaskState.PAUSED)
                    || transitState(TaskState.QUEUED, TaskState.PAUSED)) {
                notifyStateChanged(TaskState.PAUSED);
            }
        }

        @Override
        public void resume() {
            manager.submit(this);
        }

        @Override
        public void cancel() {
            boolean cancelling = transitState(TaskState.RUNNING, TaskState.CANCELLING);
            boolean cancelled = transitState(TaskState.PENDING, TaskState.CANCELLED)
                    || transitState(TaskState.QUEUED, TaskState.CANCELLED)
                    || transitState(TaskState.PAUSED, TaskState.CANCELLED);
            if (cancelling) cancelled = transitState(TaskState.CANCELLING, TaskState.CANCELLED);
            if (cancelled) notifyStateChanged(TaskState.CANCELLED);
        }
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) image.setRGB(x, y, Color.BLUE.getRGB());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", output));
        return output.toByteArray();
    }
}
