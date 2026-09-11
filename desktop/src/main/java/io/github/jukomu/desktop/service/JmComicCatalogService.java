package io.github.jukomu.desktop.service;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.error.DesktopHttpException;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.ForumMode;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import io.github.jukomu.jmcomic.api.model.ForumQuery;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.api.model.SearchQuery;
import io.github.jukomu.jmcomic.core.client.impl.JmApiClient;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 使用真实 JmApiClient 提供目录、章节和图片资源。 */
public final class JmComicCatalogService implements DesktopCatalogService {
    private static final String SEARCH_COVER_SIZE = "_3x4";
    private static final String TYPE_IMAGE = "image";
    private static final String TYPE_THUMB = "thumb";

    private final JmApiClient client;
    private final Map<ImageKey, JmImage> knownImages = new ConcurrentHashMap<>();

    public JmComicCatalogService(JmApiClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public DesktopDtos.SearchResult search(DesktopDtos.SearchQuery query) {
        SearchQuery jmQuery = buildSearchQuery(query, true);
        return DesktopDtoMapper.searchResult(client.search(jmQuery), this::coverUrl);
    }

    @Override
    public DesktopDtos.SearchResult categories(DesktopDtos.SearchQuery query) {
        SearchQuery jmQuery = buildSearchQuery(query, false);
        return DesktopDtoMapper.searchResult(client.getCategories(jmQuery), this::coverUrl);
    }

    @Override
    public DesktopDtos.AlbumDetail getAlbum(String id) {
        return DesktopDtoMapper.album(client.getAlbum(requireId(id)), this::coverUrl);
    }

    @Override
    public DesktopDtos.PhotoDetail getPhoto(String id) {
        JmPhoto photo = client.getPhoto(requireId(id));
        List<JmImage> images = photo.getImages() == null ? List.of() : photo.getImages();
        for (JmImage image : images) {
            knownImages.put(new ImageKey(photo.getId(), image.getSortOrder()), image);
        }
        return DesktopDtoMapper.photo(photo);
    }

    @Override
    public DesktopDtos.CommentList getComments(String albumId, int page) {
        String id = requireId(albumId);
        if (page < 1) {
            throw new IllegalArgumentException("page must be greater than or equal to 1");
        }
        return DesktopDtoMapper.comments(
                client.getComments(ForumQuery.album(id).mode(ForumMode.ALL).page(page).build())
        );
    }

    @Override
    public DesktopDtos.ImageResource getImage(String photoId, int sortOrder, String type) {
        String id = requireId(photoId);
        if (sortOrder < 1) {
            throw new IllegalArgumentException("sortOrder must be greater than or equal to 1");
        }
        if (!TYPE_IMAGE.equals(type) && !TYPE_THUMB.equals(type)) {
            throw new IllegalArgumentException("type must be image or thumb");
        }
        JmImage image = knownImages.get(new ImageKey(id, sortOrder));
        if (image == null) {
            throw new DesktopHttpException(
                    "not-found",
                    404,
                    "章节图片尚未加载，请先打开章节详情"
            );
        }
        byte[] bytes = client.fetchImageBytes(image);
        return new DesktopDtos.ImageResource(bytes, mediaType(image.getFilename()));
    }

    private SearchQuery buildSearchQuery(DesktopDtos.SearchQuery query, boolean requireKeyword) {
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        String keyword = text(query.keyword()).trim();
        if (requireKeyword && keyword.isEmpty()) {
            throw new IllegalArgumentException("keyword is required");
        }
        if (requireKeyword && keyword.matches("\\d+")) {
            throw new IllegalArgumentException("keyword must not be numeric");
        }
        int page = query.page() == null ? 1 : query.page();
        if (page < 1) {
            throw new IllegalArgumentException("page must be greater than or equal to 1");
        }
        return new SearchQuery.Builder()
                .text(keyword)
                .category(category(query.category()))
                .orderBy(orderBy(query.orderBy()))
                .time(time(query.time()))
                .mainTag(searchMainTag(query.searchMainTag()))
                .page(page)
                .build();
    }

    private String coverUrl(String albumId) {
        return client.getAlbumCoverUrl(albumId, SEARCH_COVER_SIZE);
    }

    private static Category category(String value) {
        String candidate = text(value);
        for (Category item : Category.values()) {
            if (item.getValue().equals(candidate)) {
                return item;
            }
        }
        return Category.ALL;
    }

    private static OrderBy orderBy(String value) {
        String candidate = text(value);
        for (OrderBy item : OrderBy.values()) {
            if (item.getValue().equals(candidate)) {
                return item;
            }
        }
        return OrderBy.LATEST;
    }

    private static TimeOption time(String value) {
        String candidate = text(value);
        for (TimeOption item : TimeOption.values()) {
            if (item.getValue().equals(candidate)) {
                return item;
            }
        }
        return TimeOption.ALL;
    }

    private static SearchMainTag searchMainTag(Integer value) {
        int candidate = value == null ? SearchMainTag.SITE_SEARCH.getValue() : value;
        for (SearchMainTag item : SearchMainTag.values()) {
            if (item.getValue() == candidate) {
                return item;
            }
        }
        return SearchMainTag.SITE_SEARCH;
    }

    private static String requireId(String value) {
        String id = text(value).trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id is required");
        }
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("id contains unsupported characters");
        }
        return id;
    }

    private static String mediaType(String filename) {
        String lower = text(filename).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".avif")) return "image/avif";
        return "application/octet-stream";
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private record ImageKey(String photoId, int sortOrder) {
    }
}
