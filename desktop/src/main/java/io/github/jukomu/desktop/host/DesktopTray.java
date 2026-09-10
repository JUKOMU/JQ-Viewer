package io.github.jukomu.desktop.host;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.Optional;

/** Optional AWT tray integration. Browser startup does not depend on this class. */
public final class DesktopTray implements AutoCloseable {
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
            PopupMenu menu = new PopupMenu();
            MenuItem openHomeItem = new MenuItem("打开首页");
            MenuItem exitItem = new MenuItem("退出");
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

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // A tray callback must not terminate the AWT event thread.
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
