package io.github.jukomu.jqviewer.desktop;

import io.github.jukomu.jqviewer.desktop.config.DesktopServerConfig;
import io.github.jukomu.jqviewer.desktop.http.DesktopServer;

public final class DesktopServerMain {
    private DesktopServerMain() {
    }

    public static void main(String[] args) throws Exception {
        DesktopServerConfig config = DesktopServerConfig.from(args, System.getenv());
        DesktopServer server = new DesktopServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "desktop-server-shutdown"));
        server.start();

        System.out.println("JQViewer desktop server listening on http://127.0.0.1:" + server.port());
        System.out.println("JQViewer desktop token: " + config.token());
        System.out.println("JQViewer desktop data dir: " + config.dataDir());

        Thread.currentThread().join();
    }
}
