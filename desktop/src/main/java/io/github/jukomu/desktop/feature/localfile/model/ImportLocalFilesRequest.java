package io.github.jukomu.desktop.feature.localfile.model;

import java.util.List;

/** 外部 PDF 批量导入参数。 */
public record ImportLocalFilesRequest(List<ImportLocalFileItemRequest> items) {
}
