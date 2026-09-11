package io.github.jukomu.desktop.host;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** 可选的 AWT 托盘集成；浏览器启动不依赖此能力。 */
public final class DesktopTray implements AutoCloseable {
    private static final String MENU_GLYPHS = "打开首页退出";
    private static final String FONT_RESOURCE = "/fonts/NotoSansCJK-Regular.ttc";
    private static final float MENU_FONT_SIZE = 13f;

    private final SystemTray systemTray;
    private final TrayIcon trayIcon;
    private boolean closed;

    private DesktopTray(SystemTray systemTray, TrayIcon trayIcon) {
        this.systemTray = systemTray;
        this.trayIcon = trayIcon;
    }

    public static Optional<DesktopTray> tryCreate(Runnable openHome, Runnable exit) {
        Objects.requireNonNull(openHome, "openHome");
        Objects.requireNonNull(exit, "exit");
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return Optional.empty();
        }

        try {
            Font menuFont = loadMenuFont();
            if (menuFont == null) {
                return Optional.empty();
            }

            PopupMenu menu = new PopupMenu();
            MenuItem openHomeItem = new MenuItem("打开首页");
            MenuItem exitItem = new MenuItem("退出");
            menu.setFont(menuFont);
            openHomeItem.setFont(menuFont);
            exitItem.setFont(menuFont);
            openHomeItem.addActionListener(event -> runSafely(openHome));
            exitItem.addActionListener(event -> runSafely(exit));
            menu.add(openHomeItem);
            menu.addSeparator();
            menu.add(exitItem);

            TrayIcon icon = new TrayIcon(createIcon(), "JQ Viewer", menu);
            icon.setImageAutoSize(true);
            icon.addActionListener(event -> runSafely(openHome));
            SystemTray tray = SystemTray.getSystemTray();
            tray.add(icon);
            return Optional.of(new DesktopTray(tray, icon));
        } catch (AWTException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static Font loadMenuFont() {
        try (InputStream stream = DesktopTray.class.getResourceAsStream(FONT_RESOURCE)) {
            if (stream == null) {
                return null;
            }

            Font[] fonts = Font.createFonts(stream);
            for (Font font : fonts) {
                String family = font.getFamily(Locale.SIMPLIFIED_CHINESE);
                if (family.toUpperCase(Locale.ROOT).contains("SC")
                        && font.canDisplayUpTo(MENU_GLYPHS) == -1) {
                    return font.deriveFont(Font.PLAIN, MENU_FONT_SIZE);
                }
            }
            for (Font font : fonts) {
                if (font.canDisplayUpTo(MENU_GLYPHS) == -1) {
                    return font.deriveFont(Font.PLAIN, MENU_FONT_SIZE);
                }
            }
        } catch (FontFormatException | IOException ignored) {
            // 字体资源不可用时跳过托盘，不影响浏览器首页启动。
        }
        return null;
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // 托盘回调失败时不能终止 AWT 事件线程。
        }
    }

    private static BufferedImage createIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(32, 95, 170));
            graphics.fillRoundRect(1, 1, 14, 14, 3, 3);
            graphics.setColor(Color.WHITE);
            graphics.fillRect(5, 4, 2, 8);
            graphics.fillRect(9, 4, 2, 8);
            graphics.fillRect(5, 10, 6, 2);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        systemTray.remove(trayIcon);
    }
}
