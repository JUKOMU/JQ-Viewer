package io.github.jukomu.desktop.feature.catalog;

import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 使用当前进程的 JmApiClient 提供目录数据和图片资源。 */
public final class JmComicCatalogService implements AutoCloseable {
    private static final String SEARCH_COVER_SIZE = "_3x4";
    private static final int MAX_KNOWN_IMAGES = 512;

    private final JmApiClient client;
    private final Map<ImageKey, JmImage> knownImages = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ImageKey, JmImage> eldest) {
            return size() > MAX_KNOWN_IMAGES;
        }
    };

    public JmComicCatalogService(JmApiClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public ObjectNode search(ObjectNode query) {
        return CatalogJson.searchPage(client.search(buildQuery(query, true)), this::coverUrl);
    }

    public ObjectNode categories(ObjectNode query) {
        return CatalogJson.searchPage(client.getCategories(buildQuery(query, false)), this::coverUrl);
    }

    public ObjectNode getAlbum(String id) {
        return CatalogJson.album(client.getAlbum(requireId(id, "id")), this::coverUrl);
    }

    public ObjectNode getPhoto(String id) {
        JmPhoto photo = client.getPhoto(requireId(id, "id"));
        remember(photo);
        return CatalogJson.photo(photo);
    }

    public ObjectNode getComments(String albumId, int page) {
        if (page < 1) throw new IllegalArgumentException("page must be greater than or equal to 1");
        ForumQuery query = ForumQuery.album(requireId(albumId, "albumId"))
                .mode(ForumMode.ALL)
                .page(page)
                .build();
        return CatalogJson.comments(client.getComments(query));
    }

    public ImageResource getImage(String photoId, int sortOrder) {
        String id = requireId(photoId, "photoId");
        if (sortOrder < 1) {
            throw new IllegalArgumentException("sortOrder must be greater than or equal to 1");
        }
        JmImage image;
        synchronized (knownImages) {
            image = knownImages.get(new ImageKey(id, sortOrder));
        }
        if (image == null) {
            remember(client.getPhoto(id));
            synchronized (knownImages) {
                image = knownImages.get(new ImageKey(id, sortOrder));
            }
        }
        if (image == null) {
            throw new DesktopHttpException("not-found", 404, "章节中不存在指定图片");
        }
        return new ImageResource(client.fetchImageBytes(image), mediaType(image.getFilename()));
    }

    private SearchQuery buildQuery(ObjectNode query, boolean requireKeyword) {
        String keyword = text(query, "keyword", "").trim();
        if (requireKeyword && keyword.isEmpty()) {
            throw new IllegalArgumentException("keyword is required");
        }
        int page = integer(query, "page", 1);
        if (page < 1) throw new IllegalArgumentException("page must be greater than or equal to 1");
        return new SearchQuery.Builder()
                .text(keyword)
                .category(category(text(query, "category", "0")))
                .orderBy(orderBy(text(query, "orderBy", "mr")))
                .time(time(text(query, "time", "a")))
                .mainTag(mainTag(integer(query, "searchMainTag", 0)))
                .page(page)
                .build();
    }

    private void remember(JmPhoto photo) {
        synchronized (knownImages) {
            for (JmImage image : list(photo.getImages())) {
                knownImages.put(new ImageKey(photo.getId(), image.getSortOrder()), image);
            }
        }
    }

    private String coverUrl(String albumId) {
        return client.getAlbumCoverUrl(albumId, SEARCH_COVER_SIZE);
    }

    private static SearchMainTag mainTag(int value) {
        for (SearchMainTag item : SearchMainTag.values()) {
            if (item.getValue() == value) return item;
        }
        return SearchMainTag.SITE_SEARCH;
    }

    private static Category category(String value) {
        for (Category item : Category.values()) {
            if (item.getValue().equals(value)) return item;
        }
        return Category.ALL;
    }

    private static OrderBy orderBy(String value) {
        for (OrderBy item : OrderBy.values()) {
            if (item.getValue().equals(value)) return item;
        }
        return OrderBy.LATEST;
    }

    private static TimeOption time(String value) {
        for (TimeOption item : TimeOption.values()) {
            if (item.getValue().equals(value)) return item;
        }
        return TimeOption.ALL;
    }

    private static String requireId(String value, String name) {
        String id = value == null ? "" : value.trim();
        if (id.isEmpty()) throw new IllegalArgumentException(name + " is required");
        if (!id.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException(name + " contains unsupported characters");
        }
        return id;
    }

    private static String text(ObjectNode node, String name, String fallback) {
        return node.hasNonNull(name) ? node.get(name).asText(fallback) : fallback;
    }

    private static int integer(ObjectNode node, String name, int fallback) {
        if (!node.hasNonNull(name)) return fallback;
        if (!node.get(name).isIntegralNumber()) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        return node.get(name).intValue();
    }

    private static List<JmImage> list(List<JmImage> value) {
        return value == null ? List.of() : value;
    }

    static String mediaType(String filename) {
        String lower = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".avif")) return "image/avif";
        return "application/octet-stream";
    }

    @Override
    public void close() {
        synchronized (knownImages) {
            knownImages.clear();
        }
    }

    public record ImageResource(byte[] bytes, String mediaType) {
    }

    private record ImageKey(String photoId, int sortOrder) {
    }
}
