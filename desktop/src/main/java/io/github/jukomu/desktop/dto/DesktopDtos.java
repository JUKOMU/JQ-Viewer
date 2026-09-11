package io.github.jukomu.desktop.dto;

import java.util.List;
import java.util.Map;

/** Desktop HTTP 边界使用的请求、响应和资源 DTO。 */
public final class DesktopDtos {
    private DesktopDtos() {
    }

    public record SearchRequest(SearchQuery query) {
    }

    public record SearchQuery(
            String keyword,
            String category,
            String orderBy,
            String time,
            Integer searchMainTag,
            Integer page
    ) {
    }

    public record IdRequest(String id) {
    }

    public record CommentsRequest(String albumId, Integer page) {
    }

    public record LoginRequest(String username, String password) {
    }

    public record UserInfo(
            String uid,
            String username,
            String email,
            boolean emailVerified,
            String avatarUrl,
            String firstName,
            String gender,
            String message,
            int level,
            String levelName,
            long nextLevelExp,
            long currentExp,
            double expPercent,
            int coin,
            int albumFavorites,
            int maxAlbumFavorites
    ) {
    }

    public record LoginState(boolean loggedIn, String username, UserInfo userInfo) {
    }

    public record UserProfile(
            String username,
            String email,
            String nickname,
            String birthday,
            String city,
            String country,
            String occupation,
            String aboutMe,
            String website
    ) {
    }

    public record SearchResult(
            int currentPage,
            int totalItems,
            int totalPages,
            List<SearchResultItem> content
    ) {
    }

    public record SearchResultItem(
            String id,
            String title,
            String coverUrl,
            List<String> authors,
            List<String> tags
    ) {
    }

    public record CategoryMeta(String id, String title) {
    }

    public record PhotoMeta(String id, String title, int sortOrder) {
    }

    public record ImageInfo(
            String photoId,
            String scrambleId,
            String filename,
            String url,
            String queryParams,
            int sortOrder
    ) {
    }

    public record PhotoDetail(
            String id,
            String title,
            String albumId,
            int sortOrder,
            String author,
            List<String> tags,
            List<ImageInfo> images,
            boolean isSingleEpisode
    ) {
    }

    public record AlbumDetail(
            String id,
            String title,
            String description,
            String addTime,
            int pageCount,
            String likes,
            String views,
            int commentCount,
            String image,
            CategoryMeta category,
            CategoryMeta subCategory,
            List<String> authors,
            List<String> works,
            List<String> actors,
            List<String> tags,
            List<AlbumMeta> relatedAlbums,
            List<PhotoMeta> photoMetas,
            String seriesId,
            boolean isSingleEpisode,
            boolean isFavorite,
            boolean isLiked,
            String price,
            String purchased
    ) {
    }

    public record AlbumMeta(
            String id,
            String title,
            String coverUrl,
            List<String> authors,
            List<String> tags,
            String description,
            String image,
            CategoryMeta category,
            CategoryMeta subCategory
    ) {
    }

    public record CommentList(int total, List<CommentItem> list) {
    }

    public record CommentItem(
            String commentId,
            String userId,
            String username,
            String nickname,
            String content,
            String postDate,
            String photo,
            String expinfo,
            String aid,
            String name,
            int likes,
            int voteUp,
            int voteDown,
            List<CommentItem> replys
    ) {
    }

    public record AllSettings(
            int readerPreloadPages,
            int preloadConcurrency,
            int downloadConcurrency,
            boolean downloadPublic,
            int cacheCapacityMb,
            int cacheRequestedMb,
            int cacheEffectiveMb,
            boolean cacheTemporaryClamp,
            String cacheLimitReason,
            boolean ocrEnabled,
            String readerDisplayMode,
            String readerScreenOrientation,
            double readerBrightness,
            boolean readerKeepScreenOn,
            boolean readerVolumeNavigation,
            boolean readerAutoShowToolbarAtEnd
    ) {
    }

    public record NumberRequest(Integer n) {
    }

    public record StringRequest(String mode) {
    }

    public record BooleanRequest(Boolean enabled) {
    }

    public record RecordBrowseRequest(
            String albumId,
            String albumTitle,
            String coverUrl,
            String authors,
            String chapterId,
            String chapterTitle
    ) {
    }

    public record BrowseHistoryRange(
            String key,
            Long startInclusive,
            Long endExclusive
    ) {
    }

    public record BrowseHistoryOverviewRequest(List<BrowseHistoryRange> ranges) {
    }

    public record BrowseHistoryQuery(
            Integer limit,
            Integer offset,
            Long startInclusive,
            Long endExclusive
    ) {
    }

    public record BrowseHistoryItem(
            long id,
            String albumId,
            String albumTitle,
            String coverUrl,
            String authors,
            String chapterId,
            String chapterTitle,
            long timestamp
    ) {
    }

    public record HistoryPage(List<BrowseHistoryItem> items, long totalCount) {
    }

    public record BrowseHistoryOverview(long totalCount, Map<String, Long> groupCounts) {
    }

    public record Success(boolean success) {
    }

    public record ErrorResponse(String code, String message) {
    }

    /** 图片资源响应不走 JSON，单独携带解密后的字节和 Content-Type。 */
    public record ImageResource(byte[] bytes, String mediaType) {
    }
}
