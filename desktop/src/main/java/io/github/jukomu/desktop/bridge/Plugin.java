package io.github.jukomu.desktop.bridge;

import io.github.jukomu.desktop.bridge.handler.ApiPluginHandler;
import io.github.jukomu.desktop.bridge.handler.AuthPluginHandler;
import io.github.jukomu.desktop.bridge.handler.CachePluginHandler;
import io.github.jukomu.desktop.bridge.handler.DownloadPluginHandler;
import io.github.jukomu.desktop.bridge.handler.HistoryPluginHandler;
import io.github.jukomu.desktop.bridge.handler.FilePluginHandler;
import io.github.jukomu.desktop.bridge.handler.OfflineFavoritePluginHandler;
import io.github.jukomu.desktop.bridge.handler.PdfPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SettingsPluginHandler;
import io.github.jukomu.desktop.bridge.handler.SystemPluginHandler;
import io.javalin.http.Context;

/** 本地 HTTP bridge 的方法入口。 */
public final class Plugin {
    private final ApiPluginHandler api;
    private final AuthPluginHandler auth;
    private final CachePluginHandler cache;
    private final SettingsPluginHandler settings;
    private final HistoryPluginHandler history;
    private final OfflineFavoritePluginHandler favorites;
    private final FilePluginHandler files;
    private final DownloadPluginHandler downloads;
    private final PdfPluginHandler pdfs;
    private final SystemPluginHandler system;

    public Plugin(ApiPluginHandler api, AuthPluginHandler auth,
                  CachePluginHandler cache,
                  SettingsPluginHandler settings, HistoryPluginHandler history,
                  OfflineFavoritePluginHandler favorites,
                  FilePluginHandler files, DownloadPluginHandler downloads,
                  PdfPluginHandler pdfs,
                  SystemPluginHandler system) {
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
    public void getPdfExportPreferences(Context context) {
        settings.getPdfExportPreferences(context);
    }

    @PluginMethod
    public void setPdfExportFolder(Context context) {
        settings.setPdfExportFolder(context);
    }

    @PluginMethod
    public void setPdfExportDirectoryTemplate(Context context) {
        settings.setPdfExportDirectoryTemplate(context);
    }

    @PluginMethod
    public void setPdfExportFileNameTemplate(Context context) {
        settings.setPdfExportFileNameTemplate(context);
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
    public void scanPdfFiles(Context context) {
        files.scanPdfFiles(context);
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
    public void exportPdfBatch(Context context) {
        pdfs.exportPdfBatch(context);
    }

    @PluginMethod
    public void getPdfExportTasks(Context context) {
        pdfs.getPdfExportTasks(context);
    }

    @PluginMethod
    public void getPdfExportTask(Context context) {
        pdfs.getPdfExportTask(context);
    }

    @PluginMethod
    public void cancelPdfExport(Context context) {
        pdfs.cancelPdfExport(context);
    }

    @PluginMethod
    public void retryPdfExport(Context context) {
        pdfs.retryPdfExport(context);
    }

    @PluginMethod
    public void deletePdfExportTask(Context context) {
        pdfs.deletePdfExportTask(context);
    }

    @PluginMethod
    public void importPdfs(Context context) {
        pdfs.importPdfs(context);
    }

    @PluginMethod
    public void getImportedPdfs(Context context) {
        pdfs.getImportedPdfs(context);
    }

    @PluginMethod
    public void getPdfFiles(Context context) {
        pdfs.getPdfFiles(context);
    }

    @PluginMethod
    public void refreshPdfFileAvailability(Context context) {
        pdfs.refreshPdfFileAvailability(context);
    }

    @PluginMethod
    public void inspectPdfFileForDeletion(Context context) {
        pdfs.inspectPdfFileForDeletion(context);
    }

    @PluginMethod
    public void verifyPdfFile(Context context) {
        pdfs.verifyPdfFile(context);
    }

    @PluginMethod
    public void removePdfFromLibrary(Context context) {
        pdfs.removePdfFromLibrary(context);
    }

    @PluginMethod
    public void deletePdfFile(Context context) {
        pdfs.deletePdfFile(context);
    }

    @PluginMethod
    public void deleteImportedPdf(Context context) {
        pdfs.deleteImportedPdf(context);
    }

    @PluginMethod
    public void getPdfManagementState(Context context) {
        pdfs.getPdfManagementState(context);
    }

    @PluginMethod
    public void acknowledgePdfDatabaseReset(Context context) {
        pdfs.acknowledgePdfDatabaseReset(context);
    }

    @PluginMethod
    public void updateLocalEpisodeType(Context context) {
        pdfs.updateLocalEpisodeType(context);
    }

    @PluginMethod
    public void openPdf(Context context) {
        pdfs.openPdf(context);
    }

    @PluginMethod
    public void openPdfFolder(Context context) {
        pdfs.openPdfFolder(context);
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
