package io.github.jukomu.bridge.handler;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;
import io.github.jukomu.feature.download.DownloadService;
import org.json.JSONObject;

/**
 * 负责下载任务 Bridge 的参数校验、服务调用和响应适配。
 */
public final class DownloadPluginHandler {
    private final DownloadService downloadService;

    public DownloadPluginHandler(DownloadService downloadService) {
        this.downloadService = downloadService;
    }

    /**
     * 提交章节下载并返回任务标识。
     */
    public void downloadChapter(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            String chapterId = call.getString("chapterId");
            if (albumId == null || chapterId == null) {
                call.reject("albumId and chapterId are required");
                return;
            }
            String taskId = downloadService.downloadChapter(
                albumId,
                chapterId,
                call.getString("albumTitle", ""),
                call.getString("chapterTitle", ""),
                call.getString("coverUrl", ""));
            JSObject result = new JSObject();
            result.put("taskId", taskId);
            call.resolve(result);
        } catch (IllegalStateException error) {
            call.reject(error.getMessage());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void getDownloadTasksPage(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(downloadService.getDownloadTasksPage(
                call.getString("status", "active"), call.getString("sort", "time"),
                call.getString("direction", "desc"), call.getString("cursor"),
                call.getInt("limit", 50))));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void getCompletedDownloadAlbumIds(PluginCall call) {
        try {
            JSArray input = call.getArray("albumIds");
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < input.length(); i++) ids.add(input.getString(i));
            JSObject result = new JSObject();
            result.put("albumIds", new org.json.JSONArray(downloadService.getCompletedDownloadAlbumIds(ids)));
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void getDownloadTaskStatuses(PluginCall call) {
        try {
            JSObject result = new JSObject();
            org.json.JSONArray statuses = new org.json.JSONArray();
            for (JSONObject task : downloadService.getDownloadTaskStatuses(call.getString("albumId"))) {
                statuses.put(new JSONObject().put("chapterId", task.optString("chapterId"))
                    .put("status", task.optString("status")));
            }
            result.put("statuses", statuses);
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void getCompletedDownloadChapters(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("tasks", new org.json.JSONArray(downloadService.getCompletedDownloadChapters(
                call.getString("albumId"))));
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void getActiveDownloadProgress(PluginCall call) {
        try {
            JSObject result = new JSObject();
            result.put("tasks", new org.json.JSONArray(downloadService.getActiveDownloadProgress()));
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void getDownloadedResourceKeys(PluginCall call) {
        try {
            JSArray input = call.getArray("items");
            java.util.List<String> albums = new java.util.ArrayList<>();
            java.util.List<String> chapters = new java.util.ArrayList<>();
            for (int i = 0; i < input.length(); i++) {
                JSONObject item = input.getJSONObject(i);
                albums.add(item.getString("albumId"));
                chapters.add(item.getString("chapterId"));
            }
            JSObject result = new JSObject();
            result.put("keys", new org.json.JSONArray(downloadService.getDownloadedResourceKeys(albums, chapters)));
            call.resolve(result);
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    public void clearDownloadTasks(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(downloadService.clearDownloadTasks(
                call.getString("status"))));
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 取消指定下载任务。
     */
    public void cancelDownload(PluginCall call) {
        try {
            String taskId = requiredTaskId(call);
            if (taskId == null) return;
            downloadService.cancelDownload(taskId);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 暂停指定下载任务。
     */
    public void pauseDownload(PluginCall call) {
        try {
            String taskId = requiredTaskId(call);
            if (taskId == null) return;
            downloadService.pauseDownload(taskId);
            call.resolve(successResult());
        } catch (IllegalArgumentException | IllegalStateException error) {
            call.reject(error.getMessage());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 恢复指定下载任务。
     */
    public void resumeDownload(PluginCall call) {
        try {
            String taskId = requiredTaskId(call);
            if (taskId == null) return;
            downloadService.resumeDownload(taskId);
            call.resolve(successResult());
        } catch (IllegalArgumentException | IllegalStateException error) {
            call.reject(error.getMessage());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 删除章节文件和对应任务记录。
     */
    public void deleteDownloaded(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            String chapterId = call.getString("chapterId");
            if (albumId == null || chapterId == null) {
                call.reject("albumId and chapterId are required");
                return;
            }
            downloadService.deleteDownloaded(albumId, chapterId);
            call.resolve(successResult());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    /**
     * 返回已完成章节的本地图片信息。
     */
    public void getDownloadedPhoto(PluginCall call) {
        try {
            String albumId = call.getString("albumId");
            String chapterId = call.getString("chapterId");
            if (albumId == null || chapterId == null) {
                call.reject("albumId and chapterId are required");
                return;
            }
            JSONObject photo = downloadService.getDownloadedPhoto(albumId, chapterId);
            call.resolve(JSObject.fromJSONObject(photo));
        } catch (IllegalArgumentException | IllegalStateException error) {
            call.reject(error.getMessage());
        } catch (Exception error) {
            call.reject(error.getMessage(), error);
        }
    }

    private static String requiredTaskId(PluginCall call) {
        String taskId = call.getString("taskId");
        if (taskId == null) {
            call.reject("taskId is required");
            return null;
        }
        return taskId;
    }

    private static JSObject successResult() {
        JSObject result = new JSObject();
        result.put("success", true);
        return result;
    }
}
