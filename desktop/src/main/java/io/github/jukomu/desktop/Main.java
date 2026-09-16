package io.github.jukomu.desktop;

import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.update.DesktopUpdateInstaller;
import io.github.jukomu.desktop.host.Host;

/** 应用的唯一入口。 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Host host = Host.createDefault();
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
