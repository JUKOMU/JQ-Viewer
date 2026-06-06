package io.github.jukomu.jqviewer.desktop;

import io.github.jukomu.jqviewer.desktop.config.DesktopServerConfig;
import io.github.jukomu.jqviewer.desktop.http.DesktopServer;

import java.nio.file.Files;
import java.util.Arrays;

public final class DesktopServerMain {
    private DesktopServerMain() {
    }

    public static void main(String[] args) throws Exception {
        DesktopServerConfig config = DesktopServerConfig.from(args, System.getenv());
        Files.createDirectories(config.logDir());
        System.setProperty("org.slf4j.simpleLogger.logFile", config.logDir().resolve("desktop-server.log").toString());
        DesktopServer server = new DesktopServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "desktop-server-shutdown"));
        server.start();

        boolean printToken = shouldPrintToken(args);
        System.out.println("JQViewer desktop server listening on http://127.0.0.1:" + server.port());
        System.out.println("JQViewer desktop token: " + (printToken ? config.token() : "<redacted>"));
        if (!printToken) {
            System.out.println("Set JQ_DESKTOP_PRINT_TOKEN=true or pass --print-token to print the token for direct server debugging.");
        }
        System.out.println("JQViewer desktop data dir: " + config.dataDir());
        System.out.println("JQViewer desktop cache dir: " + config.cacheDir());
        System.out.println("JQViewer desktop download dir: " + config.downloadDir());
        System.out.println("JQViewer desktop PDF import root: " + config.pdfRootDir());
        System.out.println("JQViewer desktop PDF export dir: " + config.pdfExportDir());
        System.out.println("JQViewer desktop log dir: " + config.logDir());
        if (config.staticDir() != null) {
            System.out.println("JQViewer desktop static dir: " + config.staticDir());
        }

        Thread.currentThread().join();
    }

    private static boolean shouldPrintToken(String[] args) {
        String env = System.getenv("JQ_DESKTOP_PRINT_TOKEN");
        return "true".equalsIgnoreCase(env) || Arrays.asList(args).contains("--print-token");
    }
}
