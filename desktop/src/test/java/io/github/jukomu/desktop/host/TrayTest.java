package io.github.jukomu.desktop.host;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrayTest {
    @Test
    void usesChineseLabelsForAnyChineseDisplayLocaleWithAvailableFont() {
        assertEquals(
                new Tray.MenuLabels("打开首页", "退出"),
                Tray.selectMenuLabels(Locale.SIMPLIFIED_CHINESE, true)
        );
        assertEquals(
                new Tray.MenuLabels("打开首页", "退出"),
                Tray.selectMenuLabels(Locale.TRADITIONAL_CHINESE, true)
        );
    }

    @Test
    void fallsBackToEnglishOutsideChineseLocalesOrWithoutChineseFont() {
        Tray.MenuLabels english = new Tray.MenuLabels("Open Home", "Exit");

        assertEquals(english, Tray.selectMenuLabels(Locale.ENGLISH, true));
        assertEquals(english, Tray.selectMenuLabels(Locale.SIMPLIFIED_CHINESE, false));
    }
}
