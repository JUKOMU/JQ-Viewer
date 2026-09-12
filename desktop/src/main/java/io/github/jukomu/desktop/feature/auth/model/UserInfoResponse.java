package io.github.jukomu.desktop.feature.auth.model;

/** 返回当前登录用户的账户和等级信息。 */
public record UserInfoResponse(
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
