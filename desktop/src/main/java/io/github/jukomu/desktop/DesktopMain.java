package io.github.jukomu.desktop;

import io.github.jukomu.desktop.host.DesktopHost;

/** The only Desktop application entry point. */
public final class DesktopMain {
    private DesktopMain() {
    }

    public static void main(String[] args) {
        DesktopHost host = DesktopHost.createDefault();
        Thread shutdownHook = new Thread(host::close, "jq-viewer-desktop-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        try {
            if (!host.start()) {
                return;
            }
            System.out.println("JQ Viewer Desktop started at " + host.homeUrl());
        } catch (Exception exception) {
            host.close();
            throw new IllegalStateException("Unable to start JQ Viewer Desktop", exception);
        }
    }
}
