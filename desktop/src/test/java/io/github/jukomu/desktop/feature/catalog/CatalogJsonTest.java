package io.github.jukomu.desktop.feature.catalog;

import io.github.jukomu.jmcomic.api.model.JmAlbumMeta;
import io.github.jukomu.jmcomic.api.model.JmComment;
import io.github.jukomu.jmcomic.api.model.JmCommentList;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.api.model.JmSearchPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CatalogJsonTest {
    @Test
    void mapsSearchAndPhotoToTheExistingFrontendFieldNames() {
        JmAlbumMeta meta = new JmAlbumMeta("10", "标题", List.of("作者"), List.of("标签"));
        var search = CatalogJson.searchPage(new JmSearchPage(2, 21, 3, List.of(meta)),
                id -> "https://cover/" + id);

        assertEquals(2, search.path("currentPage").asInt());
        assertEquals("https://cover/10", search.path("content").get(0).path("coverUrl").asText());
        assertEquals("作者", search.path("content").get(0).path("authors").get(0).asText());

        JmImage image = new JmImage("20", "300000", "001.webp", "https://image/001.webp", "v=1", 1);
        var photo = CatalogJson.photo(new JmPhoto(
                "20", "章节", "10", "300000", 1, "作者", List.of("标签"), List.of(image), false
        ));
        assertEquals("20", photo.path("images").get(0).path("photoId").asText());
        assertEquals("v=1", photo.path("images").get(0).path("queryParams").asText());
        assertFalse(photo.path("isSingleEpisode").asBoolean());
    }

    @Test
    void mapsNestedCommentRepliesAndEmptyCollections() {
        JmComment reply = new JmComment(
                "2", "u2", "reply", "内容", "now", "", "Lv.1", "10", "JM10", List.of(), 1, 0
        );
        JmComment root = new JmComment(
                "1", "u1", "root", "正文", "now", "", "Lv.2", "10", "JM10", List.of(reply), 2, 0
        );
        var comments = CatalogJson.comments(new JmCommentList(1, List.of(root)));

        assertEquals(1, comments.path("total").asInt());
        assertEquals("2", comments.path("list").get(0).path("replys").get(0).path("commentId").asText());
        assertTrue(CatalogJson.comments(new JmCommentList(0, null)).path("list").isEmpty());
    }

    @Test
    void derivesTheImageContentTypeFromTheUpstreamFilename() {
        assertEquals("image/jpeg", JmComicCatalogService.mediaType("001.JPG"));
        assertEquals("image/webp", JmComicCatalogService.mediaType("001.webp"));
        assertEquals("application/octet-stream", JmComicCatalogService.mediaType("001.bin"));
    }
}
