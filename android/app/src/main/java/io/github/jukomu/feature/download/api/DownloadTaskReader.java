package io.github.jukomu.feature.download.api;

import org.json.JSONObject;

import java.util.List;

/**
 * 提供下载任务和图片清单的只读查询。
 */
public interface DownloadTaskReader {
    List<JSONObject> getAllTasks();

    JSONObject getTask(String taskId);

    List<JSONObject> getImages(String taskId);
}
