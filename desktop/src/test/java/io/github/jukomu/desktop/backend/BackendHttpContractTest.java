package io.github.jukomu.desktop.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.jukomu.desktop.bridge.Plugin;
import io.github.jukomu.desktop.bridge.PluginMethod;
import io.github.jukomu.desktop.data.Database;
import io.github.jukomu.desktop.data.Paths;
import io.github.jukomu.desktop.feature.auth.CredentialStore;
import io.github.jukomu.desktop.feature.auth.LoginCredentials;
import io.github.jukomu.desktop.feature.files.FileReferences;
import io.github.jukomu.desktop.feature.files.FileService;
import io.github.jukomu.desktop.feature.network.NetworkService;
import io.github.jukomu.jmcomic.api.client.JmClient;
import io.github.jukomu.jmcomic.api.client.JmDownloadClient;
import io.github.jukomu.jmcomic.api.download.DownloadProgress;
import io.github.jukomu.jmcomic.api.download.IDownloadManager;
import io.github.jukomu.jmcomic.api.download.enums.TaskState;
import io.github.jukomu.jmcomic.api.download.task.BaseDownloadTask;
import io.github.jukomu.jmcomic.api.enums.FavoriteFolderType;
import io.github.jukomu.jmcomic.api.model.FavoriteQuery;
import io.github.jukomu.jmcomic.api.model.ForumQuery;
import io.github.jukomu.jmcomic.api.model.JmAlbum;
import io.github.jukomu.jmcomic.api.model.JmAlbumMeta;
import io.github.jukomu.jmcomic.api.model.JmCategoryMeta;
import io.github.jukomu.jmcomic.api.model.JmComment;
import io.github.jukomu.jmcomic.api.model.JmCommentList;
import io.github.jukomu.jmcomic.api.model.JmFavoriteFolderResult;
import io.github.jukomu.jmcomic.api.model.JmFavoritePage;
import io.github.jukomu.jmcomic.api.model.JmImage;
import io.github.jukomu.jmcomic.api.model.JmPhoto;
import io.github.jukomu.jmcomic.api.model.JmPhotoMeta;
import io.github.jukomu.jmcomic.api.model.JmSearchPage;
import io.github.jukomu.jmcomic.api.model.JmUserInfo;
import io.github.jukomu.jmcomic.api.model.JmUserProfile;
import io.github.jukomu.jmcomic.api.model.SearchQuery;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendHttpContractTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WEBP_FIXTURE_BASE64 =
            "UklGRioAAABXRUJQVlA4TB0AAAAvV8JKAAcQ0f/+BwQkSf//hxH9z/jPf/7zn/+fBwA=";
    private static final String[] HISTORY_GROUPS = {
            "today", "yesterday", "thisWeek", "thisMonth",
            "lastThreeMonths", "lastSixMonths", "thisYear", "earlier"
    };

    @Test
    void servesEveryRegisteredMethodAndResourceContract() throws Exception {
        FakeClient fake = new FakeClient(webpBytes());
        MemoryCredentialStore credentials = new MemoryCredentialStore();
        Set<String> requestedMethods = new LinkedHashSet<>();
        HttpClient http = HttpClient.newHttpClient();

        try (Backend backend = backend(fake, credentials)) {
            backend.start();
            URI base = URI.create("http://127.0.0.1:" + backend.port());

            assertOk(post(http, base, requestedMethods, "getInitStatus", "{}"));
            assertTrue(body(post(
                    http, base, requestedMethods, "consumeLaunchRoute", "{}")).isEmpty());
            ObjectNode domainStates = body(post(
                    http, base, requestedMethods, "getDomainStates", "{}"));
            ObjectNode latency = body(post(
                    http, base, requestedMethods, "measureLatency", "{}"));
            assertOk(post(http, base, requestedMethods, "reprobeDomains", "{}"));
            ObjectNode search = body(post(http, base, requestedMethods, "search",
                    "{\"keyword\":\"needle\",\"category\":\"0\",\"orderBy\":\"mr\","
                            + "\"time\":\"a\",\"searchMainTag\":0,\"page\":2}"));
            ObjectNode categories = body(post(http, base, requestedMethods, "categories", "{}"));
            ObjectNode album = body(post(http, base, requestedMethods, "getAlbum",
                    "{\"id\":\"album-1\"}"));
            ObjectNode photo = body(post(http, base, requestedMethods, "getPhoto",
                    "{\"id\":\"photo-1\"}"));
            ObjectNode comments = body(post(http, base, requestedMethods, "getComments",
                    "{\"albumId\":\"album-1\",\"page\":2}"));
            ObjectNode favorites = body(post(http, base, requestedMethods, "getFavorites",
                    "{\"folderId\":\"7\",\"page\":2}"));
            assertOk(post(http, base, requestedMethods, "toggleAlbumLike",
                    "{\"id\":\"album-1\"}"));
            assertOk(post(http, base, requestedMethods, "toggleAlbumFavorite",
                    "{\"id\":\"album-1\",\"folderId\":\"7\"}"));
            ObjectNode managedFolder = body(post(http, base, requestedMethods,
                    "manageFavoriteFolder",
                    "{\"type\":\"move\",\"folderId\":\"7\","
                            + "\"albumId\":\"album-1\"}"));

            HttpResponse<InputStream> eventResponse = http.sendAsync(
                    HttpRequest.newBuilder(base.resolve("/events"))
                            .header("Accept", "text/event-stream")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream()).get(5, TimeUnit.SECONDS);
            assertEquals(200, eventResponse.statusCode());
            CompletableFuture<NamedEvent> event = CompletableFuture.supplyAsync(
                    () -> readEvent(eventResponse.body()));

            String imageRequest = "{\"photoId\":\"photo-1\",\"type\":\"image\","
                    + "\"images\":[{\"scrambleId\":\"1\",\"filename\":\"001.webp\","
                    + "\"url\":\"https://example.invalid/001.webp\",\"queryParams\":\"\","
                    + "\"sortOrder\":1}],\"replacePending\":false}";
            ObjectNode preload = body(post(http, base, requestedMethods, "preloadImages", imageRequest));
            NamedEvent imageReady = event.get(5, TimeUnit.SECONDS);
            assertEquals("imageReady", imageReady.name());
            assertEquals("photo-1", imageReady.data().path("photoId").asText());
            assertEquals(1, imageReady.data().path("sortOrder").asInt());
            assertEquals(1, preload.path("pending").get(0).asInt());

            assertOk(post(http, base, requestedMethods, "retryImage",
                    "{\"photoId\":\"photo-1\",\"image\":{\"scrambleId\":\"1\","
                            + "\"filename\":\"001.webp\",\"url\":\"https://example.invalid/001.webp\","
                            + "\"queryParams\":\"\",\"sortOrder\":1}}"));
            ObjectNode login = body(post(http, base, requestedMethods, "login",
                    "{\"username\":\"alice\",\"password\":\"secret\"}"));
            ObjectNode autoLogin = body(post(http, base, requestedMethods, "autoLogin", "{}"));
            ObjectNode loginState = body(post(http, base, requestedMethods, "checkLoginState", "{}"));
            ObjectNode profile = body(post(http, base, requestedMethods, "getUserProfile",
                    "{\"uid\":\"user-1\"}"));
            assertOk(post(http, base, requestedMethods, "logout", "{}"));
            ObjectNode loggedOutState = body(post(
                    http, base, requestedMethods, "checkLoginState", "{}"));

            assertOk(post(http, base, requestedMethods, "setPreloadConcurrency", "{\"n\":4}"));
            assertOk(post(http, base, requestedMethods, "setDownloadConcurrency", "{\"n\":5}"));
            assertOk(post(http, base, requestedMethods, "setReaderPreloadPages", "{\"n\":10}"));
            assertOk(post(http, base, requestedMethods, "setReaderDisplayMode",
                    "{\"mode\":\"horizontal\"}"));
            assertOk(post(http, base, requestedMethods, "setReaderAutoShowToolbarAtEnd",
                    "{\"enabled\":false}"));
            assertOk(post(http, base, requestedMethods, "setOcrEnabled",
                    "{\"enabled\":true}"));
            ObjectNode settings = body(post(http, base, requestedMethods, "getAllSettings", "{}"));
            assertTrue(settings.path("ocrEnabled").asBoolean());
            assertOk(post(http, base, requestedMethods, "setDownloadPublic", "{\"open\":false}"));
            ObjectNode downloadLocation = body(post(
                    http, base, requestedMethods, "getDownloadPublic", "{}"));

            ObjectNode pickedFolder = body(post(http, base, requestedMethods, "pickFolder",
                    "{\"purpose\":\"pdf-export\"}"));
            String folderRef = pickedFolder.path("ref").asText();
            ObjectNode defaultFolder = body(post(http, base, requestedMethods, "getDefaultFolder",
                    "{\"purpose\":\"download\"}"));
            ObjectNode scannedFiles = body(post(http, base, requestedMethods, "scanPdfFiles",
                    MAPPER.writeValueAsString(Map.of("folder", folderRef))));
            String fileRef = scannedFiles.path("files").get(0).path("ref").asText();
            ObjectNode existingFiles = body(post(http, base, requestedMethods, "checkFilesExist",
                    MAPPER.writeValueAsString(Map.of("files", List.of(fileRef)))));
            assertOk(post(http, base, requestedMethods, "openFile",
                    MAPPER.writeValueAsString(Map.of("file", fileRef))));
            assertOk(post(http, base, requestedMethods, "openContainingFolder",
                    MAPPER.writeValueAsString(Map.of("file", fileRef))));

            String importBody = MAPPER.writeValueAsString(Map.of("items", List.of(Map.ofEntries(
                    Map.entry("fileRef", fileRef),
                    Map.entry("displayPath",
                            scannedFiles.path("files").get(0).path("displayPath").asText()),
                    Map.entry("fileName", "sample.pdf"),
                    Map.entry("albumId", "album-1"),
                    Map.entry("albumTitle", "Album"),
                    Map.entry("coverUrl", ""),
                    Map.entry("authors", "Alice"),
                    Map.entry("chapterId", "photo-1"),
                    Map.entry("chapterTitle", "Photo"),
                    Map.entry("chapterSortOrder", 1),
                    Map.entry("isSingleEpisode", false),
                    Map.entry("folderId", "folder-1")
            ))));
            ObjectNode imported = body(post(http, base, requestedMethods, "importPdfs", importBody));
            long importedId = imported.path("results").get(0).path("id").asLong();
            ObjectNode importedFiles = body(post(
                    http, base, requestedMethods, "getImportedPdfs", "{}"));
            ObjectNode pdfFiles = body(post(http, base, requestedMethods, "getPdfFiles",
                    "{\"sourceType\":\"imported\",\"limit\":50}"));
            ObjectNode refreshedPdfs = body(post(http, base, requestedMethods,
                    "refreshPdfFileAvailability", "{\"ids\":[" + importedId + "]}"));
            ObjectNode inspectedPdf = body(post(http, base, requestedMethods,
                    "inspectPdfFileForDeletion", "{\"id\":" + importedId + "}"));
            ObjectNode verifiedPdf = body(post(http, base, requestedMethods,
                    "verifyPdfFile", "{\"id\":" + importedId + "}"));
            ObjectNode pdfManagement = body(post(http, base, requestedMethods,
                    "getPdfManagementState", "{}"));
            ObjectNode pdfReset = body(post(http, base, requestedMethods,
                    "acknowledgePdfDatabaseReset", "{}"));
            assertOk(post(http, base, requestedMethods, "updateLocalEpisodeType",
                    "{\"albumId\":\"album-1\",\"isSingleEpisode\":true}"));
            assertOk(post(http, base, requestedMethods, "openPdf",
                    MAPPER.writeValueAsString(Map.of("fileRef", fileRef))));
            assertOk(post(http, base, requestedMethods, "openPdfFolder",
                    MAPPER.writeValueAsString(Map.of("fileRef", fileRef))));
            ObjectNode pdfInfo = body(post(http, base, requestedMethods, "getPdfInfo",
                    MAPPER.writeValueAsString(Map.of("fileRef", fileRef))));
            ObjectNode renderedPdfPage = body(post(http, base, requestedMethods, "renderPdfPage",
                    MAPPER.writeValueAsString(Map.of(
                            "fileRef", fileRef, "page", 1, "targetWidth", 720))));
            HttpResponse<byte[]> pdfPage = getBytes(
                    http, base.resolve(renderedPdfPage.path("resourceUrl").asText()));
            String encodedPdf = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    fileRef.getBytes(StandardCharsets.UTF_8));
            HttpResponse<byte[]> originalPdf = getBytes(http, base.resolve("/pdf/" + encodedPdf));
            String missingRef = FileReferences.fileRef(
                    FileReferences.parseFile(fileRef).resolveSibling("missing.pdf"));
            String encodedMissing = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    missingRef.getBytes(StandardCharsets.UTF_8));
            HttpResponse<byte[]> missingPdf = getBytes(
                    http, base.resolve("/pdf/" + encodedMissing));
            Path invalidPdfPath = FileReferences.parseFile(fileRef).resolveSibling("invalid.pdf");
            Files.writeString(invalidPdfPath, "not a pdf");
            String invalidPdfRef = FileReferences.fileRef(invalidPdfPath);
            String encodedInvalid = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    invalidPdfRef.getBytes(StandardCharsets.UTF_8));
            HttpResponse<byte[]> invalidPdfContent = getBytes(
                    http, base.resolve("/pdf/" + encodedInvalid));

            assertOk(post(http, base, requestedMethods, "removePdfFromLibrary",
                    "{\"id\":" + importedId + "}"));
            long secondPdfId = body(post(http, base, requestedMethods, "importPdfs", importBody))
                    .path("results").get(0).path("id").asLong();
            assertOk(post(http, base, requestedMethods, "deleteImportedPdf",
                    "{\"id\":" + secondPdfId + "}"));
            long thirdPdfId = body(post(http, base, requestedMethods, "importPdfs", importBody))
                    .path("results").get(0).path("id").asLong();
            ObjectNode deletedPdf = body(post(http, base, requestedMethods, "deletePdfFile",
                    "{\"id\":" + thirdPdfId + "}"));

            assertOk(post(http, base, requestedMethods, "setPdfExportFolder",
                    MAPPER.writeValueAsString(Map.of("folder", Map.of(
                            "folderRef", folderRef,
                            "displayPath", pickedFolder.path("displayPath").asText())))));
            assertOk(post(http, base, requestedMethods, "setPdfExportDirectoryTemplate",
                    "{\"value\":\"{author}/{id}\"}"));
            assertOk(post(http, base, requestedMethods, "setPdfExportFileNameTemplate",
                    "{\"value\":\"{title}\"}"));
            ObjectNode pdfPreferences = body(post(http, base, requestedMethods,
                    "getPdfExportPreferences", "{}"));

            ObjectNode submission = body(post(http, base, requestedMethods, "downloadChapter",
                    "{\"albumId\":\"album-1\",\"chapterId\":\"download-photo\","
                            + "\"albumTitle\":\"Album\",\"chapterTitle\":\"Downloaded\","
                            + "\"coverUrl\":\"https://cover.invalid/album-1.jpg\"}"));
            assertEquals("album-1_download-photo", submission.path("taskId").asText());
            ObjectNode downloads = waitForDownload(
                    http, base, requestedMethods, "album-1_download-photo", "completed");
            ObjectNode downloadedPhoto = body(post(http, base, requestedMethods,
                    "getDownloadedPhoto",
                    "{\"albumId\":\"album-1\",\"chapterId\":\"download-photo\"}"));
            HttpResponse<byte[]> downloadedImage = getBytes(
                    http, base.resolve("/image/download-photo/1"));
            String exportBody = MAPPER.writeValueAsString(Map.of("tasks", List.of(Map.ofEntries(
                    Map.entry("mode", "chapter"),
                    Map.entry("albumId", "album-1"),
                    Map.entry("albumTitle", "Album"),
                    Map.entry("coverUrl", ""),
                    Map.entry("authors", "Alice"),
                    Map.entry("isSingleEpisode", false),
                    Map.entry("chapterId", "download-photo"),
                    Map.entry("chapterTitle", "Downloaded"),
                    Map.entry("target", Map.of("folder", folderRef,
                            "relativePath", "exported.pdf")),
                    Map.entry("displayPath", pickedFolder.path("displayPath").asText()
                            + "/exported.pdf"),
                    Map.entry("useOriginal", true),
                    Map.entry("compressionRatio", 1D),
                    Map.entry("splitPages", 0),
                    Map.entry("allowOverwrite", false)
            ))));
            ObjectNode exported = body(post(http, base, requestedMethods,
                    "exportPdfBatch", exportBody));
            String exportId = exported.path("tasks").get(0).path("exportId").asText();
            ObjectNode exportTask = waitForPdfExport(
                    http, base, requestedMethods, exportId, "completed");
            ObjectNode exportTasks = body(post(http, base, requestedMethods,
                    "getPdfExportTasks", "{\"limit\":50}"));
            ObjectNode cancelledCompletedExport = body(post(http, base, requestedMethods,
                    "cancelPdfExport", "{\"exportId\":\"" + exportId + "\"}"));
            ObjectNode retriedExport = body(post(http, base, requestedMethods,
                    "retryPdfExport", "{\"exportId\":\"" + exportId
                            + "\",\"allowOverwrite\":true}"));
            ObjectNode completedRetry = waitForPdfExport(
                    http, base, requestedMethods, exportId, "completed");
            assertOk(post(http, base, requestedMethods, "deletePdfExportTask",
                    "{\"exportId\":\"" + exportId + "\"}"));
            assertOk(post(http, base, requestedMethods, "deleteDownloaded",
                    "{\"albumId\":\"album-1\",\"chapterId\":\"download-photo\"}"));
            assertOk(post(http, base, requestedMethods, "cancelDownload",
                    "{\"taskId\":\"missing\"}"));
            assertEquals(404, post(http, base, requestedMethods, "pauseDownload",
                    "{\"taskId\":\"missing\"}").statusCode());
            assertEquals(404, post(http, base, requestedMethods, "resumeDownload",
                    "{\"taskId\":\"missing\"}").statusCode());

            assertOk(post(http, base, requestedMethods, "recordBrowse",
                    "{\"albumId\":\"album-1\",\"albumTitle\":\"Album\","
                            + "\"coverUrl\":\"https://cover.invalid/album-1.jpg\","
                            + "\"authors\":\"Alice\",\"chapterId\":\"photo-1\","
                            + "\"chapterTitle\":\"Photo\"}"));
            ObjectNode history = body(post(http, base, requestedMethods, "getBrowseHistory",
                    "{\"limit\":10,\"offset\":0}"));
            ObjectNode overviewRequest = MAPPER.createObjectNode();
            ArrayNode ranges = overviewRequest.putArray("ranges");
            for (String group : HISTORY_GROUPS) ranges.addObject().put("key", group);
            ObjectNode overview = body(post(http, base, requestedMethods, "getBrowseHistoryOverview",
                    MAPPER.writeValueAsString(overviewRequest)));
            long historyId = history.path("items").get(0).path("id").asLong();
            assertOk(post(http, base, requestedMethods, "deleteBrowseItem",
                    "{\"id\":" + historyId + "}"));
            assertOk(post(http, base, requestedMethods, "clearBrowseHistory", "{}"));

            assertOk(post(http, base, requestedMethods, "addParseHistory",
                    "{\"text\":\"  First  \",\"mode\":\"batch-mode\"}"));
            assertOk(post(http, base, requestedMethods, "addParseHistory",
                    "{\"text\":\"second\"}"));
            ObjectNode parseHistory = body(post(http, base, requestedMethods, "getParseHistory",
                    "{\"limit\":10,\"offset\":0}"));
            long parseHistoryId = parseHistory.path("items").get(0).path("id").asLong();
            assertOk(post(http, base, requestedMethods, "deleteParseItem",
                    "{\"id\":" + parseHistoryId + "}"));
            assertOk(post(http, base, requestedMethods, "clearParseHistory", "{}"));

            ObjectNode offlineSource = body(post(http, base, requestedMethods,
                    "createOfflineFolder", "{\"name\":\"Offline Source\"}"));
            ObjectNode offlineTarget = body(post(http, base, requestedMethods,
                    "createOfflineFolder", "{\"name\":\"Offline Target\"}"));
            String offlineSourceId = offlineSource.path("folderId").asText();
            String offlineTargetId = offlineTarget.path("folderId").asText();
            String offlineItem = "{\"id\":\"offline-album-1\",\"title\":\"Offline One\","
                    + "\"coverUrl\":\"offline-cover.jpg\",\"authors\":[\"Alice\"],"
                    + "\"tags\":[\"offline\"]}";
            assertOk(post(http, base, requestedMethods, "addOfflineFavorite",
                    "{\"folderId\":\"" + offlineSourceId + "\",\"item\":"
                            + offlineItem + "}"));
            ObjectNode offlineBatch = body(post(http, base, requestedMethods,
                    "addOfflineFavoritesBatch",
                    "{\"folderId\":\"" + offlineSourceId + "\",\"items\":["
                            + "{\"id\":\"offline-album-2\",\"title\":\"Offline Two\","
                            + "\"coverUrl\":\"\",\"authors\":[],\"tags\":[]}] }"));
            ObjectNode offlineFolders = body(post(http, base, requestedMethods,
                    "getOfflineFolders", "{}"));
            ObjectNode offlinePage = body(post(http, base, requestedMethods,
                    "getOfflineFavorites",
                    "{\"folderId\":\"" + offlineSourceId
                            + "\",\"page\":1,\"pageSize\":1}"));
            ObjectNode offlineItems = body(post(http, base, requestedMethods,
                    "getAllOfflineFavorites",
                    "{\"folderId\":\"" + offlineSourceId + "\"}"));
            ObjectNode offlineCount = body(post(http, base, requestedMethods,
                    "getOfflineFavoritesTotalCount", "{}"));
            ObjectNode offlineMerged = body(post(http, base, requestedMethods,
                    "getAllOfflineFavoritesMerged", "{}"));
            ObjectNode offlineCopy = body(post(http, base, requestedMethods,
                    "copyOfflineFolder",
                    "{\"sourceId\":\"" + offlineSourceId + "\",\"name\":\"Offline Copy\"}"));
            assertOk(post(http, base, requestedMethods, "moveAllOfflineFavorites",
                    "{\"sourceId\":\"" + offlineSourceId + "\",\"targetId\":\""
                            + offlineTargetId + "\"}"));
            assertOk(post(http, base, requestedMethods, "mergeOfflineAllToFolder",
                    "{\"targetId\":\"" + offlineTargetId + "\"}"));
            assertOk(post(http, base, requestedMethods, "saveOfflineBackup",
                    "{\"key\":\"offline-backup\",\"items\":[" + offlineItem + "]}"));
            ObjectNode offlineBackup = body(post(http, base, requestedMethods,
                    "loadOfflineBackup", "{\"key\":\"offline-backup\"}"));
            ObjectNode offlineBackupKeys = body(post(http, base, requestedMethods,
                    "listOfflineBackupKeys", "{}"));
            assertOk(post(http, base, requestedMethods, "deleteOfflineBackup",
                    "{\"key\":\"offline-backup\"}"));
            assertOk(post(http, base, requestedMethods, "removeOfflineFavorite",
                    "{\"folderId\":\"" + offlineTargetId
                            + "\",\"albumId\":\"offline-album-1\"}"));
            assertOk(post(http, base, requestedMethods, "renameOfflineFolder",
                    "{\"folderId\":\"" + offlineTargetId
                            + "\",\"name\":\"Offline Renamed\"}"));
            assertOk(post(http, base, requestedMethods, "deleteOfflineFolder",
                    "{\"folderId\":\"" + offlineSourceId + "\"}"));

            HttpResponse<byte[]> image = getBytes(http, base.resolve("/image/photo-1/1"));
            HttpResponse<byte[]> thumb = getBytes(http, base.resolve("/thumb/photo-1/1"));
            BufferedImage thumbnail = ImageIO.read(new ByteArrayInputStream(thumb.body()));
            ObjectNode cacheCapacity = body(post(
                    http, base, requestedMethods, "getCacheCapacityInfo", "{}"));
            ObjectNode cacheContents = body(post(
                    http, base, requestedMethods, "getImageCacheContents", "{}"));
            ObjectNode updatedCacheCapacity = body(post(
                    http, base, requestedMethods, "setCacheCapacity", "{\"mb\":64}"));
            ObjectNode cacheSettings = body(post(
                    http, base, requestedMethods, "getAllSettings", "{}"));
            ObjectNode diagnostics = body(post(
                    http, base, requestedMethods, "getDiagnostics", "{}"));
            assertOk(post(http, base, requestedMethods, "clearImageCache", "{}"));
            ObjectNode clearedCacheContents = body(post(
                    http, base, requestedMethods, "getImageCacheContents", "{}"));

            assertEquals(2, search.path("currentPage").asInt());
            assertEquals("album-1", search.path("content").get(0).path("id").asText());
            assertEquals("album-1", categories.path("content").get(0).path("id").asText());
            assertEquals("https://cover.invalid/album-1.jpg", album.path("image").asText());
            assertTrue(album.has("isSingleEpisode"));
            assertEquals("photo-1", photo.path("id").asText());
            assertEquals(6, photo.path("images").get(0).size());
            assertEquals("comment-1", comments.path("list").get(0).path("commentId").asText());
            assertEquals("Folder 7", favorites.path("folderName").asText());
            assertEquals("7", favorites.path("folderId").asText());
            assertEquals(2, favorites.path("currentPage").asInt());
            assertEquals("album-1", favorites.path("content").get(0).path("id").asText());
            assertEquals("Reading", favorites.path("folderList").path("7").asText());
            assertEquals("ok", managedFolder.path("status").asText());
            assertEquals("moved", managedFolder.path("msg").asText());
            assertEquals("alice", login.path("username").asText());
            assertTrue(autoLogin.path("success").asBoolean());
            assertEquals("alice", autoLogin.path("userInfo").path("username").asText());
            assertTrue(loginState.path("loggedIn").asBoolean());
            assertEquals(1, loggedOutState.size());
            assertTrue(!loggedOutState.path("loggedIn").asBoolean());
            assertEquals("Alice", profile.path("nickname").asText());
            assertEquals(4, settings.path("preloadConcurrency").asInt());
            assertEquals(5, settings.path("downloadConcurrency").asInt());
            assertTrue(defaultFolder.path("ref").asText().startsWith("folder:path:"));
            assertEquals(1, existingFiles.path("existing").size());
            assertEquals(1, imported.path("imported").asInt());
            assertEquals(1, importedFiles.path("pdfs").size());
            assertEquals(1, pdfFiles.path("files").size());
            assertEquals("available",
                    refreshedPdfs.path("files").get(0).path("availability").asText());
            assertEquals("available", inspectedPdf.path("availability").asText());
            assertEquals("valid", verifiedPdf.path("verificationStatus").asText());
            assertEquals("ready", pdfManagement.path("recoveryState").asText());
            assertTrue(!pdfReset.path("acknowledged").asBoolean());
            assertEquals(1, pdfInfo.path("pageCount").asInt());
            assertEquals(200, pdfPage.statusCode());
            assertEquals("image/png", pdfPage.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(200, originalPdf.statusCode());
            assertEquals("application/pdf",
                    originalPdf.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(originalPdf.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertEquals(404, missingPdf.statusCode());
            assertEquals("file-missing",
                    missingPdf.headers().firstValue("X-JQViewer-Pdf-Error").orElseThrow());
            assertTrue(missingPdf.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            assertEquals(400, invalidPdfContent.statusCode());
            assertEquals("invalid-content",
                    invalidPdfContent.headers().firstValue("X-JQViewer-Pdf-Error").orElseThrow());
            assertTrue(invalidPdfContent.headers()
                    .firstValue("Access-Control-Allow-Origin").isEmpty());
            assertEquals("deleted", deletedPdf.path("result").asText());
            assertEquals(folderRef, pdfPreferences.path("exportFolder").path("folderRef").asText());
            assertEquals("{author}/{id}", pdfPreferences.path("directoryTemplate").asText());
            assertEquals("{title}", pdfPreferences.path("fileNameTemplate").asText());
            assertEquals("completed", downloads.path("tasks").get(0).path("status").asText());
            assertEquals("download-photo", downloadedPhoto.path("id").asText());
            assertEquals(200, downloadedImage.statusCode());
            assertEquals("image/webp",
                    downloadedImage.headers().firstValue("Content-Type").orElseThrow());
            assertTrue(exported.path("tasks").get(0).path("accepted").asBoolean());
            assertEquals("completed", exportTask.path("status").asText());
            assertTrue(exportTask.path("outputFileRef").asText().startsWith("file:path:"));
            assertEquals(1, exportTasks.path("tasks").size());
            assertEquals("completed", cancelledCompletedExport.path("status").asText());
            assertEquals("queued", retriedExport.path("status").asText());
            assertEquals("completed", completedRetry.path("status").asText());
            assertEquals(1, history.path("totalCount").asInt());
            assertEquals(1, overview.path("totalCount").asInt());
            assertEquals(2, parseHistory.path("totalCount").asInt());
            assertEquals("second", parseHistory.path("items").get(0).path("text").asText());
            assertEquals("single-mode", parseHistory.path("items").get(0).path("mode").asText());
            assertEquals("First", parseHistory.path("items").get(1).path("text").asText());
            assertEquals("batch-mode", parseHistory.path("items").get(1).path("mode").asText());
            assertFalse(offlineSourceId.isEmpty());
            assertFalse(offlineTargetId.isEmpty());
            assertEquals(1, offlineBatch.path("count").asInt());
            assertEquals(2, offlineFolders.path("folders").size());
            assertEquals(2, offlinePage.path("totalItems").asInt());
            assertEquals(2, offlinePage.path("totalPages").asInt());
            assertEquals(1, offlinePage.path("content").size());
            assertEquals(2, offlineItems.path("items").size());
            assertEquals(2, offlineCount.path("count").asInt());
            assertEquals(2, offlineMerged.path("items").size());
            assertFalse(offlineCopy.path("folderId").asText().isEmpty());
            assertEquals(1, offlineBackup.path("items").size());
            assertEquals("offline-backup", offlineBackupKeys.path("keys").get(0).asText());
            assertEquals(200, image.statusCode());
            assertEquals("image/webp", image.headers().firstValue("Content-Type").orElseThrow());
            assertEquals(200, thumb.statusCode());
            assertEquals("image/jpeg", thumb.headers().firstValue("Content-Type").orElseThrow());
            assertNotNull(thumbnail);
            assertEquals(300, thumbnail.getWidth());
            assertEquals(150, thumbnail.getHeight());
            assertEquals(256, cacheCapacity.path("requestedMb").asInt());
            assertTrue(cacheCapacity.path("effectiveMb").asInt() > 0);
            assertTrue(cacheContents.path("entries").size() >= 2);
            assertTrue(updatedCacheCapacity.path("success").asBoolean());
            assertEquals(64, updatedCacheCapacity.path("requestedMb").asInt());
            assertEquals(64, cacheSettings.path("cacheRequestedMb").asInt());
            assertTrue(diagnostics.path("generatedAt").asLong() > 0);
            assertEquals("data", diagnostics.path("paths").get(0).path("kind").asText());
            assertEquals(2, diagnostics.path("tasks").size());
            assertEquals(2, diagnostics.path("clearableResources").size());
            assertEquals(0, clearedCacheContents.path("entries").size());
            assertFalse(downloadLocation.path("downloadPublic").asBoolean());
            assertEquals(1, domainStates.path("alive").asInt());
            assertEquals(2, domainStates.path("total").asInt());
            assertFalse(domainStates.path("allDeadFallback").asBoolean());
            assertTrue(domainStates.path("domains").get(0).path("reachable").asBoolean());
            assertFalse(domainStates.path("domains").get(1).path("reachable").asBoolean());
            assertEquals(35, latency.path("results").get(0).path("latencyMs").asInt());
            assertFalse(latency.path("results").get(0).path("timedOut").asBoolean());
            assertEquals(0, latency.path("results").get(1).path("latencyMs").asInt());
            assertTrue(latency.path("results").get(1).path("timedOut").asBoolean());

            SearchQuery forwardedSearch = (SearchQuery) fake.firstArgument("search");
            ForumQuery forwardedComments = (ForumQuery) fake.firstArgument("getComments");
            FavoriteQuery forwardedFavorites = (FavoriteQuery) fake.firstArgument("getFavorites");
            List<Object> forwardedFolderManagement = fake.arguments("manageFavoriteFolder");
            assertEquals("needle", forwardedSearch.getSearchQuery());
            assertEquals(2, forwardedSearch.getPage());
            assertEquals("album-1", forwardedComments.getEntityId());
            assertEquals(2, forwardedComments.getPage());
            assertEquals(7, forwardedFavorites.getFolderId());
            assertEquals(2, forwardedFavorites.getPage());
            assertEquals("album-1", fake.firstArgument("toggleAlbumLike"));
            assertEquals(List.of("album-1", "7"), fake.arguments("toggleAlbumFavorite"));
            assertEquals(FavoriteFolderType.MOVE, forwardedFolderManagement.get(0));
            assertEquals(List.of("7", "", "album-1"),
                    forwardedFolderManagement.subList(1, 4));
            assertEquals(registeredMethods(), requestedMethods);
        }
    }

    @Test
    void preservesControlledFailuresAndPublicErrorCodes() throws Exception {
        FakeClient fake = new FakeClient(webpBytes());
        HttpClient http = HttpClient.newHttpClient();
        try (Backend backend = backend(fake)) {
            backend.start();
            URI base = URI.create("http://127.0.0.1:" + backend.port());

            fake.fail("getAlbum", new IllegalStateException("remote failed"));
            HttpResponse<String> upstream = post(http, base, new LinkedHashSet<>(), "getAlbum",
                    "{\"id\":\"album-1\"}");
            fake.fail("manageFavoriteFolder", new IllegalStateException("folder rejected"));
            HttpResponse<String> favoriteFailure = post(http, base, new LinkedHashSet<>(),
                    "manageFavoriteFolder",
                    "{\"type\":\"move\",\"folderId\":\"7\","
                            + "\"albumId\":\"album-1\"}");
            HttpResponse<String> invalidFavoriteFolder = post(http, base, new LinkedHashSet<>(),
                    "getFavorites", "{\"folderId\":\"invalid\",\"page\":1}");
            HttpResponse<String> protectedFolder = post(http, base, new LinkedHashSet<>(),
                    "manageFavoriteFolder", "{\"type\":\"del\",\"folderId\":\"0\"}");
            HttpResponse<String> malformed = post(http, base, new LinkedHashSet<>(),
                    "getAllSettings", "[]");
            HttpResponse<String> wrongType = post(http, base, new LinkedHashSet<>(),
                    "setReaderPreloadPages", "{\"n\":\"10\"}");
            HttpResponse<String> invalidCacheCapacity = post(
                    http, base, new LinkedHashSet<>(), "setCacheCapacity", "{\"mb\":63}");
            HttpResponse<String> invalidImage = http.send(
                    HttpRequest.newBuilder(base.resolve("/image/photo-1/not-a-number")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());

            assertEquals(500, upstream.statusCode());
            assertEquals("internal", json(upstream).path("code").asText());
            assertEquals("remote failed", json(upstream).path("message").asText());
            assertEquals(500, favoriteFailure.statusCode());
            assertEquals("internal", json(favoriteFailure).path("code").asText());
            assertEquals("folder rejected", json(favoriteFailure).path("message").asText());
            assertEquals(400, invalidFavoriteFolder.statusCode());
            assertEquals("folderId必须是非负整数",
                    json(invalidFavoriteFolder).path("message").asText());
            assertEquals(400, protectedFolder.statusCode());
            assertEquals("编辑或删除收藏夹时 folderId 不能为空",
                    json(protectedFolder).path("message").asText());
            assertEquals(400, malformed.statusCode());
            assertEquals("internal", json(malformed).path("code").asText());
            assertEquals(400, wrongType.statusCode());
            assertEquals("n必须是整数", json(wrongType).path("message").asText());
            assertEquals(400, invalidCacheCapacity.statusCode());
            assertEquals("mb must be between 64 and 1024",
                    json(invalidCacheCapacity).path("message").asText());
            assertEquals(400, invalidImage.statusCode());
            assertEquals("internal", json(invalidImage).path("code").asText());
        }
    }

    private static Backend backend(FakeClient fake) throws Exception {
        return backend(fake, new MemoryCredentialStore());
    }

    private static Backend backend(FakeClient fake, CredentialStore credentials) throws Exception {
        java.nio.file.Path root = Files.createTempDirectory("jq-viewer-contract-");
        Paths paths = new Paths(root.resolve("program"), root.resolve("home"), Map.of(), "Linux");
        paths.ensureDirectories();
        java.nio.file.Path samplePdf = paths.pdfDirectory().resolve("sample.pdf");
        writePdf(samplePdf);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                6, 6, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64));
        FileService files = new FileService(paths, ignored -> paths.pdfDirectory(), ignored -> {
        });
        return new Backend(paths, new Database(paths), executor, fake.client(),
                id -> "https://cover.invalid/" + id + ".jpg", files, credentials,
                fake.networkOperations());
    }

    private static void writePdf(Path target) throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(target.toFile());
        }
    }

    private static HttpResponse<String> post(
            HttpClient client,
            URI base,
            Set<String> requestedMethods,
            String method,
            String body
    ) throws Exception {
        requestedMethods.add(method);
        return client.send(HttpRequest.newBuilder(base.resolve("/api/" + method))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<byte[]> getBytes(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static ObjectNode waitForPdfExport(
            HttpClient http,
            URI base,
            Set<String> requestedMethods,
            String exportId,
            String status
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ObjectNode task;
        do {
            task = body(post(http, base, requestedMethods, "getPdfExportTask",
                    "{\"exportId\":\"" + exportId + "\"}"));
            if (status.equals(task.path("status").asText())) return task;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("PDF 导出任务未进入状态 " + status + ": " + task);
    }

    private static ObjectNode waitForDownload(
            HttpClient client,
            URI base,
            Set<String> requestedMethods,
            String taskId,
            String status
    ) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ObjectNode latest = null;
        while (System.nanoTime() < deadline) {
            latest = body(post(client, base, requestedMethods, "getDownloadTasks", "{}"));
            for (var task : latest.path("tasks")) {
                if (taskId.equals(task.path("taskId").asText())
                        && status.equals(task.path("status").asText())) {
                    return latest;
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Download did not reach " + status + ": " + latest);
    }

    private static void assertOk(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
    }

    private static ObjectNode body(HttpResponse<String> response) throws Exception {
        assertOk(response);
        return json(response);
    }

    private static ObjectNode json(HttpResponse<String> response) throws Exception {
        return (ObjectNode) MAPPER.readTree(response.body());
    }

    private static Set<String> registeredMethods() {
        Set<String> result = new LinkedHashSet<>();
        for (Method method : Plugin.class.getDeclaredMethods()) {
            // 系统选图需要真实桌面会话，由 OcrServiceTest 覆盖其非 UI 行为。
            if (method.isAnnotationPresent(PluginMethod.class)
                    && !method.getName().equals("pickImageAndOcr")) {
                result.add(method.getName());
            }
        }
        return result;
    }

    private static NamedEvent readEvent(InputStream stream) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            String name = null;
            String data = null;
            for (String line; (line = reader.readLine()) != null; ) {
                if (line.startsWith("event:")) name = line.substring("event:".length()).trim();
                if (line.startsWith("data:")) data = line.substring("data:".length()).trim();
                if (line.isEmpty() && name != null && data != null) {
                    return new NamedEvent(name, (ObjectNode) MAPPER.readTree(data));
                }
            }
            throw new AssertionError("SSE connection closed before an event was received");
        } catch (Exception exception) {
            throw new AssertionError("Unable to read SSE event", exception);
        }
    }

    private static byte[] webpBytes() {
        return Base64.getDecoder().decode(WEBP_FIXTURE_BASE64);
    }

    private record NamedEvent(String name, ObjectNode data) {
    }

    private record Invocation(String method, List<Object> arguments) {
    }

    private static final class MemoryCredentialStore implements CredentialStore {
        private LoginCredentials credentials;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public LoginCredentials load() {
            return credentials;
        }

        @Override
        public void save(String username, String password) {
            credentials = new LoginCredentials(username, password);
        }

        @Override
        public void clear() {
            credentials = null;
        }
    }

    private static final class FakeClient {
        private final byte[] imageBytes;
        private final FakeDownloadManager downloadManager = new FakeDownloadManager();
        private final ConcurrentLinkedQueue<Invocation> invocations = new ConcurrentLinkedQueue<>();
        private volatile String failedMethod;
        private volatile RuntimeException failure;

        private FakeClient(byte[] imageBytes) {
            this.imageBytes = imageBytes;
        }

        private JmClient client() {
            return (JmClient) Proxy.newProxyInstance(
                    JmClient.class.getClassLoader(),
                    new Class<?>[]{JmClient.class, JmDownloadClient.class},
                    (proxy, method, arguments) -> invoke(proxy, method, arguments));
        }

        private NetworkService.Operations networkOperations() {
            Map<String, Integer> states = new java.util.LinkedHashMap<>();
            states.put("https://fast.invalid", 35);
            states.put("https://dead.invalid", Integer.MAX_VALUE);
            Map<String, Integer> latency = new java.util.LinkedHashMap<>();
            latency.put("https://fast.invalid", 35);
            latency.put("https://dead.invalid", -1);
            return new NetworkService.Operations(() -> states, () -> latency, () -> {
            });
        }

        private Object invoke(Object proxy, Method method, Object[] arguments) {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "toString" -> "FakeJmClient";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new AssertionError(method.getName());
                };
            }
            List<Object> values = arguments == null
                    ? List.of()
                    : new ArrayList<>(Arrays.asList(arguments));
            invocations.add(new Invocation(method.getName(), values));
            if (method.getName().equals(failedMethod)) throw failure;
            return switch (method.getName()) {
                case "search", "getCategories" -> searchPage();
                case "getAlbum" -> album();
                case "getPhoto" -> photo((String) arguments[0]);
                case "getComments" -> comments();
                case "getFavorites" -> favorites((FavoriteQuery) arguments[0]);
                case "toggleAlbumLike", "toggleAlbumFavorite" -> null;
                case "manageFavoriteFolder" -> new JmFavoriteFolderResult("ok", "moved");
                case "fetchImageBytes" -> imageBytes;
                case "login" -> userInfo();
                case "logout" -> null;
                case "getUserProfile" -> userProfile();
                case "createDownloadTask" -> new ImmediateDownloadTask(
                        (JmPhoto) arguments[0], (Path) arguments[1], imageBytes, downloadManager);
                case "downloadManager" -> downloadManager;
                case "close" -> null;
                default -> throw new AssertionError("Unexpected client call: " + method.getName());
            };
        }

        private void fail(String method, RuntimeException exception) {
            failedMethod = method;
            failure = exception;
        }

        private Object firstArgument(String method) {
            return arguments(method).getFirst();
        }

        private List<Object> arguments(String method) {
            return invocations.stream()
                    .filter(invocation -> invocation.method().equals(method))
                    .findFirst()
                    .orElseThrow()
                    .arguments();
        }

        private static JmSearchPage searchPage() {
            return new JmSearchPage(2, 1, 4, List.of(albumMeta()));
        }

        private static JmAlbumMeta albumMeta() {
            return new JmAlbumMeta("album-1", "Album", List.of("Alice"), List.of("tag"),
                    "Description", "cover.jpg", new JmCategoryMeta("0", "All"), null);
        }

        private static JmAlbum album() {
            return new JmAlbum(
                    "album-1", "Album", "Description", "1", "2026-09-12", 1,
                    "2", "3", 1, "cover.jpg", new JmCategoryMeta("0", "All"), null,
                    List.of("Alice"), List.of("Work"), List.of("Actor"), List.of("tag"),
                    List.of(albumMeta()), List.of(new JmPhotoMeta("photo-1", "Photo", 1)),
                    "series-1", false, false, false, List.of(image()), "0", "0");
        }

        private static JmFavoritePage favorites(FavoriteQuery query) {
            return new JmFavoritePage(
                    "Folder " + query.getFolderId(),
                    query.getFolderId(),
                    query.getPage(),
                    1,
                    3,
                    List.of(albumMeta()),
                    Map.of("0", "All", "7", "Reading")
            );
        }

        private static JmPhoto photo(String id) {
            return new JmPhoto(id, "Photo", "album-1", "1", 1,
                    "Alice", List.of("tag"), List.of(image(id)), false);
        }

        private static JmImage image(String photoId) {
            return new JmImage(photoId, "1", "001.webp",
                    "https://example.invalid/001.webp", "", 1);
        }

        private static JmImage image() {
            return image("photo-1");
        }

        private static JmCommentList comments() {
            JmComment comment = new JmComment(
                    "comment-1", "user-1", "alice", "Alice", "Hello", "2026-09-12",
                    "", "", "album-1", List.of(), 1, 0);
            return new JmCommentList(1, List.of(comment));
        }

        private static JmUserInfo userInfo() {
            return new JmUserInfo(
                    "user-1", "alice", "alice@example.invalid", true, "avatar.jpg", "Alice",
                    "", "", 10, 2, 3, "Level 3", 100, 50, 0.5, 100);
        }

        private static JmUserProfile userProfile() {
            return new JmUserProfile(
                    "alice", "alice@example.invalid", "Alice", "", "", "", "", "", "",
                    "", "City", "Country", "Engineer", "", "", "About", "", "", "", "", "", "");
        }
    }

    private static final class FakeDownloadManager implements IDownloadManager {
        private final Map<String, BaseDownloadTask> tasks = new HashMap<>();

        @Override
        public BaseDownloadTask getTask(String taskId) {
            return tasks.get(taskId);
        }

        @Override
        public List<BaseDownloadTask> getActiveTasks() {
            return tasks.values().stream().filter(task -> !task.currentState().isTerminal()).toList();
        }

        @Override
        public List<BaseDownloadTask> getTaskRegistry() {
            return List.copyOf(tasks.values());
        }

        @Override
        public void submit(BaseDownloadTask task) {
            if (task.transitState(TaskState.PENDING, TaskState.QUEUED)
                    || task.transitState(TaskState.PAUSED, TaskState.QUEUED)) {
                tasks.put(task.getTaskId(), task);
                task.notifyStateChanged(TaskState.QUEUED);
                task.run();
            }
        }

        @Override
        public void pause(String taskId) {
            BaseDownloadTask task = tasks.get(taskId);
            if (task != null) task.pause();
        }

        @Override
        public void resume(String taskId) {
            BaseDownloadTask task = tasks.get(taskId);
            if (task != null) task.resume();
        }

        @Override
        public void cancel(String taskId) {
            BaseDownloadTask task = tasks.get(taskId);
            if (task != null) task.cancel();
        }

        @Override
        public void close() {
            for (BaseDownloadTask task : List.copyOf(tasks.values())) task.cancel();
        }
    }

    private static final class ImmediateDownloadTask extends BaseDownloadTask {
        private final JmPhoto photo;
        private final Path directory;
        private final byte[] bytes;
        private final IDownloadManager manager;

        private ImmediateDownloadTask(
                JmPhoto photo,
                Path path,
                byte[] bytes,
                IDownloadManager manager
        ) {
            this.photo = photo;
            this.directory = photo.isSingleAlbum() ? path : path.resolve(photo.getId());
            this.bytes = bytes;
            this.manager = manager;
            this.totalBytes = (long) bytes.length * photo.getImages().size();
        }

        @Override
        public void start() {
            if (!transitState(TaskState.QUEUED, TaskState.RUNNING)) return;
            notifyStateChanged(TaskState.RUNNING);
            try {
                Files.createDirectories(directory);
                for (JmImage image : photo.getImages()) {
                    Path target = directory.resolve(image.getFilename());
                    Files.write(target, bytes);
                    addSuccessfulFile(target);
                    completedCount++;
                    downloadedBytes += bytes.length;
                    notifyProgressUpdate(new DownloadProgress(
                            photo.getAlbumId(), null, photo.getId(), photo.getTitle(),
                            completedCount, 0, photo.getImages().size(), 0, 0, 0,
                            false, downloadedBytes, String.valueOf(System.currentTimeMillis())));
                }
                if (transitState(TaskState.RUNNING, TaskState.COMPLETED)) {
                    notifyStateChanged(TaskState.COMPLETED);
                    notifyFinish(getCurrentDownloadResult());
                }
            } catch (Exception exception) {
                notifyError(exception);
                if (transitState(TaskState.RUNNING, TaskState.FAILED)) {
                    notifyStateChanged(TaskState.FAILED);
                }
            }
        }

        @Override
        public void pause() {
            if (transitState(TaskState.RUNNING, TaskState.PAUSED)
                    || transitState(TaskState.QUEUED, TaskState.PAUSED)) {
                notifyStateChanged(TaskState.PAUSED);
            }
        }

        @Override
        public void resume() {
            manager.submit(this);
        }

        @Override
        public void cancel() {
            boolean cancelling = transitState(TaskState.RUNNING, TaskState.CANCELLING);
            boolean cancelled = transitState(TaskState.PENDING, TaskState.CANCELLED)
                    || transitState(TaskState.QUEUED, TaskState.CANCELLED)
                    || transitState(TaskState.PAUSED, TaskState.CANCELLED);
            if (cancelling) cancelled = transitState(TaskState.CANCELLING, TaskState.CANCELLED);
            if (cancelled) notifyStateChanged(TaskState.CANCELLED);
        }
    }
}
