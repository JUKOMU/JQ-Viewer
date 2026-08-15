package io.github.jukomu.bridge.handler;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import io.github.jukomu.feature.favorite.data.FavoriteStore;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 负责离线收藏夹和容灾备份 Bridge 的参数解析、Store 调用与响应封装。
 *
 * <p>所有入口均为一次性同步调用，不持有 {@link PluginCall}。
 */
public final class FavoritePluginHandler {

    private final FavoriteStore favoriteStore;

    public FavoritePluginHandler(FavoriteStore favoriteStore) {
        this.favoriteStore = favoriteStore;
    }

    /**
     * 查询全部离线收藏夹，结果包装在 {@code folders} 字段中。
     */
    public void getOfflineFolders(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("folders", favoriteStore.getFolders());
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 创建离线收藏夹；缺失名称按空字符串处理。
     */
    public void createOfflineFolder(PluginCall call) {
        try {
            String folderId = favoriteStore.createFolder(call.getString("name", ""));
            JSObject result = new JSObject();
            result.put("folderId", folderId);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 重命名离线收藏夹，返回 Store 的布尔执行结果。
     */
    public void renameOfflineFolder(PluginCall call) {
        try {
            boolean success = favoriteStore.renameFolder(
                call.getString("folderId", ""),
                call.getString("name", "")
            );
            resolveSuccess(call, success);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 删除离线收藏夹及其收藏项，返回 Store 的布尔执行结果。
     */
    public void deleteOfflineFolder(PluginCall call) {
        try {
            boolean success = favoriteStore.deleteFolder(call.getString("folderId", ""));
            resolveSuccess(call, success);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 向指定文件夹添加一个收藏对象。
     */
    public void addOfflineFavorite(PluginCall call) {
        try {
            boolean success = favoriteStore.addItem(
                call.getString("folderId", ""),
                call.getObject("item")
            );
            resolveSuccess(call, success);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按文件夹 ID 和专辑 ID 删除一个收藏项。
     */
    public void removeOfflineFavorite(PluginCall call) {
        try {
            boolean success = favoriteStore.removeItem(
                call.getString("folderId", ""),
                call.getString("albumId", "")
            );
            resolveSuccess(call, success);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询单个文件夹的分页收藏；页码和每页数量缺省值分别为 1 和 20。
     */
    public void getOfflineFavorites(PluginCall call) {
        try {
            JSONObject data = favoriteStore.getItems(
                call.getString("folderId", ""),
                call.getString("keyword", null),
                call.getInt("page", 1),
                call.getInt("pageSize", 20)
            );
            JSObject result = new JSObject();
            result.put("totalItems", data.optInt("totalItems", 0));
            result.put("totalPages", data.optInt("totalPages", 1));
            result.put("currentPage", data.optInt("currentPage", 1));
            result.put("content", data.optJSONArray("content"));
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询指定文件夹的全部收藏项，结果包装在 {@code items} 字段中。
     */
    public void getAllOfflineFavorites(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("items", favoriteStore.getAllItems(call.getString("folderId", "")));
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询全部文件夹去重后的收藏总数。
     */
    public void getOfflineFavoritesTotalCount(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("count", favoriteStore.getTotalCount());
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询全部文件夹合并并按专辑去重后的收藏项。
     */
    public void getAllOfflineFavoritesMerged(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("items", favoriteStore.getAllItemsMerged());
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 将源文件夹的全部收藏移入目标文件夹。
     */
    public void moveAllOfflineFavorites(PluginCall call) {
        try {
            boolean success = favoriteStore.moveAllItems(
                call.getString("sourceId", ""),
                call.getString("targetId", "")
            );
            resolveSuccess(call, success);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 复制指定文件夹及其中的收藏项，返回新文件夹 ID。
     */
    public void copyOfflineFolder(PluginCall call) {
        try {
            String folderId = favoriteStore.copyFolder(
                call.getString("sourceId", ""),
                call.getString("name", "")
            );
            JSObject result = new JSObject();
            result.put("folderId", folderId);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 批量添加收藏项，返回实际新增数量。
     */
    public void addOfflineFavoritesBatch(PluginCall call) {
        try {
            int count = favoriteStore.addItemsBatch(
                call.getString("folderId", ""),
                call.getArray("items")
            );
            JSObject result = new JSObject();
            result.put("count", count);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 将全部文件夹的收藏合并到目标文件夹并按专辑去重。
     */
    public void mergeOfflineAllToFolder(PluginCall call) {
        try {
            boolean success = favoriteStore.mergeAllToFolder(call.getString("targetId", ""));
            resolveSuccess(call, success);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按 key 保存收藏备份，成功完成 Store 调用后返回 {@code {success: true}}。
     */
    public void saveOfflineBackup(PluginCall call) {
        try {
            favoriteStore.saveBackup(
                call.getString("key", ""),
                call.getArray("items")
            );
            resolveSuccess(call, true);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按 key 读取收藏备份；不存在时 {@code items} 为 JSON null。
     */
    public void loadOfflineBackup(PluginCall call) {
        try {
            JSONArray items = favoriteStore.loadBackup(call.getString("key", ""));
            JSObject result = new JSObject();
            result.put("items", items != null ? items : JSONObject.NULL);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按 key 删除收藏备份，返回 Store 的布尔执行结果。
     */
    public void deleteOfflineBackup(PluginCall call) {
        try {
            boolean success = favoriteStore.deleteBackup(call.getString("key", ""));
            resolveSuccess(call, success);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 按创建时间倒序查询全部备份 key。
     */
    public void listOfflineBackupKeys(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("keys", favoriteStore.listBackupKeys());
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    private static void resolveSuccess(PluginCall call, boolean success) {
        JSObject result = new JSObject();
        result.put("success", success);
        call.resolve(result);
    }
}
