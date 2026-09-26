package io.github.jukomu.desktop.bridge;

import io.github.jukomu.desktop.bridge.handler.*;
import io.javalin.http.Context;

/**
 * 本地 HTTP bridge 的方法入口。
 */
public final class Plugin {
    private final ApiPluginHandler api;
    private final AuthPluginHandler auth;
    private final CachePluginHandler cache;
    private final SettingsPluginHandler settings;
    private final HistoryPluginHandler history;
    private final OfflineFavoritePluginHandler favorites;
    private final FilePluginHandler files;
    private final DownloadPluginHandler downloads;
    private final LocalFilePluginHandler pdfs;
    private final SystemPluginHandler system;
    private final OcrPluginHandler ocr;
    private final UpdatePluginHandler updates;

    public Plugin(ApiPluginHandler api, AuthPluginHandler auth,
                  CachePluginHandler cache,
                  SettingsPluginHandler settings, HistoryPluginHandler history,
                  OfflineFavoritePluginHandler favorites,
                  FilePluginHandler files, DownloadPluginHandler downloads,
                  LocalFilePluginHandler pdfs,
                  SystemPluginHandler system,
                  OcrPluginHandler ocr,
                  UpdatePluginHandler updates) {
        this.api = api;
        this.auth = auth;
        this.cache = cache;
        this.settings = settings;
        this.history = history;
        this.favorites = favorites;
        this.files = files;
        this.downloads = downloads;
        this.pdfs = pdfs;
        this.system = system;
        this.ocr = ocr;
        this.updates = updates;
    }

    @PluginMethod
    public void getInitStatus(Context context) {
        system.getInitStatus(context);
    }

    @PluginMethod
    public void getClientState(Context context) {
        system.getClientState(context);
    }

    @PluginMethod
    public void getDomainStates(Context context) {
        system.getDomainStates(context);
    }

    @PluginMethod
    public void reprobeDomains(Context context) {
        system.reprobeDomains(context);
    }

    @PluginMethod
    public void measureLatency(Context context) {
        system.measureLatency(context);
    }

    @PluginMethod
    public void consumeLaunchRoute(Context context) {
        system.consumeLaunchRoute(context);
    }

    @PluginMethod
    public void getDiagnostics(Context context) {
        system.getDiagnostics(context);
    }

    @PluginMethod
    public void checkUpdate(Context context) {
        updates.checkUpdate(context);
    }

    @PluginMethod
    public void startUpdate(Context context) {
        updates.startUpdate(context);
    }

    @PluginMethod
    public void cancelUpdate(Context context) {
        updates.cancelUpdate(context);
    }

    @PluginMethod
    public void getUpdateState(Context context) {
        updates.getUpdateState(context);
    }

    @PluginMethod
    public void installUpdate(Context context) {
        updates.installUpdate(context);
    }

    @PluginMethod
    public void setOcrEnabled(Context context) {
        ocr.setOcrEnabled(context);
    }

    @PluginMethod
    public void pickImageAndOcr(Context context) {
        ocr.pickImageAndOcr(context);
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
    public void getFavorites(Context context) {
        api.getFavorites(context);
    }

    @PluginMethod
    public void toggleAlbumLike(Context context) {
        api.toggleAlbumLike(context);
    }

    @PluginMethod
    public void toggleAlbumFavorite(Context context) {
        api.toggleAlbumFavorite(context);
    }

    @PluginMethod
    public void manageFavoriteFolder(Context context) {
        api.manageFavoriteFolder(context);
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
    public void setCacheCapacity(Context context) {
        cache.setCacheCapacity(context);
    }

    @PluginMethod
    public void getCacheCapacityInfo(Context context) {
        cache.getCacheCapacityInfo(context);
    }

    @PluginMethod
    public void getImageCacheContents(Context context) {
        cache.getImageCacheContents(context);
    }

    @PluginMethod
    public void clearImageCache(Context context) {
        cache.clearImageCache(context);
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
    public void autoLogin(Context context) {
        auth.autoLogin(context);
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
    public void getDownloadPublic(Context context) {
        settings.getDownloadPublic(context);
    }

    @PluginMethod
    public void setDownloadPublic(Context context) {
        settings.setDownloadPublic(context);
    }

    @PluginMethod
    public void getExportPreferences(Context context) {
        settings.getExportPreferences(context);
    }

    @PluginMethod
    public void setExportFolder(Context context) {
        settings.setExportFolder(context);
    }

    @PluginMethod
    public void setExportDirectoryTemplate(Context context) {
        settings.setExportDirectoryTemplate(context);
    }

    @PluginMethod
    public void setExportFileNameTemplate(Context context) {
        settings.setExportFileNameTemplate(context);
    }

    @PluginMethod
    public void setExportLastFormat(Context context) {
        settings.setExportLastFormat(context);
    }

    @PluginMethod
    public void pickFolder(Context context) {
        files.pickFolder(context);
    }

    @PluginMethod
    public void getDefaultFolder(Context context) {
        files.getDefaultFolder(context);
    }

    @PluginMethod
    public void checkFilesExist(Context context) {
        files.checkFilesExist(context);
    }

    @PluginMethod
    public void openFile(Context context) {
        files.openFile(context);
    }

    @PluginMethod
    public void openContainingFolder(Context context) {
        files.openContainingFolder(context);
    }

    @PluginMethod
    public void scanImportableFiles(Context context) {
        files.scanImportableFiles(context);
    }

    @PluginMethod
    public void downloadChapter(Context context) {
        downloads.downloadChapter(context);
    }

    @PluginMethod
    public void getDownloadTasks(Context context) {
        downloads.getDownloadTasks(context);
    }

    @PluginMethod
    public void cancelDownload(Context context) {
        downloads.cancelDownload(context);
    }

    @PluginMethod
    public void pauseDownload(Context context) {
        downloads.pauseDownload(context);
    }

    @PluginMethod
    public void resumeDownload(Context context) {
        downloads.resumeDownload(context);
    }

    @PluginMethod
    public void deleteDownloaded(Context context) {
        downloads.deleteDownloaded(context);
    }

    @PluginMethod
    public void getDownloadedPhoto(Context context) {
        downloads.getDownloadedPhoto(context);
    }

    @PluginMethod
    public void exportBatch(Context context) {
        pdfs.exportBatch(context);
    }

    @PluginMethod
    public void getExportTasks(Context context) {
        pdfs.getExportTasks(context);
    }

    @PluginMethod
    public void getExportTask(Context context) {
        pdfs.getExportTask(context);
    }

    @PluginMethod
    public void cancelExport(Context context) {
        pdfs.cancelExport(context);
    }

    @PluginMethod
    public void retryExport(Context context) {
        pdfs.retryExport(context);
    }

    @PluginMethod
    public void deleteExportTask(Context context) {
        pdfs.deleteExportTask(context);
    }

    @PluginMethod
    public void importLocalFiles(Context context) {
        pdfs.importLocalFiles(context);
    }

    @PluginMethod
    public void getImportedLocalFiles(Context context) {
        pdfs.getImportedLocalFiles(context);
    }

    @PluginMethod
    public void getLocalFiles(Context context) {
        pdfs.getLocalFiles(context);
    }

    @PluginMethod
    public void refreshLocalFileAvailability(Context context) {
        pdfs.refreshLocalFileAvailability(context);
    }

    @PluginMethod
    public void inspectLocalFileForDeletion(Context context) {
        pdfs.inspectLocalFileForDeletion(context);
    }

    @PluginMethod
    public void verifyLocalFile(Context context) {
        pdfs.verifyLocalFile(context);
    }

    @PluginMethod
    public void removeLocalFileFromLibrary(Context context) {
        pdfs.removeLocalFileFromLibrary(context);
    }

    @PluginMethod
    public void deleteLocalFile(Context context) {
        pdfs.deleteLocalFile(context);
    }

    @PluginMethod
    public void deleteImportedLocalFile(Context context) {
        pdfs.deleteImportedLocalFile(context);
    }

    @PluginMethod
    public void getLocalFileManagementState(Context context) {
        pdfs.getLocalFileManagementState(context);
    }

    @PluginMethod
    public void acknowledgeLocalFileDatabaseReset(Context context) {
        pdfs.acknowledgeLocalFileDatabaseReset(context);
    }

    @PluginMethod
    public void updateLocalEpisodeType(Context context) {
        pdfs.updateLocalEpisodeType(context);
    }

    @PluginMethod
    public void openLocalFile(Context context) {
        pdfs.openLocalFile(context);
    }

    @PluginMethod
    public void openLocalFileFolder(Context context) {
        pdfs.openLocalFileFolder(context);
    }

    @PluginMethod
    public void getPdfInfo(Context context) {
        pdfs.getPdfInfo(context);
    }

    @PluginMethod
    public void renderPdfPage(Context context) {
        pdfs.renderPdfPage(context);
    }

    @PluginMethod
    public void getCbzInfo(Context context) {
        pdfs.getCbzInfo(context);
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

    @PluginMethod
    public void getParseHistory(Context context) {
        history.getParseHistory(context);
    }

    @PluginMethod
    public void addParseHistory(Context context) {
        history.addParseHistory(context);
    }

    @PluginMethod
    public void clearParseHistory(Context context) {
        history.clearParseHistory(context);
    }

    @PluginMethod
    public void deleteParseItem(Context context) {
        history.deleteParseItem(context);
    }

    @PluginMethod
    public void getOfflineFolders(Context context) {
        favorites.getOfflineFolders(context);
    }

    @PluginMethod
    public void createOfflineFolder(Context context) {
        favorites.createOfflineFolder(context);
    }

    @PluginMethod
    public void renameOfflineFolder(Context context) {
        favorites.renameOfflineFolder(context);
    }

    @PluginMethod
    public void deleteOfflineFolder(Context context) {
        favorites.deleteOfflineFolder(context);
    }

    @PluginMethod
    public void addOfflineFavorite(Context context) {
        favorites.addOfflineFavorite(context);
    }

    @PluginMethod
    public void removeOfflineFavorite(Context context) {
        favorites.removeOfflineFavorite(context);
    }

    @PluginMethod
    public void getOfflineFavorites(Context context) {
        favorites.getOfflineFavorites(context);
    }

    @PluginMethod
    public void getAllOfflineFavorites(Context context) {
        favorites.getAllOfflineFavorites(context);
    }

    @PluginMethod
    public void getOfflineFavoritesTotalCount(Context context) {
        favorites.getOfflineFavoritesTotalCount(context);
    }

    @PluginMethod
    public void getAllOfflineFavoritesMerged(Context context) {
        favorites.getAllOfflineFavoritesMerged(context);
    }

    @PluginMethod
    public void moveAllOfflineFavorites(Context context) {
        favorites.moveAllOfflineFavorites(context);
    }

    @PluginMethod
    public void copyOfflineFolder(Context context) {
        favorites.copyOfflineFolder(context);
    }

    @PluginMethod
    public void addOfflineFavoritesBatch(Context context) {
        favorites.addOfflineFavoritesBatch(context);
    }

    @PluginMethod
    public void mergeOfflineAllToFolder(Context context) {
        favorites.mergeOfflineAllToFolder(context);
    }

    @PluginMethod
    public void saveOfflineBackup(Context context) {
        favorites.saveOfflineBackup(context);
    }

    @PluginMethod
    public void loadOfflineBackup(Context context) {
        favorites.loadOfflineBackup(context);
    }

    @PluginMethod
    public void deleteOfflineBackup(Context context) {
        favorites.deleteOfflineBackup(context);
    }

    @PluginMethod
    public void listOfflineBackupKeys(Context context) {
        favorites.listOfflineBackupKeys(context);
    }
}
