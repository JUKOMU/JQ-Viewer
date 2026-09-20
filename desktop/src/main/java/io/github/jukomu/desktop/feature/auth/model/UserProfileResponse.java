package io.github.jukomu.desktop.feature.auth.model;

/** 返回用户公开资料。 */
public record UserProfileResponse(
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
