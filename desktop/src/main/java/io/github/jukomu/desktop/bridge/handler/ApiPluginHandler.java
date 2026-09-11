package io.github.jukomu.desktop.bridge.handler;

import io.github.jukomu.desktop.dto.DesktopDtos;
import io.github.jukomu.desktop.service.DesktopCatalogService;
import io.javalin.http.Context;

import java.util.Objects;
import java.util.concurrent.Executor;

/** 负责目录、章节和评论请求的解析、调度及 JSON 响应转换。 */
public final class ApiPluginHandler {
    private final DesktopCatalogService catalogService;
    private final Executor executor;

    public ApiPluginHandler(DesktopCatalogService catalogService, Executor executor) {
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public void search(Context context) {
        try {
            DesktopDtos.SearchRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.SearchRequest.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> catalogService.search(request.query())
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void categories(Context context) {
        try {
            DesktopDtos.SearchRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.SearchRequest.class);
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> catalogService.categories(request.query())
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void getAlbum(Context context) {
        try {
            DesktopDtos.IdRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.IdRequest.class);
            AsyncRequestHandler.submitJson(context, executor, () -> catalogService.getAlbum(request.id()));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void getPhoto(Context context) {
        try {
            DesktopDtos.IdRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.IdRequest.class);
            AsyncRequestHandler.submitJson(context, executor, () -> catalogService.getPhoto(request.id()));
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }

    public void getComments(Context context) {
        try {
            DesktopDtos.CommentsRequest request =
                    AsyncRequestHandler.readBody(context, DesktopDtos.CommentsRequest.class);
            int page = request.page() == null ? 1 : request.page();
            AsyncRequestHandler.submitJson(
                    context,
                    executor,
                    () -> catalogService.getComments(request.albumId(), page)
            );
        } catch (Exception error) {
            AsyncRequestHandler.writeError(context, error);
        }
    }
}
