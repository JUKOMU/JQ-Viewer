package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.bridge.model.InitStatusResponse;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.client.JmcomicSessionManager;
import io.github.jukomu.desktop.feature.diagnostics.DiagnosticsService;
import io.github.jukomu.desktop.feature.network.NetworkService;
import io.github.jukomu.desktop.feature.notification.LaunchRouteService;
import io.javalin.http.Context;

/** 提供服务状态查询。 */
public final class SystemPluginHandler {
    private final RequestExecutor networkRequests;
    private final RequestExecutor diagnosticsRequests;
    private final JmcomicSessionManager clientSession;
    private final NetworkService network;
    private final LaunchRouteService launchRoutes;
    private final DiagnosticsService diagnostics;

    public SystemPluginHandler(
            RequestExecutor networkRequests,
            RequestExecutor diagnosticsRequests,
            JmcomicSessionManager clientSession,
            NetworkService network,
            LaunchRouteService launchRoutes,
            DiagnosticsService diagnostics
    ) {
        this.networkRequests = networkRequests;
        this.diagnosticsRequests = diagnosticsRequests;
        this.clientSession = clientSession;
        this.network = network;
        this.launchRoutes = launchRoutes;
        this.diagnostics = diagnostics;
    }

    public void getInitStatus(Context context) {
        context.json(new InitStatusResponse(clientSession.getClient() != null));
    }

    public void getClientState(Context context) {
        context.json(clientSession.getSnapshot());
    }

    public void getDomainStates(Context context) {
        networkRequests.run(context, () -> requireNetwork().getDomainStates());
    }

    public void reprobeDomains(Context context) {
        networkRequests.run(context, () -> {
            if (clientSession.getClient() == null) clientSession.startOrRetry();
            else if (network != null) network.reprobeDomains();
            return SuccessResponse.ok();
        });
    }

    public void measureLatency(Context context) {
        networkRequests.run(context, () -> requireNetwork().measureLatency());
    }

    public void consumeLaunchRoute(Context context) {
        context.json(requireLaunchRoutes().consume());
    }

    public void getDiagnostics(Context context) {
        diagnosticsRequests.run(context, () -> requireDiagnostics().snapshot());
    }

    private NetworkService requireNetwork() {
        if (network == null) throw ApiException.unavailable("网络客户端尚未初始化");
        return network;
    }

    private LaunchRouteService requireLaunchRoutes() {
        if (launchRoutes == null) throw ApiException.unavailable("启动路由服务尚未初始化");
        return launchRoutes;
    }

    private DiagnosticsService requireDiagnostics() {
        if (diagnostics == null) throw ApiException.unavailable("诊断服务尚未初始化");
        return diagnostics;
    }
}
