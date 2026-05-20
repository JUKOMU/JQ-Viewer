package io.github.jukomu.service;

import org.json.JSONObject;

/**
 * 异步 API 回调——service 层通过此接口将 API 结果返回给 bridge 层。
 */
public interface ApiCallback {
    void onSuccess(JSONObject result);

    void onError(String message, Exception e);
}
