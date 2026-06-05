package io.github.jukomu.jqviewer.desktop.host;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;

final class DesktopTrayController implements AutoCloseable {
    private final SystemTray tray;
    private final TrayIcon icon;

    private DesktopTrayController(SystemTray tray, TrayIcon icon) {
        this.tray = tray;
        this.icon = icon;
    }

    static DesktopTrayController install(Runnable openPage, Runnable exitApp) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return null;
        }

        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Open JQViewer");
        open.addActionListener(event -> safeRun(openPage));
        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(event -> safeRun(exitApp));
        menu.add(open);
        menu.addSeparator();
        menu.add(exit);

        TrayIcon icon = new TrayIcon(iconImage(), "JQViewer", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> safeRun(openPage));

        try {
            SystemTray tray = SystemTray.getSystemTray();
            tray.add(icon);
            return new DesktopTrayController(tray, icon);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void close() {
        tray.remove(icon);
    }

    private static BufferedImage iconImage() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(255, 250, 246));
            graphics.fillRoundRect(1, 1, 14, 14, 4, 4);
            graphics.setColor(new Color(184, 82, 42));
            graphics.fillRect(4, 4, 8, 2);
            graphics.fillRect(4, 7, 8, 2);
            graphics.fillRect(4, 10, 6, 2);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static void safeRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ignored) {
            // Tray callbacks must not tear down the AWT event thread.
        }
    }
}
