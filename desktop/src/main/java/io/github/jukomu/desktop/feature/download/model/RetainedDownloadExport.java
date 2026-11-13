package io.github.jukomu.desktop.feature.download.model;

/** 下载列表保留卡片打开本地导出文件所需的信息。 */
public record RetainedDownloadExport(
    String exportId,
    String format,
    long fileId,
    String fileRef,
    int startPage,
    int pageCount
) {
}
