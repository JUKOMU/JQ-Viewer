package io.github.jukomu.desktop.feature.notification;

/** 将通知交给桌面宿主展示，并绑定一次性点击动作。 */
@FunctionalInterface
public interface DesktopNotificationSink {
    void show(DesktopNotification notification, Runnable onClick);
}
