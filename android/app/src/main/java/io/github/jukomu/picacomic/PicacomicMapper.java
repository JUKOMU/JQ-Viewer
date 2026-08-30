package io.github.jukomu.picacomic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Maps provider-shaped records into the narrow Picacomic bridge DTOs. */
public final class PicacomicMapper {

    private PicacomicMapper() {
    }

    public static PicacomicUser mapUser(PicacomicRemoteModels.User source)
        throws PicacomicException {
        if (source == null || blank(source.id) || blank(source.username)) {
            throw invalidResponse("login");
        }
        return new PicacomicUser(source.id, source.username,
            value(source.email), value(source.avatar));
    }

    public static PicacomicCatalogPage mapCatalogPage(PicacomicRemoteModels.Page source,
                                                       PicacomicImageRegistry registry)
        throws PicacomicException {
        if (source == null || source.page <= 0 || source.pages < 0
            || (source.pages == 0 && source.total > 0)
            || (source.pages > 0 && source.page > source.pages)
            || source.total < 0 || registry == null) {
            throw invalidResponse("catalog");
        }
        List<PicacomicCatalogItem> items = new ArrayList<>();
        for (PicacomicRemoteModels.Album album : source.albums) {
            items.add(mapCatalogItem(album, registry));
        }
        return new PicacomicCatalogPage(source.page, source.pages, source.total, items);
    }

    public static PicacomicAlbumDetail mapAlbumDetail(PicacomicRemoteModels.Album source,
                                                      PicacomicImageRegistry registry)
        throws PicacomicException {
        if (source == null || registry == null) throw invalidResponse("album");
        AlbumRef ref = albumRef(source.id, "album");
        List<PicacomicChapterSummary> chapters = mapChapterSummaries(source, ref);
        PicacomicImageRef cover = mapCover(source, registry);
        return new PicacomicAlbumDetail(ref, value(source.title), authors(source.author),
            value(source.chineseTeam), source.categories, source.tags, cover,
            value(source.description), nonNegative(source.pagesCount, "album"),
            nonNegative(source.epsCount, "album"), source.finished, value(source.createdAt),
            value(source.updatedAt), chapters);
    }

    public static PicacomicChapterDetail mapChapterDetail(PicacomicRemoteModels.Photo source,
                                                           ChapterRef expected,
                                                           PicacomicImageRegistry registry)
        throws PicacomicException {
        if (source == null || expected == null || registry == null) {
            throw invalidResponse("chapter");
        }
        if (!expected.matchesResponse(source.albumId, source.id, source.order)) {
            throw new PicacomicException(PicacomicErrorCode.STALE_RESOURCE, "chapter");
        }
        if (source.images.isEmpty()) throw invalidResponse("chapter");

        String revision;
        try {
            revision = PicacomicCacheNamespace.contentRevision(source.updatedAt, source.images);
        } catch (IllegalArgumentException error) {
            throw invalidResponse("chapter");
        }
        List<PicacomicImageRef> images = new ArrayList<>();
        for (int index = 0; index < source.images.size(); index++) {
            PicacomicRemoteModels.Image image = source.images.get(index);
            if (image == null) throw invalidResponse("chapter");
            int pageIndex = index + 1;
            PicacomicImageSource locator = PicacomicImageSource.page(
                expected.albumId, expected.chapterId, revision, pageIndex,
                image.originalName, image.fileServer, image.path, image.imageUrl);
            try {
                images.add(registry.register(locator));
            } catch (IllegalArgumentException | IllegalStateException error) {
                throw invalidResponse("chapter");
            }
        }
        return new PicacomicChapterDetail(expected, value(source.title), value(source.updatedAt),
            images, revision, source.isSingleAlbum);
    }

    private static PicacomicCatalogItem mapCatalogItem(PicacomicRemoteModels.Album source,
                                                       PicacomicImageRegistry registry)
        throws PicacomicException {
        if (source == null) throw invalidResponse("catalog");
        AlbumRef ref = albumRef(source.id, "catalog");
        return new PicacomicCatalogItem(ref, value(source.title), authors(source.author),
            value(source.chineseTeam), mapCover(source, registry),
            nonNegative(source.pagesCount, "catalog"), source.finished);
    }

    private static List<PicacomicChapterSummary> mapChapterSummaries(
        PicacomicRemoteModels.Album source, AlbumRef albumRef) throws PicacomicException {
        List<PicacomicRemoteModels.Photo> photos = new ArrayList<>(source.photos);
        Set<String> chapterIds = new HashSet<>();
        Set<Integer> orders = new HashSet<>();
        for (PicacomicRemoteModels.Photo photo : photos) {
            if (photo == null || blank(photo.id) || photo.order <= 0
                || (!blank(photo.albumId) && !albumRef.albumId.equals(photo.albumId))
                || !chapterIds.add(photo.id) || !orders.add(photo.order)) {
                throw invalidResponse("album");
            }
        }
        photos.sort(Comparator.comparingInt(photo -> photo.order));
        List<PicacomicChapterSummary> result = new ArrayList<>();
        for (PicacomicRemoteModels.Photo photo : photos) {
            result.add(new PicacomicChapterSummary(
                ChapterRef.of(albumRef.albumId, photo.id, photo.order),
                value(photo.title), value(photo.updatedAt)));
        }
        return result;
    }

    private static PicacomicImageRef mapCover(PicacomicRemoteModels.Album source,
                                              PicacomicImageRegistry registry)
        throws PicacomicException {
        if (source.thumb == null) return null;
        try {
            String revision = PicacomicCacheNamespace.contentRevision(source.updatedAt,
                Collections.singletonList(source.thumb));
            return registry.register(PicacomicImageSource.cover(source.id, revision,
                source.thumb.originalName, source.thumb.fileServer, source.thumb.path,
                source.thumb.imageUrl));
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw invalidResponse("cover");
        }
    }

    private static AlbumRef albumRef(String albumId, String operation)
        throws PicacomicException {
        try {
            return AlbumRef.of(albumId);
        } catch (IllegalArgumentException error) {
            throw invalidResponse(operation);
        }
    }

    private static List<String> authors(String author) {
        if (blank(author)) return Collections.emptyList();
        return Collections.singletonList(author);
    }

    private static int nonNegative(int value, String operation) throws PicacomicException {
        if (value < 0) throw invalidResponse(operation);
        return value;
    }

    private static PicacomicException invalidResponse(String operation) {
        return new PicacomicException(PicacomicErrorCode.INVALID_RESPONSE, operation);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
