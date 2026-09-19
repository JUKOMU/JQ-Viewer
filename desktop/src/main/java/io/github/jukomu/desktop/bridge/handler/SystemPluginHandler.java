package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.bridge.model.InitStatusResponse;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.feature.diagnostics.DiagnosticsService;
import io.github.jukomu.desktop.feature.network.NetworkService;
import io.github.jukomu.desktop.feature.notification.LaunchRouteService;
import io.javalin.http.Context;

/** 提供服务状态查询。 */
public final class SystemPluginHandler {
    private final RequestExecutor requests;
    private final NetworkService network;
    private final LaunchRouteService launchRoutes;
    private final DiagnosticsService diagnostics;

    public SystemPluginHandler(RequestExecutor requests, NetworkService network) {
        this(requests, network, null, null);
    }

    public SystemPluginHandler(
            RequestExecutor requests,
            NetworkService network,
            LaunchRouteService launchRoutes
    ) {
        this(requests, network, launchRoutes, null);
    }

    public SystemPluginHandler(
            RequestExecutor requests,
            NetworkService network,
            LaunchRouteService launchRoutes,
            DiagnosticsService diagnostics
    ) {
        this.requests = requests;
        this.network = network;
        this.launchRoutes = launchRoutes;
        this.diagnostics = diagnostics;
    }

    public void getInitStatus(Context context) {
        context.json(new InitStatusResponse(true));
    }

    public void getDomainStates(Context context) {
        requests.run(context, () -> requireNetwork().getDomainStates());
    }

    public void reprobeDomains(Context context) {
        requests.run(context, () -> {
            // 与 Android 保持一致：客户端未初始化时重新探活是无操作成功。
            if (network != null) network.reprobeDomains();
            return SuccessResponse.ok();
        });
    }

    public void measureLatency(Context context) {
        requests.run(context, () -> requireNetwork().measureLatency());
    }

    public void consumeLaunchRoute(Context context) {
        context.json(requireLaunchRoutes().consume());
    }

    public void getDiagnostics(Context context) {
        requests.run(context, () -> requireDiagnostics().snapshot());
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
