package io.github.jukomu.desktop.service;

import io.github.jukomu.desktop.dto.DesktopDtos;

/** Desktop 在线目录能力；HTTP handler 只依赖该业务接口。 */
public interface DesktopCatalogService {
    DesktopDtos.SearchResult search(DesktopDtos.SearchQuery query);

    DesktopDtos.SearchResult categories(DesktopDtos.SearchQuery query);

    DesktopDtos.AlbumDetail getAlbum(String id);

    DesktopDtos.PhotoDetail getPhoto(String id);

    DesktopDtos.CommentList getComments(String albumId, int page);

    DesktopDtos.ImageResource getImage(String photoId, int sortOrder, String type);
}
