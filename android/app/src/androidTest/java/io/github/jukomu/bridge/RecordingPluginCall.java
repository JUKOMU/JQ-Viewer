package io.github.jukomu.bridge;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

class RecordingPluginCall extends PluginCall {

    JSObject resolvedData;
    boolean resolvedWithoutData;
    String rejectionMessage;
    String rejectionCode;
    Exception rejectionException;
    JSObject rejectionData;
    int completionCount;

    RecordingPluginCall(String methodName, JSObject data) {
        super(null, "Jmcomic", "test-callback", methodName,
            data == null ? new JSObject() : data);
    }

    @Override
    public void resolve(JSObject data) {
        resolvedData = data;
        completionCount++;
    }

    @Override
    public void resolve() {
        resolvedWithoutData = true;
        completionCount++;
    }

    @Override
    public void reject(String message, String code, Exception exception, JSObject data) {
        rejectionMessage = message;
        rejectionCode = code;
        rejectionException = exception;
        rejectionData = data;
        completionCount++;
    }
}
