package io.github.jukomu.runtime;

import io.github.jukomu.feature.download.DownloadEventSink;
import io.github.jukomu.feature.download.model.DownloadProgressData;
import io.github.jukomu.feature.preload.PreloadEventSink;
import io.github.jukomu.feature.settings.relocation.RelocationEventSink;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class RuntimeEventRouterTest {

    @Test
    public void allFeatureSinksForwardSynchronouslyAndDetachTogether() {
        RuntimeEventRouter router = new RuntimeEventRouter();
        RecordingSinks sinks = new RecordingSinks();
        Thread callingThread = Thread.currentThread();

        router.attach(sinks, sinks, sinks);
        emitAll(router);
        router.detach(sinks, sinks, sinks);
        emitAll(router);

        assertEquals(1, sinks.downloadCount);
        assertEquals(1, sinks.preloadCount);
        assertEquals(1, sinks.relocationCount);
        assertSame(callingThread, sinks.lastThread);
    }

    @Test
    public void staleDetachCannotClearReboundSinks() {
        RuntimeEventRouter router = new RuntimeEventRouter();
        RecordingSinks first = new RecordingSinks();
        RecordingSinks second = new RecordingSinks();

        router.attach(first, first, first);
        router.attach(second, second, second);
        router.detach(first, first, first);
        emitAll(router);

        assertEquals(0, first.downloadCount);
        assertEquals(0, first.preloadCount);
        assertEquals(0, first.relocationCount);
        assertEquals(1, second.downloadCount);
        assertEquals(1, second.preloadCount);
        assertEquals(1, second.relocationCount);
    }

    @Test
    public void eachFeatureSinkDetachesOnlyItsExpectedDelegate() {
        RuntimeEventRouter router = new RuntimeEventRouter();
        RecordingSinks attached = new RecordingSinks();
        RecordingSinks stale = new RecordingSinks();

        router.attach(attached, attached, attached);
        router.detach(attached, stale, attached);
        emitAll(router);

        assertEquals(0, attached.downloadCount);
        assertEquals(1, attached.preloadCount);
        assertEquals(0, attached.relocationCount);
    }

    @Test
    public void eventsWithoutAttachedSinksAreDropped() {
        RuntimeEventRouter router = new RuntimeEventRouter();

        emitAll(router);
    }

    private static void emitAll(RuntimeEventRouter router) {
        router.onDownloadProgress(new DownloadProgressData(
            "task", "album", "chapter", 1, 2, "downloading", null, 3, 4, 5));
        router.onImageReady("photo", 6, "image");
        router.onRelocationProgress(7, 8, "copy", "file");
    }

    private static final class RecordingSinks implements DownloadEventSink,
        PreloadEventSink,
        RelocationEventSink {

        private int downloadCount;
        private int preloadCount;
        private int relocationCount;
        private Thread lastThread;

        @Override
        public void onDownloadProgress(DownloadProgressData data) {
            downloadCount++;
            lastThread = Thread.currentThread();
        }

        @Override
        public void onImageReady(String photoId, int sortOrder, String type) {
            preloadCount++;
            lastThread = Thread.currentThread();
        }

        @Override
        public void onRelocationProgress(int current, int total, String phase,
                                         String currentFile) {
            relocationCount++;
            lastThread = Thread.currentThread();
        }
    }
}
