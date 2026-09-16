package io.github.jukomu.desktop.feature.dialog;

import com.formdev.flatlaf.util.SystemFileChooser;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopFileDialogHostTest {
    @Test
    void opensChooserWithActiveOwnerOnEventDispatchThread() throws Exception {
        TestDialogOwner owner = new TestDialogOwner();
        DesktopFileDialogHost host = new DesktopFileDialogHost(() -> owner);
        CapturingChooser chooser = new CapturingChooser(SystemFileChooser.APPROVE_OPTION);

        int result = host.showOpenDialog(chooser);

        assertEquals(SystemFileChooser.APPROVE_OPTION, result);
        assertTrue(owner.activated.get());
        assertTrue(owner.closed.get());
        assertTrue(chooser.openedOnEventDispatchThread.get());
        assertSame(owner.component(), chooser.parent.get());
    }

    @Test
    void closesOwnerWhenChooserFails() {
        TestDialogOwner owner = new TestDialogOwner();
        DesktopFileDialogHost host = new DesktopFileDialogHost(() -> owner);
        CapturingChooser chooser = new CapturingChooser(SystemFileChooser.CANCEL_OPTION);
        chooser.failure = new IllegalStateException("boom");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> host.showOpenDialog(chooser));

        assertEquals("boom", failure.getMessage());
        assertTrue(owner.activated.get());
        assertTrue(owner.closed.get());
    }

    private static final class TestDialogOwner implements DesktopFileDialogHost.DialogOwner {
        private final Component component = new Component() {
        };
        private final AtomicBoolean activated = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

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
            closed.set(true);
        }
    }

    private static final class CapturingChooser extends SystemFileChooser {
        private final int result;
        private final AtomicReference<Component> parent = new AtomicReference<>();
        private final AtomicBoolean openedOnEventDispatchThread = new AtomicBoolean();
        private RuntimeException failure;

        private CapturingChooser(int result) {
            this.result = result;
        }

        @Override
        public int showOpenDialog(Component parent) {
            this.parent.set(parent);
            openedOnEventDispatchThread.set(SwingUtilities.isEventDispatchThread());
            if (failure != null) throw failure;
            return result;
        }
    }
}
