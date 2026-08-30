package io.github.jukomu.picacomic;

/** User data safe to expose to the WebView; it contains no token or password. */
public final class PicacomicUser {
    public final String id;
    public final String username;
    public final String email;
    public final String avatar;

    public PicacomicUser(String id, String username, String email, String avatar) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.avatar = avatar;
    }
}
