package io.github.jukomu.desktop.host;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.awt.HeadlessException;
import java.net.URI;
import java.util.Objects;
import java.util.function.Consumer;

/** Opens a URL with the operating system browser without depending on tray support. */
public final class BrowserLauncher {
    private final Consumer<URI> opener;

    public BrowserLauncher() {
        this(BrowserLauncher::openWithDesktop);
    }

    public BrowserLauncher(Consumer<URI> opener) {
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    public boolean open(URI uri) {
        Objects.requireNonNull(uri, "uri");
        try {
            opener.accept(uri);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static void openWithDesktop(URI uri) {
        if (GraphicsEnvironment.isHeadless() || !Desktop.isDesktopSupported()) {
            throw new UnsupportedOperationException("System browser is unavailable");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            throw new UnsupportedOperationException("System browser is unavailable");
        }
        try {
            desktop.browse(uri);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to open the system browser", exception);
        }
    }
}
