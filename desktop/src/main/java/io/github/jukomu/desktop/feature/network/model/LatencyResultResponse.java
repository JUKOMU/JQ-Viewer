package io.github.jukomu.desktop.feature.network.model;

/** 单个 JMComic API 域名的延迟测量结果。 */
public record LatencyResultResponse(String domain, int latencyMs, boolean timedOut) {
}
