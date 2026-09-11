package io.github.jukomu.desktop.bridge;

import io.github.jukomu.desktop.bridge.handler.AuthPluginHandler;
import io.github.jukomu.desktop.bridge.handler.CatalogPluginHandler;
import io.github.jukomu.desktop.bridge.handler.HistoryPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SettingsPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.javalin.http.Context;

/** 向本地 HTTP backend 暴露的唯一 Desktop bridge。 */
public final class DesktopPlugin {
    private final CatalogPluginHandler catalogHandler;
    private final AuthPluginHandler authHandler;
    private final SettingsPluginHandler settingsHandler;
    private final HistoryPluginHandler historyHandler;
    private final SystemPluginHandler systemHandler;

    public DesktopPlugin(
            SystemPluginHandler systemHandler,
            CatalogPluginHandler catalogHandler,
            AuthPluginHandler authHandler,
            SettingsPluginHandler settingsHandler,
            HistoryPluginHandler historyHandler
    ) {
        this.systemHandler = systemHandler;
        this.catalogHandler = catalogHandler;
        this.authHandler = authHandler;
        this.settingsHandler = settingsHandler;
        this.historyHandler = historyHandler;
    }

    @PluginMethod
    public void getInitStatus(Context context) {
        systemHandler.getInitStatus(context);
    }

    @PluginMethod
    public void search(Context context) {
        catalogHandler.search(context);
    }

    @PluginMethod
    public void categories(Context context) {
        catalogHandler.categories(context);
    }

    @PluginMethod
    public void getAlbum(Context context) {
        catalogHandler.getAlbum(context);
    }

    @PluginMethod
    public void getPhoto(Context context) {
        catalogHandler.getPhoto(context);
    }

    @PluginMethod
    public void getComments(Context context) {
        catalogHandler.getComments(context);
    }

    @PluginMethod
    public void login(Context context) {
        authHandler.login(context);
    }

    @PluginMethod
    public void logout(Context context) {
        authHandler.logout(context);
    }

    @PluginMethod
    public void checkLoginState(Context context) {
        authHandler.checkLoginState(context);
    }

    @PluginMethod
    public void getUserProfile(Context context) {
        authHandler.getUserProfile(context);
    }

    @PluginMethod
    public void getAllSettings(Context context) {
        settingsHandler.getAllSettings(context);
    }

    @PluginMethod
    public void setPreloadConcurrency(Context context) {
        settingsHandler.setPreloadConcurrency(context);
    }

    @PluginMethod
    public void setDownloadConcurrency(Context context) {
        settingsHandler.setDownloadConcurrency(context);
    }

    @PluginMethod
    public void setReaderPreloadPages(Context context) {
        settingsHandler.setReaderPreloadPages(context);
    }

    @PluginMethod
    public void setReaderDisplayMode(Context context) {
        settingsHandler.setReaderDisplayMode(context);
    }

    @PluginMethod
    public void setReaderAutoShowToolbarAtEnd(Context context) {
        settingsHandler.setReaderAutoShowToolbarAtEnd(context);
    }

    @PluginMethod
    public void getBrowseHistory(Context context) {
        historyHandler.getBrowseHistory(context);
    }

    @PluginMethod
    public void getBrowseHistoryOverview(Context context) {
        historyHandler.getBrowseHistoryOverview(context);
    }

    @PluginMethod
    public void recordBrowse(Context context) {
        historyHandler.recordBrowse(context);
    }

    @PluginMethod
    public void clearBrowseHistory(Context context) {
        historyHandler.clearBrowseHistory(context);
    }

    @PluginMethod
    public void deleteBrowseItem(Context context) {
        historyHandler.deleteBrowseItem(context);
    }
}
