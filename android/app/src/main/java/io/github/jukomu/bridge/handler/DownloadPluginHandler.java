package io.github.jukomu.bridge.handler;

import android.util.Log;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import io.github.jukomu.feature.download.DownloadService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 负责下载任务 Bridge 的参数校验、服务调用和响应适配。
 */
public final class DownloadPluginHandler {

    private static final String TAG = "DownloadPluginHandler";

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

    /**
     * 返回任务列表和下载目录空间统计。
     */
    public void getDownloadTasks(PluginCall call) {
        try {
            JSONArray tasks = downloadService.getDownloadTasks();
            JSArray taskArray = new JSArray();
            for (int index = 0; index < tasks.length(); index++) {
                try {
                    taskArray.put(JSObject.fromJSONObject(tasks.getJSONObject(index)));
                } catch (Exception error) {
                    Log.d(TAG, "跳过无效下载任务条目", error);
                }
            }
            JSObject result = new JSObject();
            result.put("tasks", taskArray);
            result.put("usedBytes", downloadService.getUsedBytes());
            result.put("availableBytes", downloadService.getAvailableBytes());
            call.resolve(result);
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
