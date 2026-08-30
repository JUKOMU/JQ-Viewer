package io.github.jukomu.picacomic;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake-only remote fixture for the Android Capacitor bridge contract. */
final class PicacomicPluginFakeClient implements PicacomicRemoteClient {

    volatile boolean blockImage;
    final AtomicInteger closeCalls = new AtomicInteger();
    final CountDownLatch imageStarted = new CountDownLatch(1);
    final CountDownLatch releaseImage = new CountDownLatch(1);

    @Override
    public PicacomicRemoteModels.User login(String usernameOrEmail, String password,
                                            PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        return new PicacomicRemoteModels.User("user-1", "fixture-user",
            "fixture@example.invalid", "https://example.invalid/avatar");
    }

    @Override
    public PicacomicRemoteModels.Page search(String query, String order, int page,
                                             PicacomicCancellationToken cancellation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PicacomicRemoteModels.Page categories(String category, String order, int page,
                                                 PicacomicCancellationToken cancellation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PicacomicRemoteModels.Album getAlbum(String albumId,
                                                PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        return album();
    }

    @Override
    public PicacomicRemoteModels.Photo getPhoto(String albumId, int order,
                                                PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        for (PicacomicRemoteModels.Photo photo : album().photos) {
            if (photo.order == order) return photo;
        }
        throw new PicacomicRemoteException(PicacomicRemoteException.Kind.NOT_FOUND, 404);
    }

    @Override
    public byte[] fetchImageBytes(PicacomicImageSource source,
                                  PicacomicCancellationToken cancellation)
        throws Exception {
        imageStarted.countDown();
        while (blockImage && !cancellation.isCancelled()) {
            if (releaseImage.await(10, TimeUnit.MILLISECONDS)) break;
        }
        cancellation.throwIfCancelled();
        return new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00
        };
    }

    @Override
    public void close() {
        closeCalls.incrementAndGet();
    }

    private static PicacomicRemoteModels.Album album() {
        PicacomicRemoteModels.Image cover = new PicacomicRemoteModels.Image(
            "cover.jpg", "https://img.example.invalid", "/album-1/cover.jpg", null);
        PicacomicRemoteModels.Photo chapterOne = new PicacomicRemoteModels.Photo(
            "album-1", "chapter-1", "Chapter One", "2026-01-01", 1, false,
            Arrays.asList(
                new PicacomicRemoteModels.Image("page-1.png", "https://img.example.invalid",
                    "/album-1/one/page-1.png", null),
                new PicacomicRemoteModels.Image("page-2.png", "https://img.example.invalid",
                    "/album-1/one/page-2.png", null)));
        PicacomicRemoteModels.Photo chapterTwo = new PicacomicRemoteModels.Photo(
            "album-1", "chapter-2", "Chapter Two", "2026-01-02", 2, false,
            Collections.singletonList(new PicacomicRemoteModels.Image(
                "page-1.png", "https://img.example.invalid", "/album-1/two/page-1.png", null)));
        return new PicacomicRemoteModels.Album("album-1", "Fixture Album", "Fixture Author",
            "Fixture Team", Collections.singletonList("Action"),
            Collections.singletonList("fixture"), cover, "Fixture description", 3, 2,
            false, "2026-01-01", "2026-01-02", Arrays.asList(chapterTwo, chapterOne));
    }
}