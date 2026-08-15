package io.github.jukomu.bridge.handler;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import io.github.jukomu.feature.catalog.ApiCallback;
import io.github.jukomu.feature.catalog.ApiService;
import io.github.jukomu.jmcomic.api.enums.Category;
import io.github.jukomu.jmcomic.api.enums.OrderBy;
import io.github.jukomu.jmcomic.api.enums.SearchMainTag;
import io.github.jukomu.jmcomic.api.enums.TimeOption;
import org.json.JSONObject;

/**
 * 负责在线内容 API Bridge 的参数校验、异步调用和响应适配。
 *
 * <p>通过校验的调用会设置 keepAlive，由 {@link ApiService} 负责执行与超时控制。
 */
public final class ApiPluginHandler {

    private final ApiService apiService;

    public ApiPluginHandler(ApiService apiService) {
        this.apiService = apiService;
    }

    /**
     * 按查询对象搜索专辑；查询对象为必填参数。
     */
    public void search(PluginCall call) {
        try {
            JSObject query = call.getObject("query");
            if (query == null) {
                call.reject("query is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.search(
                query.getString("keyword", ""),
                query.getString("category", Category.ALL.getValue()),
                query.getString("orderBy", OrderBy.LATEST.getValue()),
                query.getString("time", TimeOption.ALL.getValue()),
                query.getInteger("searchMainTag", SearchMainTag.SITE_SEARCH.getValue()),
                query.getInteger("page", 1),
                bridgeCallback(call)
            );
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按查询对象读取分类页；查询参数及缺省值与搜索入口一致。
     */
    public void categories(PluginCall call) {
        try {
            JSObject query = call.getObject("query");
            if (query == null) {
                call.reject("query is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.categories(
                query.getString("keyword", ""),
                query.getString("category", Category.ALL.getValue()),
                query.getString("orderBy", OrderBy.LATEST.getValue()),
                query.getString("time", TimeOption.ALL.getValue()),
                query.getInteger("searchMainTag", SearchMainTag.SITE_SEARCH.getValue()),
                query.getInteger("page", 1),
                bridgeCallback(call)
            );
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按非空专辑 ID 查询专辑详情。
     */
    public void getAlbum(PluginCall call) {
        try {
            String id = call.getString("id");
            if (id == null || id.isEmpty()) {
                call.reject("id is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.getAlbum(id, bridgeCallback(call));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按非空章节 ID 查询图片信息。
     */
    public void getPhoto(PluginCall call) {
        try {
            String id = call.getString("id");
            if (id == null || id.isEmpty()) {
                call.reject("id is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.getPhoto(id, bridgeCallback(call));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询专辑评论；专辑 ID 必填，页码缺省值为 1。
     */
    public void getComments(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            if (albumId == null || albumId.isEmpty()) {
                call.reject("albumId is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.getComments(
                albumId,
                call.getInt("page", 1),
                bridgeCallback(call)
            );
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 切换指定专辑的点赞状态。
     */
    public void toggleAlbumLike(PluginCall call) {
        try {
            String id = call.getString("id");
            if (id == null || id.isEmpty()) {
                call.reject("id is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.toggleAlbumLike(id, bridgeCallback(call));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询在线收藏；查询对象必填，文件夹和页码缺省值分别为 0 和 1。
     */
    public void getFavorites(PluginCall call) {
        try {
            JSObject query = call.getObject("query");
            if (query == null) {
                call.reject("query is required");
                return;
            }
            call.setKeepAlive(true);
            int folderId = Integer.parseInt(query.getString("folderId", "0"));
            apiService.getFavorites(
                folderId,
                query.getInteger("page", 1),
                bridgeCallback(call)
            );
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 添加、编辑、移动或删除在线收藏夹。
     */
    public void manageFavoriteFolder(PluginCall call) {
        try {
            String type = call.getString("type");
            if (type == null || type.isEmpty()) {
                call.reject("type is required (add/edit/move/del)");
                return;
            }
            String folderId = call.getString("folderId", "");
            if ("edit".equals(type) || "del".equals(type)) {
                if (folderId.isEmpty() || "0".equals(folderId)) {
                    call.reject("folderId is required for edit/del operations");
                    return;
                }
            } else if (folderId.isEmpty()) {
                folderId = "0";
            }
            call.setKeepAlive(true);
            apiService.manageFavoriteFolder(
                type,
                folderId,
                call.getString("folderName", ""),
                call.getString("albumId", ""),
                bridgeCallback(call)
            );
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 切换专辑在线收藏状态；文件夹 ID 缺省值为 0。
     */
    public void toggleAlbumFavorite(PluginCall call) {
        try {
            String id = call.getString("id");
            if (id == null || id.isEmpty()) {
                call.reject("id is required");
                return;
            }
            call.setKeepAlive(true);
            apiService.toggleAlbumFavorite(
                id,
                call.getString("folderId", "0"),
                bridgeCallback(call)
            );
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    private static ApiCallback bridgeCallback(PluginCall call) {
        return new ApiCallback() {
            @Override
            public void onSuccess(JSONObject result) {
                try {
                    call.resolve(JSObject.fromJSONObject(result));
                } catch (Exception error) {
                    call.reject(error.getMessage(), error);
                }
            }

            @Override
            public void onError(String message, Exception error) {
                call.reject(message, error);
            }
        };
    }
}
