package io.github.jukomu.desktop.feature.network.model;

import java.util.List;

/**
 * 一轮域名延迟测量的完整结果。
 */
public record LatencyResultsResponse(List<LatencyResultResponse> results) {
}
