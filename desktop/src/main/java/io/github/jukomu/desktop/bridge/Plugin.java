package io.github.jukomu.desktop.bridge;

import io.github.jukomu.desktop.bridge.handler.ApiPluginHandler;
import io.github.jukomu.desktop.bridge.handler.AuthPluginHandler;
import io.github.jukomu.desktop.bridge.handler.HistoryPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SettingsPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.javalin.http.Context;

/** 本地 HTTP bridge 的方法入口。 */
public final class Plugin {
    private final ApiPluginHandler api;
    private final AuthPluginHandler auth;
    private final SettingsPluginHandler settings;
    private final HistoryPluginHandler history;
    private final SystemPluginHandler system;

    public Plugin(ApiPluginHandler api, AuthPluginHandler auth,
                  SettingsPluginHandler settings, HistoryPluginHandler history,
                  SystemPluginHandler system) {
        this.api = api;
        this.auth = auth;
        this.settings = settings;
        this.history = history;
        this.system = system;
    }

    @PluginMethod
    public void getInitStatus(Context context) {
        system.getInitStatus(context);
    }

    @PluginMethod
    public void search(Context context) {
        api.search(context);
    }

    @PluginMethod
    public void categories(Context context) {
        api.categories(context);
    }

    @PluginMethod
    public void getAlbum(Context context) {
        api.getAlbum(context);
    }

    @PluginMethod
    public void getPhoto(Context context) {
        api.getPhoto(context);
    }

    @PluginMethod
    public void getComments(Context context) {
        api.getComments(context);
    }

    @PluginMethod
    public void preloadImages(Context context) {
        api.preloadImages(context);
    }

    @PluginMethod
    public void retryImage(Context context) {
        api.retryImage(context);
    }

    @PluginMethod
    public void login(Context context) {
        auth.login(context);
    }

    @PluginMethod
    public void logout(Context context) {
        auth.logout(context);
    }

    @PluginMethod
    public void checkLoginState(Context context) {
        auth.checkLoginState(context);
    }

    @PluginMethod
    public void getUserProfile(Context context) {
        auth.getUserProfile(context);
    }

    @PluginMethod
    public void getAllSettings(Context context) {
        settings.getAllSettings(context);
    }

    @PluginMethod
    public void setPreloadConcurrency(Context context) {
        settings.setPreloadConcurrency(context);
    }

    @PluginMethod
    public void setDownloadConcurrency(Context context) {
        settings.setDownloadConcurrency(context);
    }

    @PluginMethod
    public void setReaderPreloadPages(Context context) {
        settings.setReaderPreloadPages(context);
    }

    @PluginMethod
    public void setReaderDisplayMode(Context context) {
        settings.setReaderDisplayMode(context);
    }

    @PluginMethod
    public void setReaderAutoShowToolbarAtEnd(Context context) {
        settings.setReaderAutoShowToolbarAtEnd(context);
    }

    @PluginMethod
    public void getBrowseHistory(Context context) {
        history.getBrowseHistory(context);
    }

    @PluginMethod
    public void getBrowseHistoryOverview(Context context) {
        history.getBrowseHistoryOverview(context);
    }

    @PluginMethod
    public void recordBrowse(Context context) {
        history.recordBrowse(context);
    }

    @PluginMethod
    public void clearBrowseHistory(Context context) {
        history.clearBrowseHistory(context);
    }

    @PluginMethod
    public void deleteBrowseItem(Context context) {
        history.deleteBrowseItem(context);
    }
}
