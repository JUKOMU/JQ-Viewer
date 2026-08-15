package io.github.jukomu.feature.pdf.export;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertSame;

public class PdfExportEventSinkTest {

    @Test
    public void sinkCanBeReboundAndStaleDetachIsIgnored() {
        AtomicReference<JSONObject> first = new AtomicReference<>();
        AtomicReference<JSONObject> second = new AtomicReference<>();
        PdfExportEventSink firstSink = first::set;
        PdfExportEventSink secondSink = second::set;

        RecordingEventSource source = new RecordingEventSource();
        source.attachEventSink(firstSink);
        source.attachEventSink(secondSink);
        source.detachEventSink(firstSink);

        JSONObject snapshot = new JSONObject();
        source.publish(snapshot);
        assertSame(snapshot, second.get());
    }

    private static final class RecordingEventSource {
        private PdfExportEventSink sink = value -> {
        };

        void attachEventSink(PdfExportEventSink value) {
            sink = value;
        }

        void detachEventSink(PdfExportEventSink expected) {
            if (sink == expected) sink = value -> {
            };
        }

        void publish(JSONObject snapshot) {
            sink.onExportProgress(snapshot);
        }
    }
}
