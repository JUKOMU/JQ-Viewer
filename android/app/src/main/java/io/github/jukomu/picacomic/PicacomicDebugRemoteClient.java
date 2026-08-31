package io.github.jukomu.picacomic;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic debug-only fixture for the internal UI route.
 *
 * <p>This client has no network implementation and is selected only when the
 * Android application is built with {@code BuildConfig.DEBUG}. Release builds
 * keep the unavailable CP2 default client.</p>
 */
final class PicacomicDebugRemoteClient implements PicacomicRemoteClient {

    private static final byte[] PNG_HEADER = new byte[]{
        (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        0x00, 0x00, 0x00, 0x00
    };

    private final List<PicacomicRemoteModels.Album> albums = Arrays.asList(
        album("fixture-album-1", "Debug Fixture: Moonlit Archive", "Fixture Author A"),
        album("fixture-album-2", "Debug Fixture: Paper Garden", "Fixture Author B"));

    @Override
    public PicacomicRemoteModels.User login(String usernameOrEmail, String password,
                                            PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        return new PicacomicRemoteModels.User(
            "debug-user", usernameOrEmail, "", "");
    }

    @Override
    public PicacomicRemoteModels.Page search(String query, String order, int page,
                                             PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        throwScenario(query);
        if ("empty".equalsIgnoreCase(value(query))) {
            return new PicacomicRemoteModels.Page(page, 1, 0, Collections.emptyList());
        }
        return page(page);
    }

    @Override
    public PicacomicRemoteModels.Page categories(String category, String order, int page,
                                                 PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        throwScenario(category);
        if ("empty".equalsIgnoreCase(value(category))) {
            return new PicacomicRemoteModels.Page(page, 1, 0, Collections.emptyList());
        }
        return page(page);
    }

    @Override
    public PicacomicRemoteModels.Album getAlbum(String albumId,
                                                PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        for (PicacomicRemoteModels.Album album : albums) {
            if (album.id.equals(albumId)) return album;
        }
        throw new PicacomicRemoteException(PicacomicRemoteException.Kind.NOT_FOUND, 404);
    }

    @Override
    public PicacomicRemoteModels.Photo getPhoto(String albumId, int order,
                                                PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        PicacomicRemoteModels.Album album = getAlbum(albumId, cancellation);
        for (PicacomicRemoteModels.Photo photo : album.photos) {
            if (photo.order == order) return photo;
        }
        throw new PicacomicRemoteException(PicacomicRemoteException.Kind.NOT_FOUND, 404);
    }

    @Override
    public byte[] fetchImageBytes(PicacomicImageSource source,
                                  PicacomicCancellationToken cancellation)
        throws Exception {
        cancellation.throwIfCancelled();
        return PNG_HEADER.clone();
    }

    @Override
    public void close() {
        // The fixture owns no external resource.
    }

    private PicacomicRemoteModels.Page page(int page) {
        if (page <= 0 || page > albums.size()) {
            return new PicacomicRemoteModels.Page(page, albums.size(), albums.size(),
                Collections.emptyList());
        }
        return new PicacomicRemoteModels.Page(page, albums.size(), albums.size(),
            Collections.singletonList(albums.get(page - 1)));
    }

    private static void throwScenario(String value) throws PicacomicRemoteException {
        switch (value.toLowerCase()) {
            case "401":
                throw PicacomicRemoteException.unauthorized();
            case "403":
                throw PicacomicRemoteException.forbidden();
            case "network":
                throw new PicacomicRemoteException(PicacomicRemoteException.Kind.NETWORK);
            case "parse":
                throw new PicacomicRemoteException(PicacomicRemoteException.Kind.PARSE);
            default:
                return;
        }
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static PicacomicRemoteModels.Album album(String id, String title, String author) {
        PicacomicRemoteModels.Image cover = new PicacomicRemoteModels.Image(
            "cover.jpg", "debug-fixture", "/" + id + "/cover.jpg", null);
        PicacomicRemoteModels.Photo chapterOne = new PicacomicRemoteModels.Photo(
            id, "chapter-1", "Archive entry", "2026-08-01", 1, false,
            Arrays.asList(
                image(id, "chapter-1", 1), image(id, "chapter-1", 2),
                image(id, "chapter-1", 3)));
        PicacomicRemoteModels.Photo chapterTwo = new PicacomicRemoteModels.Photo(
            id, "chapter-2", "Garden entry", "2026-08-02", 2, false,
            Arrays.asList(image(id, "chapter-2", 1), image(id, "chapter-2", 2)));
        return new PicacomicRemoteModels.Album(id, title, author, "Debug Team",
            Collections.singletonList("debug"), Collections.singletonList("fixture"), cover,
            "A local fake album for the CP3 debug-only read flow.", 5, 2, false,
            "2026-08-01", "2026-08-02", Arrays.asList(chapterTwo, chapterOne));
    }

    private static PicacomicRemoteModels.Image image(String albumId, String chapterId, int page) {
        return new PicacomicRemoteModels.Image(
            "page-" + page + ".png", "debug-fixture",
            "/" + albumId + "/" + chapterId + "/page-" + page + ".png", null);
    }
}
