package io.github.jukomu.picacomic;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.util.concurrent.CountDownLatch;

/** Minimal JVM-only Capacitor call recorder for bridge contract tests. */
final class RecordingPicacomicPluginCall extends PluginCall {

    final CountDownLatch completed = new CountDownLatch(1);
    volatile JSObject resolvedData;
    volatile String rejectionMessage;
    volatile String rejectionCode;
    volatile Exception rejectionException;
    volatile JSObject rejectionData;
    volatile int completionCount;

    RecordingPicacomicPluginCall(String methodName, JSObject data) {
        super(null, "Picacomic", "test-callback", methodName,
            data == null ? new JSObject() : data);
    }

    @Override
    public void resolve(JSObject data) {
        resolvedData = data;
        completionCount++;
        completed.countDown();
    }

    @Override
    public void resolve() {
        completionCount++;
        completed.countDown();
    }

    @Override
    public void reject(String message, String code, Exception exception, JSObject data) {
        rejectionMessage = message;
        rejectionCode = code;
        rejectionException = exception;
        rejectionData = data;
        completionCount++;
        completed.countDown();
    }
}
