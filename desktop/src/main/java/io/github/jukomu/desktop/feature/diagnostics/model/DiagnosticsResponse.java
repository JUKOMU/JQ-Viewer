package io.github.jukomu.desktop.feature.diagnostics.model;

import java.util.List;

/**
 * Desktop 本地状态的只读诊断快照。
 */
public record DiagnosticsResponse(
    long generatedAt,
    List<PathEntry> paths,
    List<TaskSummary> tasks,
    List<ClearableResource> clearableResources
) {
    public record PathEntry(String kind, String label, String displayPath) {
    }

    public record TaskSummary(
        String kind,
        String label,
        int total,
        int active,
        int failed,
        List<TaskFailure> recentFailures
    ) {
    }

    public record TaskFailure(
        String id,
        String title,
        String status,
        String reason,
        long updatedAt
    ) {
    }

    public record ClearableResource(
        String kind,
        String label,
        int entryCount,
        long sizeBytes
    ) {
    }
}
