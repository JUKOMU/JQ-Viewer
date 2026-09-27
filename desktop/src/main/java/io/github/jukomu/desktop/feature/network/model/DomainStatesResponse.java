package io.github.jukomu.desktop.feature.network.model;

import java.util.List;

/**
 * 当前域名状态的完整快照。
 */
public record DomainStatesResponse(
    List<DomainStateResponse> domains,
    int alive,
    int total,
    boolean allDeadFallback
) {
}
