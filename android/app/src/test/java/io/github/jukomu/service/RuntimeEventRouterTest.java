package io.github.jukomu.service;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class RuntimeEventRouterTest {

    @Test
    public void listenerCanBeReboundAcrossPluginLifecycles() {
        RuntimeEventRouter router = new RuntimeEventRouter();
        AtomicInteger firstCount = new AtomicInteger();
        AtomicInteger secondCount = new AtomicInteger();
        ServiceListener first = listener(firstCount);
        ServiceListener second = listener(secondCount);

        router.attach(first);
        router.onImageReady("photo", 1, "image");
        router.detach(first);
        router.onImageReady("photo", 2, "image");
        router.attach(second);
        router.onImageReady("photo", 3, "image");

        assertEquals(1, firstCount.get());
        assertEquals(1, secondCount.get());
    }

    @Test
    public void staleDetachCannotClearReboundListener() {
        RuntimeEventRouter router = new RuntimeEventRouter();
        AtomicInteger secondCount = new AtomicInteger();
        ServiceListener first = listener(new AtomicInteger());
        ServiceListener second = listener(secondCount);

        router.attach(first);
        router.attach(second);
        router.detach(first);
        router.onImageReady("photo", 1, "image");

        assertEquals(1, secondCount.get());
    }

    private ServiceListener listener(AtomicInteger count) {
        return new ServiceListener() {
            @Override
            public void onDownloadProgress(DownloadProgressData data) {
            }

            @Override
            public void onImageReady(String photoId, int sortOrder, String type) {
                count.incrementAndGet();
            }

            @Override
            public void onRelocationProgress(int current, int total, String phase,
                                              String currentFile) {
            }
        };
    }
}
