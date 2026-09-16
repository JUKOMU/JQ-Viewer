package io.github.jukomu.desktop.feature.dialog;

import com.formdev.flatlaf.util.SystemFileChooser;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** 为系统文件选择器提供可激活、可释放的 Desktop owner 窗口。 */
public final class DesktopFileDialogHost {
    private static final DesktopFileDialogHost SHARED =
            new DesktopFileDialogHost(SwingDialogOwner::new);

    private final DialogOwnerFactory ownerFactory;

    DesktopFileDialogHost(DialogOwnerFactory ownerFactory) {
        this.ownerFactory = Objects.requireNonNull(ownerFactory, "ownerFactory");
    }

    public static DesktopFileDialogHost shared() {
        return SHARED;
    }

    /** 在 Swing EDT 上打开选择器，并在返回或失败后释放临时 owner。 */
    public int showOpenDialog(SystemFileChooser chooser) throws InterruptedException {
        Objects.requireNonNull(chooser, "chooser");
        if (SwingUtilities.isEventDispatchThread()) {
            return showOnEventDispatchThread(chooser);
        }

        AtomicInteger result = new AtomicInteger(SystemFileChooser.CANCEL_OPTION);
        try {
            SwingUtilities.invokeAndWait(() -> result.set(showOnEventDispatchThread(chooser)));
        } catch (InvocationTargetException exception) {
            rethrow(exception.getCause());
        }
        return result.get();
    }

    private int showOnEventDispatchThread(SystemFileChooser chooser) {
        try (DialogOwner owner = ownerFactory.create()) {
            owner.activate();
            return chooser.showOpenDialog(owner.component());
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) throw runtimeException;
        if (failure instanceof Error error) throw error;
        throw new IllegalStateException("无法打开系统文件选择器", failure);
    }

    interface DialogOwnerFactory {
        DialogOwner create();
    }

    interface DialogOwner extends AutoCloseable {
        Component component();

        void activate();

        @Override
        void close();
    }

    private static final class SwingDialogOwner implements DialogOwner {
        private final JDialog window;

        private SwingDialogOwner() {
            window = new JDialog((Frame) null);
            window.setName("JQ Viewer file dialog owner");
            window.setUndecorated(true);
            window.setType(Window.Type.UTILITY);
            window.setSize(1, 1);
            window.setLocationRelativeTo(null);
            window.setAutoRequestFocus(true);
            window.setAlwaysOnTop(true);
            try {
                window.setOpacity(0.0f);
            } catch (RuntimeException ignored) {
                // 不支持透明窗口时仍保留 1x1 的 utility owner。
            }
        }

        @Override
        public Component component() {
            return window;
        }

        @Override
        public void activate() {
            window.setVisible(true);
            window.toFront();
            window.requestFocus();
        }

        @Override
        public void close() {
            try {
                window.setAlwaysOnTop(false);
            } finally {
                window.setVisible(false);
                window.dispose();
            }
        }
    }
}
