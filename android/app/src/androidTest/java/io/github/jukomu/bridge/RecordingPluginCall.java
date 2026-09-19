package io.github.jukomu.bridge;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class RecordingPluginCall extends PluginCall {

    JSObject resolvedData;
    boolean resolvedWithoutData;
    String rejectionMessage;
    String rejectionCode;
    Exception rejectionException;
    JSObject rejectionData;
    int completionCount;
    private final CountDownLatch completion = new CountDownLatch(1);

    RecordingPluginCall(String methodName, JSObject data) {
        super(null, "Jmcomic", "test-callback", methodName,
            data == null ? new JSObject() : data);
    }

    @Override
    public void resolve(JSObject data) {
        resolvedData = data;
        completionCount++;
        completion.countDown();
    }

    @Override
    public void resolve() {
        resolvedWithoutData = true;
        completionCount++;
        completion.countDown();
    }

    @Override
    public void reject(String message, String code, Exception exception, JSObject data) {
        rejectionMessage = message;
        rejectionCode = code;
        rejectionException = exception;
        rejectionData = data;
        completionCount++;
        completion.countDown();
    }

    boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return completion.await(timeout, unit);
    }
}
