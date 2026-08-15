package io.github.jukomu.bridge;

import io.github.jukomu.feature.catalog.ApiCallback;
import io.github.jukomu.feature.catalog.ApiService;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

final class FakeApiService extends ApiService {

    String method;
    List<Object> arguments = Collections.emptyList();
    ApiCallback pendingCallback;
    JSONObject nextResult;
    String errorMessage;
    Exception error;
    boolean autoComplete = true;

    FakeApiService() {
        super(null, null, null);
    }

    void succeedWith(JSONObject result) {
        nextResult = result;
        errorMessage = null;
        error = null;
    }

    void failWith(String message, Exception exception) {
        errorMessage = message;
        error = exception;
    }

    void completeSuccess() {
        ApiCallback callback = pendingCallback;
        pendingCallback = null;
        callback.onSuccess(resultFor(method));
    }

    @Override
    public void search(String keyword, String category, String orderBy, String time,
                       int searchMainTag, int page, ApiCallback callback) {
        record("search", callback, keyword, category, orderBy, time, searchMainTag, page);
    }

    @Override
    public void categories(String keyword, String category, String orderBy, String time,
                           int searchMainTag, int page, ApiCallback callback) {
        record("categories", callback, keyword, category, orderBy, time, searchMainTag, page);
    }

    @Override
    public void getAlbum(String id, ApiCallback callback) {
        record("getAlbum", callback, id);
    }

    @Override
    public void getPhoto(String id, ApiCallback callback) {
        record("getPhoto", callback, id);
    }

    @Override
    public void getComments(String albumId, int page, ApiCallback callback) {
        record("getComments", callback, albumId, page);
    }

    @Override
    public void toggleAlbumLike(String id, ApiCallback callback) {
        record("toggleAlbumLike", callback, id);
    }

    @Override
    public void getFavorites(int folderId, int page, ApiCallback callback) {
        record("getFavorites", callback, folderId, page);
    }

    @Override
    public void manageFavoriteFolder(String type, String folderId, String folderName,
                                     String albumId, ApiCallback callback) {
        record("manageFavoriteFolder", callback, type, folderId, folderName, albumId);
    }

    @Override
    public void toggleAlbumFavorite(String id, String folderId, ApiCallback callback) {
        record("toggleAlbumFavorite", callback, id, folderId);
    }

    @Override
    public void login(String username, String password, ApiCallback callback) {
        record("login", callback, username, password);
    }

    @Override
    public void logout(ApiCallback callback) {
        record("logout", callback);
    }

    @Override
    public void getUserProfile(String uid, ApiCallback callback) {
        record("getUserProfile", callback, uid);
    }

    private void record(String methodName, ApiCallback callback, Object... values) {
        method = methodName;
        arguments = Arrays.asList(values);
        pendingCallback = callback;
        if (!autoComplete) {
            return;
        }
        pendingCallback = null;
        if (errorMessage != null) {
            callback.onError(errorMessage, error);
        } else {
            callback.onSuccess(resultFor(methodName));
        }
    }

    private JSONObject resultFor(String methodName) {
        if (nextResult != null) {
            return nextResult;
        }
        JSONObject result = new JSONObject();
        try {
            result.put("method", methodName);
        } catch (JSONException exception) {
            throw new AssertionError(exception);
        }
        return result;
    }
}
