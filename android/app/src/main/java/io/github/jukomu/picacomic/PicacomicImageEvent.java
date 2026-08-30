package io.github.jukomu.picacomic;

import com.getcapacitor.JSObject;

/** Safe asynchronous image event emitted by the isolated plugin. */
public final class PicacomicImageEvent {

    public enum Type {
        READY("picacomicImageReady"),
        FAILED("picacomicImageFailed");

        private final String eventName;

        Type(String eventName) {
            this.eventName = eventName;
        }

        public String getEventName() {
            return eventName;
        }
    }

    public final Type type;
    public final String imageKey;
    public final PicacomicErrorCode errorCode;
    public final boolean retryable;

    private PicacomicImageEvent(Type type, String imageKey, PicacomicErrorCode errorCode) {
        this.type = type;
        this.imageKey = imageKey;
        this.errorCode = errorCode;
        this.retryable = errorCode != null && errorCode.isRetryable();
    }

    public static PicacomicImageEvent ready(String imageKey) {
        return new PicacomicImageEvent(Type.READY, imageKey, null);
    }

    public static PicacomicImageEvent failed(String imageKey, PicacomicErrorCode errorCode) {
        return new PicacomicImageEvent(Type.FAILED, imageKey,
            errorCode == null ? PicacomicErrorCode.INTERNAL : errorCode);
    }

    public JSObject toJsObject() {
        JSObject value = new JSObject();
        value.put("imageKey", imageKey);
        if (errorCode != null) value.put("code", errorCode.getCode());
        value.put("retryable", retryable);
        return value;
    }
}
