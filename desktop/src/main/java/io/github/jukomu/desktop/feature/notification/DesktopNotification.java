package io.github.jukomu.desktop.feature.notification;

/** 宿主可展示的一条系统通知。 */
public record DesktopNotification(
        String key,
        String title,
        String message,
        String route
) {
}
