package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fake-only provider seam used by the CP2 JVM contract tests. */
final class FakePicacomicRemoteClient implements PicacomicRemoteClient {

    enum Failure {
        NONE,
        UNAUTHORIZED,
        FORBIDDEN,
        NETWORK,
        PARSE,
        CANCELLED
    }

    volatile Failure loginFailure = Failure.NONE;
    volatile Failure catalogFailure = Failure.NONE;
    volatile Failure chapterFailure = Failure.NONE;
    volatile Failure imageFailure = Failure.NONE;
    volatile boolean returnWrongFirstChapter;
    volatile boolean omitRequestedChapter;
    volatile boolean blockImage;
    volatile boolean returnInvalidImageBytes;
    volatile byte[] imageBytes;

    final AtomicInteger loginCalls = new AtomicInteger();
    final AtomicInteger getAlbumCalls = new AtomicInteger();
    final AtomicInteger getPhotoCalls = new AtomicInteger();
    final AtomicInteger imageCalls = new AtomicInteger();
    final AtomicInteger closeCalls = new AtomicInteger();
    final CountDownLatch imageStarted = new CountDownLatch(1);
    final CountDownLatch releaseImage = new CountDownLatch(1);

    private volatile PicacomicRemoteModels.Album album = initialAlbum();
    private volatile boolean loggedIn;

    void setAlbum(PicacomicRemoteModels.Album value) {
        album = value;
    }

    @Override
    public PicacomicRemoteModels.User login(String usernameOrEmail, String password,
                                            PicacomicCancellationToken cancellation)
        throws Exception {
        loginCalls.incrementAndGet();
        cancellation.throwIfCancelled();
        throwFailure(loginFailure);
        loggedIn = true;
        return new PicacomicRemoteModels.User("user-1", "fixture-user",
            "fixture@example.invalid", "https://example.invalid/avatar");
    }

    @Override
    public PicacomicRemoteModels.Page search(String query, String order, int page,
                                             PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        throwFailure(catalogFailure);
        return new PicacomicRemoteModels.Page(page, 2, 1,
            Collections.singletonList(album));
    }

    @Override
    public PicacomicRemoteModels.Page categories(String category, String order, int page,
                                                 PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        throwFailure(catalogFailure);
        return new PicacomicRemoteModels.Page(page, 2, 1,
            Collections.singletonList(album));
    }

    @Override
    public PicacomicRemoteModels.Album getAlbum(String albumId,
                                                PicacomicCancellationToken cancellation)
        throws Exception {
        getAlbumCalls.incrementAndGet();
        cancellation.throwIfCancelled();
        throwFailure(chapterFailure);
        if (!album.id.equals(albumId)) {
            throw new PicacomicRemoteException(PicacomicRemoteException.Kind.NOT_FOUND, 404);
        }
        if (!omitRequestedChapter) return album;
        List<PicacomicRemoteModels.Photo> photos = new ArrayList<>();
        for (PicacomicRemoteModels.Photo photo : album.photos) {
            if (!"chapter-1".equals(photo.id)) photos.add(photo);
        }
        return new PicacomicRemoteModels.Album(album.id, album.title, album.author,
            album.chineseTeam, album.categories, album.tags, album.thumb, album.description,
            album.pagesCount, album.epsCount, album.finished, album.createdAt,
            album.updatedAt, photos);
    }

    @Override
    public PicacomicRemoteModels.Photo getPhoto(String albumId, int order,
                                                PicacomicCancellationToken cancellation)
        throws Exception {
        int call = getPhotoCalls.incrementAndGet();
        cancellation.throwIfCancelled();
        throwFailure(chapterFailure);
        if (returnWrongFirstChapter && call == 1) {
            return findPhoto(2);
        }
        return findPhoto(order);
    }

    @Override
    public byte[] fetchImageBytes(PicacomicImageSource source,
                                  PicacomicCancellationToken cancellation)
        throws Exception {
        imageCalls.incrementAndGet();
        imageStarted.countDown();
        throwFailure(imageFailure);
        while (blockImage && !cancellation.isCancelled()) {
            if (releaseImage.await(10, TimeUnit.MILLISECONDS)) break;
        }
        cancellation.throwIfCancelled();
        if (imageBytes != null) return imageBytes;
        if (returnInvalidImageBytes) return new byte[]{1, 2, 3, 4};
        return new byte[]{
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            0x00, 0x00, 0x00, 0x00
        };
    }

    @Override
    public void close() {
        closeCalls.incrementAndGet();
        loggedIn = false;
    }

    private PicacomicRemoteModels.Photo findPhoto(int order) throws PicacomicRemoteException {
        for (PicacomicRemoteModels.Photo photo : album.photos) {
            if (photo.order == order) return photo;
        }
        throw new PicacomicRemoteException(PicacomicRemoteException.Kind.NOT_FOUND, 404);
    }

    private static void throwFailure(Failure failure) throws PicacomicRemoteException {
        if (failure == null || failure == Failure.NONE) return;
        switch (failure) {
            case UNAUTHORIZED:
                throw PicacomicRemoteException.unauthorized();
            case FORBIDDEN:
                throw PicacomicRemoteException.forbidden();
            case NETWORK:
                throw new PicacomicRemoteException(PicacomicRemoteException.Kind.NETWORK);
            case PARSE:
                throw new PicacomicRemoteException(PicacomicRemoteException.Kind.PARSE);
            case CANCELLED:
                throw PicacomicRemoteException.cancelled();
            default:
                return;
        }
    }

    static PicacomicRemoteModels.Album initialAlbum() {
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
