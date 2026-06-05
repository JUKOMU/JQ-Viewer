package io.github.jukomu.jqviewer.desktop.host;

public final class DesktopHostMain {
    private DesktopHostMain() {
    }

    public static void main(String[] args) throws Exception {
        DesktopHostRuntime runtime = DesktopHostRuntime.start(args, System.getenv());
        if (runtime != null) {
            runtime.await();
        }
    }
}
