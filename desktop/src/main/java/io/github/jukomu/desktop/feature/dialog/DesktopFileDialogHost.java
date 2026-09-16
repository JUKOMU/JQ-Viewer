package io.github.jukomu.desktop.feature.dialog;

import com.formdev.flatlaf.util.SystemFileChooser;

import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Window;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
            return new DialogSession(chooser).show();
        }

        DialogSession session = new DialogSession(chooser);
        FutureTask<Integer> task = new FutureTask<>(session::show);
        SwingUtilities.invokeLater(task);
        try {
            return task.get();
        } catch (InterruptedException exception) {
            task.cancel(false);
            session.cancel();
            throw exception;
        } catch (ExecutionException exception) {
            rethrow(exception.getCause());
            return SystemFileChooser.CANCEL_OPTION;
        }
    }

    private final class DialogSession {
        private final SystemFileChooser chooser;
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private final AtomicReference<DialogOwner> activeOwner = new AtomicReference<>();

        private DialogSession(SystemFileChooser chooser) {
            this.chooser = chooser;
        }

        private int show() {
            if (cancellationRequested.get()) return SystemFileChooser.CANCEL_OPTION;

            DialogOwner owner = ownerFactory.create();
            activeOwner.set(owner);
            try {
                if (cancellationRequested.get()) return SystemFileChooser.CANCEL_OPTION;
                owner.activate();
                if (cancellationRequested.get()) return SystemFileChooser.CANCEL_OPTION;
                return chooser.showOpenDialog(owner.component());
            } finally {
                closeOwner();
            }
        }

        private void cancel() {
            cancellationRequested.set(true);
            // SystemFileChooser 没有取消 API；释放 owner 会关闭其拥有的原生或 Swing 对话框。
            SwingUtilities.invokeLater(this::closeOwner);
        }

        private void closeOwner() {
            DialogOwner owner = activeOwner.getAndSet(null);
            if (owner != null) owner.close();
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
