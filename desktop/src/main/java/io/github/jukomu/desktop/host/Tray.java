package io.github.jukomu.desktop.host;

import io.github.jukomu.desktop.feature.notification.DesktopNotification;

import javax.swing.*;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 可选的托盘集成；使用系统字体与轻量弹出菜单。
 */
public final class Tray implements AutoCloseable {
    private static final MenuLabels CHINESE_LABELS = new MenuLabels("打开首页", "退出");
    private static final MenuLabels ENGLISH_LABELS = new MenuLabels("Open Home", "Exit");
    private static final String CHINESE_MENU_GLYPHS =
        CHINESE_LABELS.openHome() + CHINESE_LABELS.exit();
    private static final float MENU_FONT_SIZE = 13f;

    private final SystemTray systemTray;
    private final TrayIcon trayIcon;
    private final JPopupMenu popupMenu;
    private final JDialog hiddenDialog;
    private final AtomicReference<Runnable> notificationClick = new AtomicReference<>();
    private boolean closed;

    private Tray(SystemTray systemTray, TrayIcon trayIcon, JPopupMenu popupMenu, JDialog hiddenDialog) {
        this.systemTray = systemTray;
        this.trayIcon = trayIcon;
        this.popupMenu = popupMenu;
        this.hiddenDialog = hiddenDialog;
    }

    public static Optional<Tray> tryCreate(Runnable openHome, Runnable exit) {
        Objects.requireNonNull(openHome, "openHome");
        Objects.requireNonNull(exit, "exit");
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            return Optional.empty();
        }

        try {
            MenuPresentation menuPresentation = resolveMenuPresentation(
                Locale.getDefault(Locale.Category.DISPLAY)
            );

            // 透明宿主窗口，用于处理失焦关闭
            JDialog hiddenDialog = new JDialog();
            hiddenDialog.setUndecorated(true);
            hiddenDialog.setType(Window.Type.UTILITY);
            hiddenDialog.setSize(1, 1);
            try {
                hiddenDialog.setOpacity(0.0f);
            } catch (Exception ignored) {
            }

            // 弹出菜单与样式
            JPopupMenu menu = new JPopupMenu();
            menu.setLightWeightPopupEnabled(false);
            menu.setBackground(Color.WHITE);
            menu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(210, 215, 220), 1),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)
            ));

            JMenuItem openHomeItem = createMenuItem(
                menuPresentation.labels().openHome(),
                menuPresentation.font(),
                openHome
            );
            JMenuItem exitItem = createMenuItem(
                menuPresentation.labels().exit(),
                menuPresentation.font(),
                exit
            );

            JSeparator separator = new JSeparator();
            separator.setForeground(new Color(230, 233, 236));
            separator.setBackground(Color.WHITE);

            menu.add(openHomeItem);
            menu.add(separator);
            menu.add(exitItem);

            // 点击其他地方失焦关闭菜单
            hiddenDialog.addWindowFocusListener(new WindowFocusListener() {
                @Override
                public void windowGainedFocus(WindowEvent e) {
                }

                @Override
                public void windowLostFocus(WindowEvent e) {
                    Window opposite = e.getOppositeWindow();
                    if (opposite != null && opposite.getClass().getName().contains("Popup")) {
                        return;
                    }
                    SwingUtilities.invokeLater(() -> {
                        menu.setVisible(false);
                        hiddenDialog.setVisible(false);
                    });
                }
            });

            menu.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                    SwingUtilities.invokeLater(() -> hiddenDialog.setVisible(false));
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                    SwingUtilities.invokeLater(() -> hiddenDialog.setVisible(false));
                }
            });

            // 托盘图标及鼠标点击事件
            TrayIcon icon = new TrayIcon(createIcon(), "JQ Viewer");
            icon.setImageAutoSize(true);
            AtomicReference<Tray> trayReference = new AtomicReference<>();
            icon.addActionListener(event -> {
                Tray current = trayReference.get();
                Runnable action = current == null ? null : current.notificationClick.getAndSet(null);
                runSafely(action == null ? openHome : action);
            });

            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseReleased(MouseEvent e) {
                    if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e)) {
                        showMenu(menu, hiddenDialog, e);
                    }
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        Tray current = trayReference.get();
                        if (current != null) current.notificationClick.set(null);
                    }
                    if (e.isPopupTrigger()) {
                        showMenu(menu, hiddenDialog, e);
                    }
                }
            });

            SystemTray tray = SystemTray.getSystemTray();
            tray.add(icon);
            Tray created = new Tray(tray, icon, menu, hiddenDialog);
            trayReference.set(created);
            return Optional.of(created);
        } catch (AWTException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private static void showMenu(JPopupMenu menu, JDialog hiddenDialog, MouseEvent e) {
        SwingUtilities.invokeLater(() -> {
            if (menu.isVisible()) {
                menu.setVisible(false);
                hiddenDialog.setVisible(false);
                return;
            }

            Point mousePos = null;
            try {
                PointerInfo pointerInfo = MouseInfo.getPointerInfo();
                if (pointerInfo != null) {
                    mousePos = pointerInfo.getLocation();
                }
            } catch (Exception ignored) {
            }
            if (mousePos == null) {
                mousePos = new Point(e.getXOnScreen(), e.getYOnScreen());
            }

            Point target = calculateMenuPosition(mousePos, menu.getPreferredSize());
            hiddenDialog.setLocation(target.x, target.y);
            hiddenDialog.setVisible(true);
            hiddenDialog.toFront();

            menu.show(hiddenDialog, 0, 0);
        });
    }

    /**
     * 智能计算位置，防止菜单被任务栏遮挡或超出屏幕边界
     */
    private static Point calculateMenuPosition(Point mousePos, Dimension menuSize) {
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        GraphicsConfiguration targetGc = null;

        for (GraphicsDevice device : ge.getScreenDevices()) {
            for (GraphicsConfiguration gc : device.getConfigurations()) {
                if (gc.getBounds().contains(mousePos)) {
                    targetGc = gc;
                    break;
                }
            }
            if (targetGc != null) {
                break;
            }
        }

        if (targetGc == null) {
            targetGc = ge.getDefaultScreenDevice().getDefaultConfiguration();
        }

        Rectangle screenBounds = targetGc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(targetGc);

        int minX = screenBounds.x + insets.left;
        int maxX = screenBounds.x + screenBounds.width - insets.right;
        int minY = screenBounds.y + insets.top;
        int maxY = screenBounds.y + screenBounds.height - insets.bottom;

        int x = mousePos.x;
        int y = mousePos.y;

        // 右侧空间不足向左弹出
        if (x + menuSize.width > maxX) {
            x = mousePos.x - menuSize.width;
        }
        // 下方被任务栏或屏幕遮挡则向上弹出
        if (y + menuSize.height > maxY) {
            y = mousePos.y - menuSize.height;
        }

        x = Math.max(minX + 2, Math.min(x, maxX - menuSize.width - 2));
        y = Math.max(minY + 2, Math.min(y, maxY - menuSize.height - 2));

        return new Point(x, y);
    }

    private static JMenuItem createMenuItem(String text, Font font, Runnable action) {
        JMenuItem item = new JMenuItem(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                ButtonModel model = getModel();
                if (model.isArmed() || model.isRollover()) {
                    g2.setColor(new Color(242, 244, 246));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                }
                super.paintComponent(g2);
                g2.dispose();
            }
        };
        item.setFont(font);
        item.setForeground(new Color(33, 37, 41));
        item.setOpaque(false);
        item.setFocusPainted(false);
        item.setBorder(BorderFactory.createEmptyBorder(7, 18, 7, 18));
        item.addActionListener(e -> runSafely(action));
        return item;
    }

    static MenuLabels selectMenuLabels(Locale locale, boolean chineseFontAvailable) {
        Objects.requireNonNull(locale, "locale");
        return "zh".equals(locale.getLanguage()) && chineseFontAvailable
            ? CHINESE_LABELS
            : ENGLISH_LABELS;
    }

    private static MenuPresentation resolveMenuPresentation(Locale locale) {
        Font defaultFont = defaultMenuFont();
        if (!"zh".equals(locale.getLanguage())) {
            return new MenuPresentation(ENGLISH_LABELS, defaultFont);
        }

        Font chineseFont = findChineseMenuFont(defaultFont, locale);
        MenuLabels labels = selectMenuLabels(locale, chineseFont != null);
        return new MenuPresentation(labels, chineseFont == null ? defaultFont : chineseFont);
    }

    private static Font defaultMenuFont() {
        Font font = UIManager.getFont("MenuItem.font");
        if (font == null) {
            font = new Font(Font.DIALOG, Font.PLAIN, Math.round(MENU_FONT_SIZE));
        }
        return font.deriveFont(Font.PLAIN, MENU_FONT_SIZE);
    }

    private static Font findChineseMenuFont(Font preferredFont, Locale locale) {
        if (preferredFont.canDisplayUpTo(CHINESE_MENU_GLYPHS) == -1) {
            return preferredFont;
        }

        try {
            String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames(locale);
            for (String family : families) {
                Font font = new Font(family, Font.PLAIN, Math.round(MENU_FONT_SIZE));
                if (font.canDisplayUpTo(CHINESE_MENU_GLYPHS) == -1) {
                    return font.deriveFont(Font.PLAIN, MENU_FONT_SIZE);
                }
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    record MenuLabels(String openHome, String exit) {
    }

    private record MenuPresentation(MenuLabels labels, Font font) {
    }

    private static void runSafely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * 使用操作系统托盘气泡展示通知；点击动作只消费一次。
     */
    public void displayNotification(DesktopNotification notification, Runnable onClick) {
        Objects.requireNonNull(notification, "notification");
        Objects.requireNonNull(onClick, "onClick");
        if (closed) return;
        notificationClick.set(onClick);
        EventQueue.invokeLater(() -> {
            if (!closed) {
                trayIcon.displayMessage(
                    notification.title(),
                    notification.message(),
                    TrayIcon.MessageType.NONE
                );
            }
        });
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
        notificationClick.set(null);
        systemTray.remove(trayIcon);
        SwingUtilities.invokeLater(() -> {
            if (popupMenu != null && popupMenu.isVisible()) {
                popupMenu.setVisible(false);
            }
            if (hiddenDialog != null) {
                hiddenDialog.dispose();
            }
        });
    }
}
