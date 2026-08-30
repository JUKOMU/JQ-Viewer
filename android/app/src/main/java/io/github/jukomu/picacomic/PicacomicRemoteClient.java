package io.github.jukomu.picacomic;

/** Provider-specific seam.  It is intentionally narrower than JmcomicPlugin. */
public interface PicacomicRemoteClient {

    PicacomicRemoteModels.User login(String usernameOrEmail, String password,
                                     PicacomicCancellationToken cancellation)
        throws Exception;

    PicacomicRemoteModels.Page search(String query, String order, int page,
                                      PicacomicCancellationToken cancellation)
        throws Exception;

    PicacomicRemoteModels.Page categories(String category, String order, int page,
                                          PicacomicCancellationToken cancellation)
        throws Exception;

    PicacomicRemoteModels.Album getAlbum(String albumId,
                                         PicacomicCancellationToken cancellation)
        throws Exception;

    PicacomicRemoteModels.Photo getPhoto(String albumId, int order,
                                         PicacomicCancellationToken cancellation)
        throws Exception;

    byte[] fetchImageBytes(PicacomicImageSource source,
                           PicacomicCancellationToken cancellation)
        throws Exception;

    /** Close only resources owned by this client; it must be idempotent. */
    void close();
}
