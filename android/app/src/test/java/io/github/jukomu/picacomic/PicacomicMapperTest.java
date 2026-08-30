package io.github.jukomu.picacomic;

import io.github.jukomu.feature.cache.ImageCache;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PicacomicMapperTest {

    private final ImageCache cache = ImageCache.getInstance();
    private PicacomicImageRegistry registry;

    @Before
    public void setUp() {
        PicacomicCacheNamespace.clear(cache);
        registry = new PicacomicImageRegistry();
    }

    @After
    public void tearDown() {
        PicacomicCacheNamespace.clear(cache);
    }

    @Test
    public void mapsCatalogAndAlbumFieldsAndSortsChapterOrder() throws Exception {
        PicacomicRemoteModels.Album album = FakePicacomicRemoteClient.initialAlbum();
        PicacomicRemoteModels.Page page = new PicacomicRemoteModels.Page(
            1, 1, 1, Collections.singletonList(album));

        PicacomicCatalogPage mappedPage = PicacomicMapper.mapCatalogPage(page, registry);
        assertEquals(1, mappedPage.currentPage);
        assertEquals(1, mappedPage.totalPages);
        assertEquals("album-1", mappedPage.items.get(0).ref.albumId);
        assertEquals(Collections.singletonList("Fixture Author"),
            mappedPage.items.get(0).authors);
        assertEquals("Fixture Team", mappedPage.items.get(0).translator);
        assertNotNull(mappedPage.items.get(0).cover);
        assertTrue(mappedPage.items.get(0).cover.imageKey.startsWith(
            PicacomicCacheNamespace.KEY_PREFIX));

        PicacomicAlbumDetail detail = PicacomicMapper.mapAlbumDetail(album, registry);
        assertEquals(2, detail.chapters.size());
        assertEquals("chapter-1", detail.chapters.get(0).ref.chapterId);
        assertEquals(1, detail.chapters.get(0).ref.order);
        assertEquals("chapter-2", detail.chapters.get(1).ref.chapterId);
        assertEquals(2, detail.chapters.get(1).ref.order);
        assertEquals(3, detail.pagesCount);
    }

    @Test
    public void mapsEmptyCatalogWithoutInventingItems() throws Exception {
        PicacomicRemoteModels.Page empty = new PicacomicRemoteModels.Page(1, 0, 0,
            Collections.emptyList());

        PicacomicCatalogPage mapped = PicacomicMapper.mapCatalogPage(empty, registry);

        assertEquals(1, mapped.currentPage);
        assertEquals(0, mapped.totalPages);
        assertEquals(0, mapped.totalItems);
        assertTrue(mapped.items.isEmpty());
    }

    @Test
    public void rejectsDuplicateChapterOrderAsInvalidResponse() {
        PicacomicRemoteModels.Album album = FakePicacomicRemoteClient.initialAlbum();
        PicacomicRemoteModels.Photo first = album.photos.get(0);
        PicacomicRemoteModels.Photo duplicate = new PicacomicRemoteModels.Photo(
            album.id, "chapter-3", "Duplicate", "2026-01-03", first.order,
            false, first.images);
        PicacomicRemoteModels.Album invalid = new PicacomicRemoteModels.Album(
            album.id, album.title, album.author, album.chineseTeam, album.categories,
            album.tags, album.thumb, album.description, album.pagesCount, album.epsCount,
            album.finished, album.createdAt, album.updatedAt,
            Arrays.asList(first, duplicate));

        try {
            PicacomicMapper.mapAlbumDetail(invalid, registry);
            fail("duplicate order must be rejected");
        } catch (PicacomicException error) {
            assertEquals(PicacomicErrorCode.INVALID_RESPONSE, error.getErrorCode());
        }
    }

    @Test
    public void chapterPageIndexIsOneBasedAndLocatorStaysNative() throws Exception {
        PicacomicRemoteModels.Album album = FakePicacomicRemoteClient.initialAlbum();
        PicacomicRemoteModels.Photo raw = album.photos.get(1);
        ChapterRef ref = ChapterRef.of(album.id, raw.id, raw.order);

        PicacomicChapterDetail detail = PicacomicMapper.mapChapterDetail(raw, ref, registry);

        assertEquals(1, detail.images.get(0).pageIndex);
        assertEquals(2, detail.images.get(1).pageIndex);
        assertEquals("/album-1/one/page-1.png",
            registry.resolve(detail.images.get(0).imageKey).path);
    }

    @Test
    public void contentRevisionChangesWhenLocatorChanges() {
        PicacomicRemoteModels.Image first = new PicacomicRemoteModels.Image(
            "page.jpg", "https://img.example.invalid", "/one/page.jpg", null);
        PicacomicRemoteModels.Image changed = new PicacomicRemoteModels.Image(
            "page.jpg", "https://img.example.invalid", "/two/page.jpg", null);

        String oldRevision = PicacomicCacheNamespace.contentRevision("same-time",
            Collections.singletonList(first));
        String newRevision = PicacomicCacheNamespace.contentRevision("same-time",
            Collections.singletonList(changed));

        assertTrue(!oldRevision.equals(newRevision));
    }

    @Test
    public void chapterIdentityIgnoresOrderButLocatorDoesNot() {
        ChapterRef oldRef = ChapterRef.of("album-1", "chapter-1", 1);
        ChapterRef movedRef = oldRef.withOrder(3);

        assertTrue(oldRef.sameChapterIdentity(movedRef));
        assertTrue(!oldRef.equals(movedRef));
        assertTrue(!oldRef.locatorKey().equals(movedRef.locatorKey()));
    }
}
