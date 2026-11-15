package io.github.jukomu.desktop.feature.download.model;

/** 保留下载项的分卷来源，文件库记录删除后仍保留定位和章节范围。 */
public record RetainedExportCandidate(
    String exportId, String format, Long fileId, String fileRef,
    int startPage, int pageCount, String availability
) {}
