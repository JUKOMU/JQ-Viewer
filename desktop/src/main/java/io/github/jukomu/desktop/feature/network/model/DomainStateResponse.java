package io.github.jukomu.desktop.feature.network.model;

/**
 * 单个 JMComic API 域名的当前可达状态。
 */
public record DomainStateResponse(String domain, boolean reachable) {
}
