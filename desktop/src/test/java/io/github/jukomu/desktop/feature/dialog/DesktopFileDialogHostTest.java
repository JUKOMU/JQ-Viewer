package io.github.jukomu.desktop.feature.dialog;

import com.formdev.flatlaf.util.SystemFileChooser;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopFileDialogHostTest {
    @Test
    void opensChooserWithActiveOwnerOnEventDispatchThread() throws Exception {
        TestDialogOwner owner = new TestDialogOwner();
        DesktopFileDialogHost host = new DesktopFileDialogHost(() -> owner);
        CapturingChooser chooser = new CapturingChooser(
                SystemFileChooser.APPROVE_OPTION,
                owner);

        int result = host.showOpenDialog(chooser);

        assertEquals(SystemFileChooser.APPROVE_OPTION, result);
        assertTrue(owner.activated.get());
        assertTrue(owner.closed.get());
        assertTrue(chooser.openedOnEventDispatchThread.get());
        assertTrue(chooser.ownerActiveDuringOpen.get());
        assertSame(owner.component(), chooser.parent.get());
    }

    @Test
    void closesOwnerWhenChooserFails() {
        TestDialogOwner owner = new TestDialogOwner();
        DesktopFileDialogHost host = new DesktopFileDialogHost(() -> owner);
        CapturingChooser chooser = new CapturingChooser(
                SystemFileChooser.CANCEL_OPTION,
                owner);
        chooser.failure = new IllegalStateException("boom");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> host.showOpenDialog(chooser));

        assertEquals("boom", failure.getMessage());
        assertTrue(owner.activated.get());
        assertTrue(owner.closed.get());
    }

    @Test
    void closesActiveDialogWhenWaitingThreadIsInterrupted() throws Exception {
        AtomicReference<BlockingChooser> chooserReference = new AtomicReference<>();
        TestDialogOwner owner = new TestDialogOwner(
                () -> chooserReference.get().closeDialog());
        BlockingChooser chooser = new BlockingChooser(
                () -> owner.activated.get() && !owner.closed.get());
        chooserReference.set(chooser);
        DesktopFileDialogHost host = new DesktopFileDialogHost(() -> owner);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                host.showOpenDialog(chooser);
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });

        worker.start();
        assertTrue(chooser.opened.await(5, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(TimeUnit.SECONDS.toMillis(5));
        assertTrue(chooser.closed.await(5, TimeUnit.SECONDS));
        SwingUtilities.invokeAndWait(() -> {
        });

        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);
        assertTrue(owner.closed.get());
        assertTrue(chooser.openedOnEventDispatchThread.get());
        assertTrue(chooser.ownerActiveDuringOpen.get());
    }

    private static final class TestDialogOwner implements DesktopFileDialogHost.DialogOwner {
        private final Component component = new Component() {
        };
        private final Runnable closeAction;
        private final AtomicBoolean activated = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private TestDialogOwner() {
            this(() -> {
            });
        }

        private TestDialogOwner(Runnable closeAction) {
            this.closeAction = closeAction;
        }

        @Override
        public Component component() {
            return component;
        }

        @Override
        public void activate() {
            activated.set(true);
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) closeAction.run();
        }
    }

    private static final class CapturingChooser extends SystemFileChooser {
        private final int result;
        private final AtomicReference<Component> parent = new AtomicReference<>();
        private final AtomicBoolean openedOnEventDispatchThread = new AtomicBoolean();
        private final AtomicBoolean ownerActiveDuringOpen = new AtomicBoolean();
        private final TestDialogOwner owner;
        private RuntimeException failure;

        private CapturingChooser(int result, TestDialogOwner owner) {
            this.result = result;
            this.owner = owner;
        }

        @Override
        public int showOpenDialog(Component parent) {
            this.parent.set(parent);
            openedOnEventDispatchThread.set(SwingUtilities.isEventDispatchThread());
            ownerActiveDuringOpen.set(owner.activated.get() && !owner.closed.get());
            if (failure != null) throw failure;
            return result;
        }
    }

    private static final class BlockingChooser extends SystemFileChooser {
        private final CountDownLatch opened = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean openedOnEventDispatchThread = new AtomicBoolean();
        private final AtomicBoolean ownerActiveDuringOpen = new AtomicBoolean();
        private final AtomicReference<SecondaryLoop> loop = new AtomicReference<>();
        private final BooleanSupplier ownerActive;

        private BlockingChooser(BooleanSupplier ownerActive) {
            this.ownerActive = ownerActive;
        }

        @Override
        public int showOpenDialog(Component parent) {
            openedOnEventDispatchThread.set(SwingUtilities.isEventDispatchThread());
            ownerActiveDuringOpen.set(ownerActive.getAsBoolean());
            SecondaryLoop secondaryLoop = Toolkit.getDefaultToolkit()
                    .getSystemEventQueue()
                    .createSecondaryLoop();
            loop.set(secondaryLoop);
            opened.countDown();
            secondaryLoop.enter();
            return SystemFileChooser.CANCEL_OPTION;
        }

        private void closeDialog() {
            SecondaryLoop secondaryLoop = loop.get();
            if (secondaryLoop != null) secondaryLoop.exit();
            closed.countDown();
        }
    }
}
