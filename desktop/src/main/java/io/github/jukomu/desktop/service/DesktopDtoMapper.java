package io.github.jukomu.desktop.service;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.jmcomic.api.model.JmAlbum;
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta;
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta;
import io.github.jukomu.jmcomic.api.model.JmComment;
import io.github.jukomu.jmcomic.api.model.JmCommentList;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.api.model.JmPhotoMeta;
import io.github.jukomu.jmcomic.api.model.JmSearchPage;
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import io.github.jukomu.jmcomic.api.model.JmUserProfile;

import java.util.List;
import java.util.function.Function;

/** 将 JMComic 模型转换为 Desktop HTTP 的稳定 JSON DTO。 */
public final class DesktopDtoMapper {
    private DesktopDtoMapper() {
    }

    public static DesktopDtos.SearchResult searchResult(
            JmSearchPage page,
            Function<String, String> coverUrl
    ) {
        List<DesktopDtos.SearchResultItem> content = safeList(page.getContent()).stream()
                .map(item -> searchResultItem(item, coverUrl))
                .toList();
        return new DesktopDtos.SearchResult(
                page.getCurrentPage(),
                page.getTotalItems(),
                page.getTotalPages(),
                content
        );
    }

    public static DesktopDtos.AlbumDetail album(
            JmAlbum album,
            Function<String, String> coverUrl
    ) {
        List<DesktopDtos.AlbumMeta> related = safeList(album.getRelatedAlbums()).stream()
                .map(item -> albumMeta(item, coverUrl))
                .toList();
        List<DesktopDtos.PhotoMeta> photos = safeList(album.getPhotoMetas()).stream()
                .map(DesktopDtoMapper::photoMeta)
                .toList();
        return new DesktopDtos.AlbumDetail(
                text(album.getId()),
                text(album.getTitle()),
                text(album.getDescription()),
                text(album.getAddTime()),
                album.getPageCount(),
                text(album.getLikes()),
                text(album.getViews()),
                album.getCommentCount(),
                coverUrl.apply(text(album.getId())),
                category(album.getCategory()),
                category(album.getSubCategory()),
                strings(album.getAuthors()),
                strings(album.getWorks()),
                strings(album.getActors()),
                strings(album.getTags()),
                related,
                photos,
                text(album.getSeriesId()),
                album.isSingleAlbum(),
                album.isFavorite(),
                album.isLiked(),
                text(album.getPrice()),
                text(album.getPurchased())
        );
    }

    public static DesktopDtos.PhotoDetail photo(JmPhoto photo) {
        return new DesktopDtos.PhotoDetail(
                text(photo.getId()),
                text(photo.getTitle()),
                text(photo.getAlbumId()),
                photo.getSortOrder(),
                text(photo.getAuthor()),
                strings(photo.getTags()),
                safeList(photo.getImages()).stream().map(DesktopDtoMapper::image).toList(),
                photo.isSingleAlbum()
        );
    }

    public static DesktopDtos.CommentList comments(JmCommentList comments) {
        return new DesktopDtos.CommentList(
                comments.getTotal(),
                safeList(comments.getList()).stream().map(DesktopDtoMapper::comment).toList()
        );
    }

    public static DesktopDtos.UserInfo userInfo(JmUserInfo userInfo) {
        return new DesktopDtos.UserInfo(
                text(userInfo.getUid()),
                text(userInfo.getUsername()),
                text(userInfo.getEmail()),
                userInfo.isEmailVerified(),
                text(userInfo.getPhotoUrl()),
                text(userInfo.getFirstName()),
                text(userInfo.getGender()),
                text(userInfo.getMessage()),
                userInfo.getLevel(),
                text(userInfo.getLevelName()),
                userInfo.getNextLevelExp(),
                userInfo.getCurrentExp(),
                userInfo.getExpPercent(),
                userInfo.getCoin(),
                userInfo.getAlbumFavorites(),
                userInfo.getMaxAlbumFavorites()
        );
    }

    public static DesktopDtos.UserProfile userProfile(JmUserProfile profile) {
        return new DesktopDtos.UserProfile(
                text(profile.username()),
                text(profile.email()),
                text(profile.nickname()),
                text(profile.birthday()),
                text(profile.city()),
                text(profile.country()),
                text(profile.occupation()),
                text(profile.aboutMe()),
                text(profile.website())
        );
    }

    private static DesktopDtos.SearchResultItem searchResultItem(
            JmAlbumMeta item,
            Function<String, String> coverUrl
    ) {
        return new DesktopDtos.SearchResultItem(
                text(item.getId()),
                text(item.getTitle()),
                coverUrl.apply(text(item.getId())),
                strings(item.getAuthors()),
                strings(item.getTags())
        );
    }

    private static DesktopDtos.AlbumMeta albumMeta(
            JmAlbumMeta item,
            Function<String, String> coverUrl
    ) {
        return new DesktopDtos.AlbumMeta(
                text(item.getId()),
                text(item.getTitle()),
                coverUrl.apply(text(item.getId())),
                strings(item.getAuthors()),
                strings(item.getTags()),
                text(item.getDescription()),
                text(item.getImage()),
                category(item.getCategory()),
                category(item.getSubCategory())
        );
    }

    private static DesktopDtos.PhotoMeta photoMeta(JmPhotoMeta meta) {
        return new DesktopDtos.PhotoMeta(text(meta.getId()), text(meta.getTitle()), meta.getSortOrder());
    }

    private static DesktopDtos.ImageInfo image(JmImage image) {
        return new DesktopDtos.ImageInfo(
                text(image.getPhotoId()),
                text(image.getScrambleId()),
                text(image.getFilename()),
                text(image.getUrl()),
                text(image.getQueryParams()),
                image.getSortOrder()
        );
    }

    private static DesktopDtos.CommentItem comment(JmComment comment) {
        return new DesktopDtos.CommentItem(
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
                safeList(comment.getReplys()).stream().map(DesktopDtoMapper::comment).toList()
        );
    }

    private static DesktopDtos.CategoryMeta category(JmCategoryMeta meta) {
        if (meta == null) {
            return null;
        }
        return new DesktopDtos.CategoryMeta(text(meta.getId()), text(meta.getTitle()));
    }

    private static List<String> strings(List<String> values) {
        return safeList(values).stream().map(DesktopDtoMapper::text).toList();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
