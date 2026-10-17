package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.bridge.ApiException;
import io.github.jukomu.desktop.bridge.RequestExecutor;
import io.github.jukomu.desktop.bridge.model.InitStatusResponse;
import io.github.jukomu.desktop.bridge.model.LogReadRequest;
import io.github.jukomu.desktop.bridge.model.RouteSelectionRequest;
import io.github.jukomu.desktop.bridge.model.SuccessResponse;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.auth.AuthService;
import io.github.jukomu.desktop.feature.client.JmcomicSessionManager;
import io.github.jukomu.desktop.feature.diagnostics.DiagnosticsService;
import io.github.jukomu.desktop.feature.network.NetworkService;
import io.github.jukomu.desktop.feature.network.model.NetworkProbeEvent;
import io.github.jukomu.desktop.feature.notification.LaunchRouteService;
import io.github.jukomu.desktop.logging.ApplicationLogging;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 提供服务状态查询。
 */
public final class SystemPluginHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SystemPluginHandler.class);
    private final RequestExecutor networkRequests;
    private final RequestExecutor diagnosticsRequests;
    private final JmcomicSessionManager clientSession;
    private final NetworkService network;
    private final LaunchRouteService launchRoutes;
    private final DiagnosticsService diagnostics;
    private final Paths paths;
    private final AuthService auth;
    private final Executor authExecutor;
    private final Consumer<NetworkProbeEvent> authEventPublisher;

    public SystemPluginHandler(
        RequestExecutor networkRequests,
        RequestExecutor diagnosticsRequests,
        JmcomicSessionManager clientSession,
        NetworkService network,
        LaunchRouteService launchRoutes,
        DiagnosticsService diagnostics
    ) {
        this(networkRequests, diagnosticsRequests, clientSession, network, launchRoutes,
            diagnostics, null, null, Runnable::run, ignored -> {
            });
    }

    public SystemPluginHandler(
        RequestExecutor networkRequests,
        RequestExecutor diagnosticsRequests,
        JmcomicSessionManager clientSession,
        NetworkService network,
        LaunchRouteService launchRoutes,
        DiagnosticsService diagnostics,
        Paths paths,
        AuthService auth,
        Executor authExecutor,
        Consumer<NetworkProbeEvent> authEventPublisher
    ) {
        this.networkRequests = networkRequests;
        this.diagnosticsRequests = diagnosticsRequests;
        this.clientSession = clientSession;
        this.network = network;
        this.launchRoutes = launchRoutes;
        this.diagnostics = diagnostics;
        this.paths = paths;
        this.auth = auth;
        this.authExecutor = authExecutor;
        this.authEventPublisher = authEventPublisher;
    }

    public void getInitStatus(Context context) {
        respond(context, "getInitStatus", new InitStatusResponse(clientSession.getClient() != null));
    }

    public void getClientState(Context context) {
        respond(context, "getClientState", clientSession.getSnapshot());
    }

    public void getDomainStates(Context context) {
        networkRequests.run(context, () -> requireNetwork().getDomainStates());
    }

    public void getUsedDomain(Context context) {
        networkRequests.run(context, () -> {
            var client = clientSession.requireClient();
            if (!(client instanceof io.github.jukomu.jmcomic.core.client.impl.JmApiClient apiClient)) {
                throw ApiException.unavailable("当前 JMComic 客户端不支持线路选择");
            }
            String domain = apiClient.getUsedDomain();
            return java.util.Map.of("domain", domain == null ? "" : domain);
        });
    }

    public void applyApiRoute(Context context) {
        networkRequests.run(context, RouteSelectionRequest.class, request -> {
            var client = clientSession.requireClient();
            if (!(client instanceof io.github.jukomu.jmcomic.core.client.impl.JmApiClient apiClient)) {
                throw ApiException.unavailable("当前 JMComic 客户端不支持线路选择");
            }
            String beforeDomain = apiClient.getUsedDomain();
            if ("auto".equals(request.mode())) apiClient.useAutoDomain();
            else if ("manual".equals(request.mode())) {
                if (request.domain() == null || request.domain().isBlank()) {
                    throw ApiException.invalidRequest("domain is required in manual mode");
                }
                apiClient.useDomain(request.domain());
            } else throw ApiException.invalidRequest("mode must be auto or manual");
            String domain = apiClient.getUsedDomain();
            if (auth != null && !java.util.Objects.equals(beforeDomain, domain)) {
                try {
                    authEventPublisher.accept(NetworkProbeEvent.routeAuth(
                        "route_auth_started", "线路切换后正在恢复登录"));
                    authExecutor.execute(() -> {
                        try {
                            auth.reauthenticateAfterRouteChange();
                            authEventPublisher.accept(NetworkProbeEvent.routeAuth(
                                "route_auth_succeeded", "线路切换后登录已恢复"));
                        } catch (RuntimeException error) {
                            LOGGER.warn("线路切换后自动登录失败", error);
                            authEventPublisher.accept(NetworkProbeEvent.routeAuth(
                                "route_auth_failed", "线路切换后登录恢复失败"));
                        }
                    });
                } catch (RuntimeException error) {
                    LOGGER.warn("无法提交线路切换后的自动登录", error);
                    authEventPublisher.accept(NetworkProbeEvent.routeAuth(
                        "route_auth_failed", "线路切换后登录恢复失败"));
                }
            }
            return java.util.Map.of("domain", domain == null ? "" : domain);
        });
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
        respond(context, "consumeLaunchRoute", requireLaunchRoutes().consume());
    }

    public void getDiagnostics(Context context) {
        diagnosticsRequests.run(context, () -> requireDiagnostics().snapshot());
    }

    public void getLogs(Context context) {
        if (paths == null) throw ApiException.unavailable("日志服务尚未初始化");
        diagnosticsRequests.run(context, LogReadRequest.class, request -> {
            try {
                long fromLine = request.fromLine() == null ? 0L : request.fromLine();
                long fromOffset = request.fromOffset() == null ? 0L : request.fromOffset();
                return ApplicationLogging.readCurrent(paths, fromLine, fromOffset);
            } catch (java.io.IOException error) {
                throw new ApiException("internal", 500, "读取应用日志失败");
            }
        });
    }

    public void listLogFiles(Context context) {
        if (paths == null) throw ApiException.unavailable("日志服务尚未初始化");
        diagnosticsRequests.run(context, () -> {
            try {
                return java.util.Map.of("files", ApplicationLogging.listFiles(paths));
            } catch (java.io.IOException error) {
                throw new ApiException("internal", 500, "列出日志文件失败");
            }
        });
    }

    public void exportLogFiles(Context context) {
        if (paths == null) throw ApiException.unavailable("日志服务尚未初始化");
        diagnosticsRequests.run(context, io.github.jukomu.desktop.bridge.model.LogExportRequest.class, request -> {
            if (request.folder() == null || request.fileNames() == null || request.fileNames().isEmpty()) {
                throw ApiException.invalidRequest("folder和fileNames不能为空");
            }
            Path targetDirectory = io.github.jukomu.desktop.feature.files.FileReferences.parseFolder(request.folder());
            if (!Files.isDirectory(targetDirectory)) throw ApiException.notFound("导出目录不存在");
            java.util.List<String> exported = new java.util.ArrayList<>();
            java.util.List<String> skipped = new java.util.ArrayList<>();
            java.util.List<java.util.Map<String, String>> failed = new java.util.ArrayList<>();
            Path sourceDirectory = paths.logsDirectory().toAbsolutePath().normalize();
            boolean sameDirectory = sourceDirectory.equals(targetDirectory.toAbsolutePath().normalize());
            for (String fileName : request.fileNames()) {
                try {
                    Path source = ApplicationLogging.resolveLogFile(paths, fileName);
                    if (!Files.isRegularFile(source)) throw ApiException.notFound("日志文件不存在");
                    if (sameDirectory) {
                        skipped.add(fileName);
                    } else {
                        Files.copy(source, targetDirectory.resolve(fileName),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        exported.add(fileName);
                    }
                } catch (Exception error) {
                    failed.add(java.util.Map.of(
                        "fileName", fileName == null ? "" : fileName,
                        "message", error.getMessage() == null ? "导出失败" : error.getMessage()));
                }
            }
            return java.util.Map.of("exported", exported, "skipped", skipped, "failed", failed);
        });
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

    private static void respond(Context context, String operation, Object value) {
        long startedAtNanos = System.nanoTime();
        try {
            context.json(value);
            LOGGER.info("bridge direct response completed operation={} status=success elapsedMs={}",
                operation, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAtNanos));
        } catch (RuntimeException failure) {
            LOGGER.warn("bridge direct response failed operation={} errorType={} elapsedMs={}",
                operation, failure.getClass().getSimpleName(),
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAtNanos));
            throw failure;
        }
    }
}
