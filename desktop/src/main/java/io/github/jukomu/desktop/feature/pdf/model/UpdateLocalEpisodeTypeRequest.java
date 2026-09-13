package io.github.jukomu.desktop.feature.pdf.model;

/** 同步下载与 PDF 文件库的单话/多话元数据。 */
public record UpdateLocalEpisodeTypeRequest(String albumId, Boolean isSingleEpisode) {
}
