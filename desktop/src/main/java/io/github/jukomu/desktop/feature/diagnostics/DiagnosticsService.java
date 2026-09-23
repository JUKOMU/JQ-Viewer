package io.github.jukomu.desktop.feature.diagnostics;

import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.diagnostics.model.DiagnosticsResponse;
import io.github.jukomu.desktop.feature.download.data.DownloadStore;
import io.github.jukomu.desktop.feature.image.CacheService;
import io.github.jukomu.desktop.feature.pdf.export.ExportStore;

import java.util.List;

/** 从既有路径、任务和缓存事实组装维护诊断信息。 */
public final class DiagnosticsService {
    private static final int FAILURE_LIMIT = 3;

    private final Paths paths;
    private final DownloadStore downloads;
    private final ExportStore pdfExports;
    private final CacheService cache;

    public DiagnosticsService(
            Paths paths,
            DownloadStore downloads,
            ExportStore pdfExports,
            CacheService cache
    ) {
        this.paths = paths;
        this.downloads = downloads;
        this.pdfExports = pdfExports;
        this.cache = cache;
    }

    public DiagnosticsResponse snapshot() {
        DownloadStore.DiagnosticSnapshot downloadSnapshot =
                downloads.diagnosticSnapshot(FAILURE_LIMIT);
        ExportStore.DiagnosticSnapshot pdfSnapshot =
                pdfExports.diagnosticSnapshot(FAILURE_LIMIT);
        return new DiagnosticsResponse(
                System.currentTimeMillis(),
                List.of(
                        path("data", "应用数据", paths.dataDirectory()),
                        path("cache", "运行缓存", paths.cacheDirectory()),
                        path("logs", "日志", paths.logsDirectory()),
                        path("downloads", "离线下载", paths.downloadsDirectory()),
                        path("pdf", "PDF 文件", paths.pdfDirectory()),
                        path("ocr", "OCR 模型", paths.ocrDirectory())
                ),
                List.of(
                        new DiagnosticsResponse.TaskSummary(
                                "download", "下载任务",
                                downloadSnapshot.total(), downloadSnapshot.active(),
                                downloadSnapshot.failed(),
                                downloadSnapshot.recentFailures().stream()
                                        .map(failure -> new DiagnosticsResponse.TaskFailure(
                                                failure.id(), failure.title(), failure.status(),
                                                failure.reason(), failure.updatedAt()))
                                        .toList()),
                        new DiagnosticsResponse.TaskSummary(
                                "pdf-export", "PDF 导出",
                                pdfSnapshot.total(), pdfSnapshot.active(), pdfSnapshot.failed(),
                                pdfSnapshot.recentFailures().stream()
                                        .map(failure -> new DiagnosticsResponse.TaskFailure(
                                                failure.id(), failure.title(), failure.status(),
                                                failure.reason(), failure.updatedAt()))
                                        .toList())
                ),
                cache.diagnosticResources().stream()
                        .map(resource -> new DiagnosticsResponse.ClearableResource(
                                resource.kind(), resource.label(), resource.entryCount(),
                                resource.sizeBytes()))
                        .toList()
        );
    }

    private static DiagnosticsResponse.PathEntry path(
            String kind,
            String label,
            java.nio.file.Path path
    ) {
        return new DiagnosticsResponse.PathEntry(kind, label, path.toString());
    }
}
