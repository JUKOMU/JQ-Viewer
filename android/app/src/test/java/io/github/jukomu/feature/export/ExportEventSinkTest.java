package io.github.jukomu.feature.export;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertSame;

public class ExportEventSinkTest {

    @Test
    public void sinkCanBeReboundAndStaleDetachIsIgnored() {
        AtomicReference<JSONObject> first = new AtomicReference<>();
        AtomicReference<JSONObject> second = new AtomicReference<>();
        ExportEventSink firstSink = first::set;
        ExportEventSink secondSink = second::set;

        RecordingEventSource source = new RecordingEventSource();
        source.attachEventSink(firstSink);
        source.attachEventSink(secondSink);
        source.detachEventSink(firstSink);

        JSONObject snapshot = new JSONObject();
        source.publish(snapshot);
        assertSame(snapshot, second.get());
    }

    private static final class RecordingEventSource {
        private ExportEventSink sink = value -> {
        };

        void attachEventSink(ExportEventSink value) {
            sink = value;
        }

        void detachEventSink(ExportEventSink expected) {
            if (sink == expected) sink = value -> {
            };
        }

        void publish(JSONObject snapshot) {
            sink.onExportProgress(snapshot);
        }
    }
}
