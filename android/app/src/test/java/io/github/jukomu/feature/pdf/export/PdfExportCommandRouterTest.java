package io.github.jukomu.feature.pdf.export;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class PdfExportCommandRouterTest {

    @Test
    public void routesCancelToCurrentPort() {
        PdfExportCommandRouter router = PdfExportCommandRouter.getInstance();
        AtomicInteger calls = new AtomicInteger();
        PdfExportCommandPort port = exportId -> {
            if ("export-1".equals(exportId)) calls.incrementAndGet();
        };
        router.attach(port);
        try {
            router.cancelExport("export-1");
            assertEquals(1, calls.get());
        } finally {
            router.detach(port);
        }
    }

    @Test
    public void staleDetachDoesNotClearNewPort() {
        PdfExportCommandRouter router = PdfExportCommandRouter.getInstance();
        AtomicInteger calls = new AtomicInteger();
        PdfExportCommandPort oldPort = exportId -> {
        };
        PdfExportCommandPort newPort = exportId -> calls.incrementAndGet();
        router.attach(oldPort);
        router.attach(newPort);
        router.detach(oldPort);
        try {
            router.cancelExport("export-2");
            assertEquals(1, calls.get());
        } finally {
            router.detach(newPort);
        }
    }

    @Test
    public void unboundOrInvalidCancelIsIgnored() {
        PdfExportCommandRouter router = PdfExportCommandRouter.getInstance();
        router.detach(null);
        router.cancelExport(null);
        router.cancelExport("");
    }
}
