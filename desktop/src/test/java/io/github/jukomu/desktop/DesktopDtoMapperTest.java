package io.github.jukomu.desktop;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.service.DesktopDtoMapper;
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta;
import io.github.jukomu.jmcomic.api.model.JmComment;
import io.github.jukomu.jmcomic.api.model.JmCommentList;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.api.model.JmSearchPage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesktopDtoMapperTest {
    @Test
    void mapsCatalogModelsToTheFrontendContract() {
        JmSearchPage page = new JmSearchPage(
                2,
                5,
                3,
                List.of(new JmAlbumMeta("album-1", "测试本子", List.of("作者"), List.of("标签")))
        );

        DesktopDtos.SearchResult result = DesktopDtoMapper.searchResult(
                page,
                id -> "https://example.test/" + id + ".jpg"
        );

        assertEquals(2, result.currentPage());
        assertEquals(5, result.totalItems());
        assertEquals("album-1", result.content().getFirst().id());
        assertEquals("https://example.test/album-1.jpg", result.content().getFirst().coverUrl());
        assertEquals(List.of("作者"), result.content().getFirst().authors());
    }

    @Test
    void mapsPhotoImagesAndNestedCommentsWithoutNullCollections() {
        JmImage image = new JmImage(
                "photo-1", "scramble", "page.webp", "https://example.test/page.webp", "v=1", 1
        );
        DesktopDtos.PhotoDetail photo = DesktopDtoMapper.photo(new JmPhoto(
                "photo-1", "第一章", "album-1", "scramble", 1, "作者", null, List.of(image), false
        ));
        JmComment comment = new JmComment(
                "comment-1", "user-1", "user", "内容", "2026-09-11", "", "Lv.1",
                "album-1", "JMalbum-1", List.of(), 3, 1
        );
        DesktopDtos.CommentList comments = DesktopDtoMapper.comments(
                new JmCommentList(1, List.of(comment))
        );

        assertEquals("photo-1", photo.id());
        assertEquals("作者", photo.author());
        assertEquals("page.webp", photo.images().getFirst().filename());
        assertEquals(1, comments.total());
        assertEquals("内容", comments.list().getFirst().content());
        assertEquals(3, comments.list().getFirst().voteUp());
        assertEquals(List.of(), comments.list().getFirst().replys());
    }
}
