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

    static DesktopTrayController install(Runnable openHome, Runnable openDownload, Runnable openDataDir,
                                         Runnable openLogDir, Runnable restartService, Runnable exitApp) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return null;
        }

        PopupMenu menu = new PopupMenu();
        MenuItem openHomeItem = menuItem("Open Home", openHome);
        MenuItem openDownloadItem = menuItem("Open Downloads", openDownload);
        MenuItem openDataDirItem = menuItem("Open Data Directory", openDataDir);
        MenuItem openLogDirItem = menuItem("Open Log Directory", openLogDir);
        MenuItem restartItem = menuItem("Restart Service", restartService);
        MenuItem exit = menuItem("Exit", exitApp);
        menu.add(openHomeItem);
        menu.add(openDownloadItem);
        menu.addSeparator();
        menu.add(openDataDirItem);
        menu.add(openLogDirItem);
        menu.addSeparator();
        menu.add(restartItem);
        menu.addSeparator();
        menu.add(exit);

        TrayIcon icon = new TrayIcon(iconImage(), "JQViewer", menu);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> safeRun(openHome));

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

    private static MenuItem menuItem(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(event -> safeRun(action));
        return item;
    }

    private static void safeRun(Runnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException ignored) {
            // Tray callbacks must not tear down the AWT event thread.
        }
    }
}
