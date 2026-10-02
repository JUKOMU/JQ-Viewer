package io.github.jukomu.desktop;

import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.update.DesktopUpdateInstaller;
import io.github.jukomu.desktop.host.Host;
import io.github.jukomu.desktop.logging.ApplicationLogging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 应用的唯一入口。
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Paths paths = Paths.current();
        try {
            ApplicationLogging.initialize(paths);
        } catch (Exception error) {
            throw new IllegalStateException("无法初始化 JQ Viewer 日志", error);
        }
        Logger logger = LoggerFactory.getLogger(Main.class);
        logger.info("应用启动");
        Host host = Host.createDefault(paths);
        Thread shutdownHook = new Thread(host::close, "jq-viewer-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            if (!host.start()) {
                return;
            }
            DesktopUpdateInstaller.confirmStarted(Paths.current());
            System.out.println("JQ Viewer started at " + host.homeUrl());
        } catch (Exception exception) {
            host.close();
            throw new IllegalStateException("无法启动 JQ Viewer", exception);
        }
    }
}
