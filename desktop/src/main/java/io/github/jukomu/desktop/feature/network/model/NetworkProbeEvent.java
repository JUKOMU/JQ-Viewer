package io.github.jukomu.desktop.feature.network.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 手动域名探活过程中的状态变化。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NetworkProbeEvent(
    String phase,
    String message,
    long timestamp,
    List<DomainStateResponse> domains,
    Integer alive,
    Integer total,
    Boolean allDeadFallback
) {
    public static NetworkProbeEvent probing() {
        return new NetworkProbeEvent(
            "probing", "正在探测域名连通性...", System.currentTimeMillis(),
            null, null, null, null);
    }

    public static NetworkProbeEvent result(DomainStatesResponse state) {
        String message = state.allDeadFallback()
            ? "探活完成 · 全部不可达"
            : "探活完成 · " + state.alive() + "/" + state.total() + " 可达";
        return new NetworkProbeEvent(
            "result", message, System.currentTimeMillis(), state.domains(),
            state.alive(), state.total(), state.allDeadFallback());
    }

    public static NetworkProbeEvent error(String message) {
        return new NetworkProbeEvent(
            "error", message, System.currentTimeMillis(),
            null, null, null, null);
    }
}
