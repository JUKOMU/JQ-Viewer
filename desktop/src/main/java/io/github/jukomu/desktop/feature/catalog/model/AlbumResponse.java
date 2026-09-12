package io.github.jukomu.desktop.feature.catalog.model;

import java.util.List;

/** 返回作品详情页所需的完整作品信息。 */
public record AlbumResponse(
        String id,
        String title,
        String description,
        String addTime,
        int pageCount,
        String likes,
        String views,
        int commentCount,
        String image,
        CategoryResponse category,
        CategoryResponse subCategory,
        List<String> authors,
        List<String> works,
        List<String> actors,
        List<String> tags,
        List<AlbumSummaryResponse> relatedAlbums,
        List<PhotoSummaryResponse> photoMetas,
        String seriesId,
        boolean isSingleEpisode,
        boolean isFavorite,
        boolean isLiked,
        String price,
        String purchased
) {
}
