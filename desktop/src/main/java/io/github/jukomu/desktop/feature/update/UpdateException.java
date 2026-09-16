package io.github.jukomu.desktop.feature.update;

/** Desktop 更新契约、网络或安装准备失败。 */
public final class UpdateException extends RuntimeException {
    public UpdateException(String message) {
        super(message);
    }

    public UpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
