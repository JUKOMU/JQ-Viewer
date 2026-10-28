package io.github.jukomu.desktop.feature.network.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.jukomu.desktop.feature.network.ImageHostOptimizer;

import java.util.List;

/** 域名探活与网络恢复过程中的状态变化。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NetworkProbeEvent(
    String phase,
    String message,
    long timestamp,
    List<DomainStateResponse> domains,
    Integer alive,
    Integer total,
    Boolean allDeadFallback,
    Long runId,
    Long revision,
    Boolean running,
    String currentHost,
    String selectedHost,
    List<ImageHostResult> hosts,
    String error
) {
    public record ImageHostResult(String host, boolean available, long elapsedMs,
                                  long bytes, long bytesPerSecond, String error) {
    }

    private static NetworkProbeEvent standard(
        String phase, String message, List<DomainStateResponse> domains,
        Integer alive, Integer total, Boolean allDeadFallback
    ) {
        return new NetworkProbeEvent(phase, message, System.currentTimeMillis(), domains,
            alive, total, allDeadFallback, null, null, null, null, null, null, null);
    }

    public static NetworkProbeEvent probing() {
        return standard("probing", "正在探测域名连通性...", null, null, null, null);
    }

    public static NetworkProbeEvent result(DomainStatesResponse state) {
        String message = state.allDeadFallback()
            ? "探活完成 · 全部不可达"
            : "探活完成 · " + state.alive() + "/" + state.total() + " 可达";
        return standard("result", message, state.domains(), state.alive(), state.total(),
            state.allDeadFallback());
    }

    public static NetworkProbeEvent error(String message) {
        return standard("error", message, null, null, null, null);
    }

    public static NetworkProbeEvent networkChanged() {
        return standard("network_changed", "网络环境已变化", null, null, null, null);
    }

    public static NetworkProbeEvent networkLost() {
        return standard("network_lost", "网络不可用", null, null, null, null);
    }

    public static NetworkProbeEvent networkRestored(DomainStatesResponse state) {
        return standard("network_restored", "网络恢复并完成线路探测", state.domains(),
            state.alive(), state.total(), state.allDeadFallback());
    }

    public static NetworkProbeEvent routeAuth(String phase, String message) {
        return standard(phase, message, null, null, null, null);
    }

    public static NetworkProbeEvent imageRoute(
        String phase, String message, ImageHostOptimizer.Progress progress, String error
    ) {
        return new NetworkProbeEvent(phase, message, System.currentTimeMillis(), null,
            null, null, null, progress.runId(), progress.revision(), progress.running(),
            progress.currentHost(), null, toImageHostResults(progress.hosts()), error);
    }

    public static NetworkProbeEvent imageRoute(
        String phase, String message, ImageHostOptimizer.Result result, String error
    ) {
        return new NetworkProbeEvent(phase, message, System.currentTimeMillis(), null,
            null, null, null, result.runId(), result.revision(), false, null,
            result.selectedHost(), toImageHostResults(result.hosts()), error);
    }

    public static NetworkProbeEvent imageRouteProgress(
        String phase, String message, ImageHostOptimizer.Progress progress
    ) {
        return imageRoute(phase, message, progress, progress.error());
    }

    private static List<ImageHostResult> toImageHostResults(
        List<ImageHostOptimizer.HostResult> hosts
    ) {
        return hosts.stream().map(host -> new ImageHostResult(
            host.host(), host.available(), host.elapsedMs(), host.bytes(),
            host.bytesPerSecond(), host.error())).toList();
    }
}
