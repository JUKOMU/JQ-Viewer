package io.github.jukomu.desktop.feature.catalog;

import io.github.jukomu.desktop.feature.catalog.model.AlbumResponse;
import io.github.jukomu.desktop.feature.catalog.model.AlbumSummaryResponse;
import io.github.jukomu.desktop.feature.catalog.model.CategoryResponse;
import io.github.jukomu.desktop.feature.catalog.model.CommentListResponse;
import io.github.jukomu.desktop.feature.catalog.model.ImageResponse;
import io.github.jukomu.desktop.feature.catalog.model.PhotoResponse;
import io.github.jukomu.desktop.feature.catalog.model.PhotoSummaryResponse;
import io.github.jukomu.desktop.feature.catalog.model.SearchRequest;
import io.github.jukomu.desktop.feature.catalog.model.SearchResponse;
import io.github.jukomu.desktop.feature.image.ImageService;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.ForumMode;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import io.github.jukomu.jmcomic.api.model.ForumQuery;
import io.github.jukomu.jmcomic.api.model.JmAlbum;
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta;
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta;
import io.github.jukomu.jmcomic.api.model.JmComment;
import io.github.jukomu.jmcomic.api.model.JmCommentList;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.api.model.JmPhotoMeta;
import io.github.jukomu.jmcomic.api.model.JmSearchPage;
import io.github.jukomu.jmcomic.api.model.SearchQuery;

import java.util.List;
import java.util.function.Function;

/** 调用在线客户端并转换为页面使用的响应模型。 */
public final class CatalogService {
    private final JmClient client;
    private final ImageService imageService;
    private final Function<String, String> albumCoverUrl;

    public CatalogService(
            JmClient client,
            ImageService imageService,
            Function<String, String> albumCoverUrl
    ) {
        this.client = client;
        this.imageService = imageService;
        this.albumCoverUrl = albumCoverUrl;
    }

    public SearchResponse search(SearchRequest request) {
        return toSearchResponse(client.search(query(request)));
    }

    public SearchResponse categories(SearchRequest request) {
        return toSearchResponse(client.getCategories(query(request)));
    }

    public AlbumResponse getAlbum(String id) {
        return toAlbumResponse(client.getAlbum(id));
    }

    public PhotoResponse getPhoto(String id) {
        JmPhoto photo = client.getPhoto(id);
        imageService.register(photo);
        return toPhotoResponse(photo);
    }

    public CommentListResponse getComments(String albumId, int page) {
        JmCommentList comments = client.getComments(
                ForumQuery.album(albumId).mode(ForumMode.ALL).page(page).build());
        return new CommentListResponse(
                comments.getTotal(),
                safe(comments.getList()).stream().map(this::toCommentResponse).toList()
        );
    }

    private SearchQuery query(SearchRequest request) {
        return new SearchQuery.Builder()
                .text(text(request.keyword()))
                .category(category(text(request.category())))
                .orderBy(orderBy(text(request.orderBy())))
                .time(time(text(request.time())))
                .mainTag(mainTag(request.searchMainTag() == null ? 0 : request.searchMainTag()))
                .page(Math.max(1, request.page() == null ? 1 : request.page()))
                .build();
    }

    private static Category category(String value) {
        for (Category candidate : Category.values()) {
            if (candidate.getValue().equals(value)) return candidate;
        }
        return Category.ALL;
    }

    private static OrderBy orderBy(String value) {
        for (OrderBy candidate : OrderBy.values()) {
            if (candidate.getValue().equals(value)) return candidate;
        }
        return OrderBy.LATEST;
    }

    private static TimeOption time(String value) {
        for (TimeOption candidate : TimeOption.values()) {
            if (candidate.getValue().equals(value)) return candidate;
        }
        return TimeOption.ALL;
    }

    private static SearchMainTag mainTag(int value) {
        for (SearchMainTag candidate : SearchMainTag.values()) {
            if (candidate.getValue() == value) return candidate;
        }
        return SearchMainTag.SITE_SEARCH;
    }

    private SearchResponse toSearchResponse(JmSearchPage page) {
        return new SearchResponse(
                page.getCurrentPage(),
                page.getTotalItems(),
                page.getTotalPages(),
                safe(page.getContent()).stream().map(this::toAlbumSummaryResponse).toList()
        );
    }

    private AlbumResponse toAlbumResponse(JmAlbum album) {
        return new AlbumResponse(
                text(album.getId()),
                text(album.getTitle()),
                text(album.getDescription()),
                text(album.getAddTime()),
                album.getPageCount(),
                text(album.getLikes()),
                text(album.getViews()),
                album.getCommentCount(),
                albumCoverUrl.apply(album.getId()),
                toCategoryResponse(album.getCategory()),
                toCategoryResponse(album.getSubCategory()),
                strings(album.getAuthors()),
                strings(album.getWorks()),
                strings(album.getActors()),
                strings(album.getTags()),
                safe(album.getRelatedAlbums()).stream().map(this::toAlbumSummaryResponse).toList(),
                safe(album.getPhotoMetas()).stream().map(CatalogService::toPhotoSummaryResponse).toList(),
                text(album.getSeriesId()),
                album.isSingleAlbum(),
                album.isFavorite(),
                album.isLiked(),
                text(album.getPrice()),
                text(album.getPurchased())
        );
    }

    private AlbumSummaryResponse toAlbumSummaryResponse(JmAlbumMeta album) {
        return new AlbumSummaryResponse(
                text(album.getId()),
                text(album.getTitle()),
                albumCoverUrl.apply(album.getId()),
                strings(album.getAuthors()),
                strings(album.getTags()),
                text(album.getDescription()),
                text(album.getImage()),
                toCategoryResponse(album.getCategory()),
                toCategoryResponse(album.getSubCategory())
        );
    }

    private static PhotoSummaryResponse toPhotoSummaryResponse(JmPhotoMeta photo) {
        return new PhotoSummaryResponse(
                text(photo.getId()),
                text(photo.getTitle()),
                photo.getSortOrder()
        );
    }

    private PhotoResponse toPhotoResponse(JmPhoto photo) {
        return new PhotoResponse(
                text(photo.getId()),
                text(photo.getTitle()),
                text(photo.getAlbumId()),
                photo.getSortOrder(),
                text(photo.getAuthor()),
                strings(photo.getTags()),
                safe(photo.getImages()).stream().map(CatalogService::toImageResponse).toList(),
                photo.isSingleAlbum()
        );
    }

    private static ImageResponse toImageResponse(JmImage image) {
        return new ImageResponse(
                text(image.getPhotoId()),
                text(image.getScrambleId()),
                text(image.getFilename()),
                text(image.getUrl()),
                text(image.getQueryParams()),
                image.getSortOrder()
        );
    }

    private CommentListResponse.Comment toCommentResponse(JmComment comment) {
        return new CommentListResponse.Comment(
                text(comment.getCommentId()),
                text(comment.getUserId()),
                text(comment.getUsername()),
                text(comment.getNickname()),
                text(comment.getContent()),
                text(comment.getPostDate()),
                text(comment.getPhoto()),
                text(comment.getExpinfo()),
                text(comment.getAid()),
                text(comment.getName()),
                comment.getLikes(),
                comment.getVoteUp(),
                comment.getVoteDown(),
                safe(comment.getReplys()).stream().map(this::toCommentResponse).toList()
        );
    }

    private static CategoryResponse toCategoryResponse(JmCategoryMeta category) {
        return category == null
                ? null
                : new CategoryResponse(text(category.getId()), text(category.getTitle()));
    }

    private static List<String> strings(List<String> values) {
        return safe(values).stream().map(CatalogService::text).toList();
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
