package io.github.jukomu.desktop.host;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.awt.image.MultiResolutionImage;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

    @Test
    void providesNativeResolutionVariantsForTrayScaling() {
        MultiResolutionImage image = assertInstanceOf(MultiResolutionImage.class, Tray.loadTrayImage());

        for (int size : new int[] {16, 20, 24, 32, 64}) {
            Image variant = image.getResolutionVariant(size, size);
            assertEquals(size, variant.getWidth(null));
            assertEquals(size, variant.getHeight(null));
        }
    }

    @Test
    void usesTwentyPixelVariantAtOneHundredTwentyFivePercentScale() {
        Image image = Tray.loadTrayImage();
        BufferedImage actual = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D scaledGraphics = actual.createGraphics();
        scaledGraphics.scale(1.25, 1.25);
        scaledGraphics.drawImage(image, 0, 0, 16, 16, null);
        scaledGraphics.dispose();

        BufferedImage expected = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D nativeGraphics = expected.createGraphics();
        nativeGraphics.drawImage(((MultiResolutionImage) image).getResolutionVariant(20, 20), 0, 0, null);
        nativeGraphics.dispose();

        assertArrayEquals(
            expected.getRGB(0, 0, 20, 20, null, 0, 20),
            actual.getRGB(0, 0, 20, 20, null, 0, 20)
        );
    }
}
