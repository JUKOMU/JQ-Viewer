package io.github.jukomu.desktop.feature.files.model;

/**
 * 可展示且可回传的文件引用。
 */
public record FileDescriptorResponse(
    String format,
    String ref,
    String fileName,
    String displayPath
) {
}
