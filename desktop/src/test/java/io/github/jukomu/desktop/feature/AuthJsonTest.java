package io.github.jukomu.desktop.feature;

import io.github.jukomu.desktop.feature.auth.AuthJson;
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import io.github.jukomu.jmcomic.api.model.JmUserProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AuthJsonTest {
    @Test
    void mapsUserAndProfileToTheExistingFrontendFieldNames() {
        var user = AuthJson.userInfo(new JmUserInfo(
                "1", "user", null, true, "https://avatar", "name", "x", "message",
                10, 20, 3, "Lv.3", 100, 40, 0.4, 500
        ));

        assertEquals("", user.path("email").asText());
        assertEquals("https://avatar", user.path("avatarUrl").asText());
        assertEquals(40, user.path("currentExp").asLong());

        var profile = AuthJson.profile(new JmUserProfile(
                "user", "mail@example.com", "nick", "last", "first", "2000-01-01",
                "", "", "https://site", "", "city", "country", "job", "", "",
                "about", "", "", "", "", "", ""
        ));
        assertEquals("nick", profile.path("nickname").asText());
        assertEquals("about", profile.path("aboutMe").asText());
        assertFalse(profile.has("lastName"));
    }
}
