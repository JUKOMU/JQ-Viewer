package io.github.jukomu.bridge.handler;

import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import org.json.JSONObject;

import io.github.jukomu.feature.history.data.HistoryStore;

/**
 * 负责历史记录 Bridge 的参数解析、Store 调用和 Capacitor 响应封装。
 *
 * <p>所有入口均为一次性同步调用，不持有 {@link PluginCall}。
 */
public final class HistoryPluginHandler {

    private final HistoryStore historyStore;

    public HistoryPluginHandler(HistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    /**
     * 查询浏览历史；缺省分页参数均为 0，结果包含当前页 {@code items} 和全量
     * {@code totalCount}。
     */
    public void getBrowseHistory(PluginCall call) {
        try {
            int limit = call.getInt("limit", 0);
            int offset = call.getInt("offset", 0);
            JSONObject data = historyStore.getBrowseHistory(limit, offset);
            JSObject result = new JSObject();
            result.put("items", data.optJSONArray("items"));
            result.put("totalCount", data.optLong("totalCount", 0L));
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询浏览历史总数，不读取历史条目。
     */
    public void getBrowseHistoryTotalCount(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("count", historyStore.getBrowseHistoryTotalCount());
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 记录浏览历史；缺失的专辑和章节字段按空字符串传给 Store。
     */
    public void recordBrowse(PluginCall call) {
        try {
            historyStore.recordBrowse(
                call.getString("albumId", ""),
                call.getString("albumTitle", ""),
                call.getString("coverUrl", ""),
                call.getString("authors", ""),
                call.getString("chapterId", ""),
                call.getString("chapterTitle", "")
            );
            resolveSuccess(call);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 清空浏览历史，成功时返回 {@code {success: true}}。
     */
    public void clearBrowseHistory(PluginCall call) {
        try {
            historyStore.clearBrowseHistory();
            resolveSuccess(call);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 删除指定浏览记录；缺失的 {@code id} 按 0 处理。
     */
    public void deleteBrowseItem(PluginCall call) {
        try {
            historyStore.deleteBrowseItem(call.getInt("id", 0));
            resolveSuccess(call);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询解析历史；缺省分页参数均为 0，结果包含当前页 {@code items} 和全量
     * {@code totalCount}。
     */
    public void getParseHistory(PluginCall call) {
        try {
            int limit = call.getInt("limit", 0);
            int offset = call.getInt("offset", 0);
            JSONObject data = historyStore.getParseHistory(limit, offset);
            JSObject result = new JSObject();
            result.put("items", data.optJSONArray("items"));
            result.put("totalCount", data.optLong("totalCount", 0L));
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 查询解析历史总数，不读取历史条目。
     */
    public void getParseHistoryTotalCount(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("count", historyStore.getParseHistoryTotalCount());
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 新增解析历史；缺失文本按空字符串处理，缺失模式为 {@code single-mode}。
     */
    public void addParseHistory(PluginCall call) {
        try {
            historyStore.addParseHistory(
                call.getString("text", ""),
                call.getString("mode", "single-mode")
            );
            resolveSuccess(call);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 清空解析历史，成功时返回 {@code {success: true}}。
     */
    public void clearParseHistory(PluginCall call) {
        try {
            historyStore.clearParseHistory();
            resolveSuccess(call);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 删除指定解析记录；缺失的 {@code id} 按 0 处理。
     */
    public void deleteParseItem(PluginCall call) {
        try {
            historyStore.deleteParseItem(call.getInt("id", 0));
            resolveSuccess(call);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    private static void resolveSuccess(PluginCall call) {
        JSObject result = new JSObject();
        result.put("success", true);
        call.resolve(result);
    }
}
