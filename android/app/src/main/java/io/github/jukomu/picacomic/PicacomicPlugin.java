package io.github.jukomu.picacomic;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Isolated, narrow Picacomic bridge.  Loading this plugin never creates a
 * Picacomic runtime; the runtime is created on the first login/remote call.
 */
@CapacitorPlugin(name = "Picacomic")
public final class PicacomicPlugin extends Plugin {

    private final PicacomicRuntime injectedRuntime;
    private final Consumer<PicacomicImageEvent> injectedEventSink;
    private final PicacomicRuntime.ImageEventSink imageEventSink = this::publishImageEvent;
    private final Object dispatchLock = new Object();
    private final Set<PendingCall> pendingCalls = ConcurrentHashMap.newKeySet();

    private volatile ExecutorService dispatchExecutor;
    private volatile boolean destroyed;

    public PicacomicPlugin() {
        this(null, null);
    }

    PicacomicPlugin(PicacomicRuntime runtime) {
        this(runtime, null);
    }

    PicacomicPlugin(PicacomicRuntime runtime, Consumer<PicacomicImageEvent> eventSink) {
        injectedRuntime = runtime;
        injectedEventSink = eventSink;
    }

    /** Test-only construction seam; production uses Capacitor's public no-arg constructor. */
    public static PicacomicPlugin forTests(PicacomicRuntime runtime,
                                           Consumer<PicacomicImageEvent> eventSink) {
        return new PicacomicPlugin(runtime, eventSink);
    }

    public static PicacomicPlugin forTests(PicacomicRuntime runtime) {
        return new PicacomicPlugin(runtime, null);
    }

    /** Build gate for the internal fake UI; it does not create a runtime. */
    @PluginMethod
    public void getBuildInfo(PluginCall call) {
        JSObject result = new JSObject();
        result.put("debugUiEnabled", io.github.jukomu.BuildConfig.DEBUG);
        resolve(call, result);
    }

    @PluginMethod
    public void getAuthState(PluginCall call) {
        PicacomicRuntime runtime = currentRuntime();
        resolve(call, authJson(runtime == null ? AuthSnapshot.signedOut()
            : runtime.getAuthState()));
    }

    @PluginMethod
    public void getCatalogOptions(PluginCall call) {
        resolve(call, catalogOptionsJson());
    }

    @PluginMethod
    public void login(PluginCall call) {
        String usernameOrEmail = call.getString("usernameOrEmail");
        if (usernameOrEmail == null) usernameOrEmail = call.getString("username");
        String password = call.getString("password");
        if (blank(usernameOrEmail) || blank(password)) {
            reject(call, new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "login"));
            return;
        }
        final String loginIdentity = usernameOrEmail;
        dispatch(call, token -> {
            PicacomicRuntime runtime = ensureRuntime();
            runtime.login(loginIdentity, password, token);
            resolveIfAlive(call, authJson(runtime.getAuthState()));
        });
    }

    @PluginMethod
    public void logout(PluginCall call) {
        PicacomicRuntime runtime = currentRuntime();
        if (runtime == null) {
            resolve(call, authJson(AuthSnapshot.signedOut()));
            return;
        }
        dispatch(call, token -> {
            runtime.logout();
            if (injectedRuntime == null) PicacomicRuntime.releaseProcess(runtime);
            resolveIfAlive(call, authJson(AuthSnapshot.signedOut()));
        });
    }

    @PluginMethod
    public void search(PluginCall call) {
        PicacomicRuntime runtime = requireRuntime(call, "search");
        if (runtime == null) return;
        String query = call.getString("query", "");
        String order = call.getString("order", "latest");
        int page = call.getInt("page", 1);
        dispatch(call, token -> resolveIfAlive(call,
            catalogPageJson(runtime.search(query, order, page, token))));
    }

    @PluginMethod
    public void categories(PluginCall call) {
        PicacomicRuntime runtime = requireRuntime(call, "categories");
        if (runtime == null) return;
        String category = call.getString("category", "all");
        String order = call.getString("order", "latest");
        int page = call.getInt("page", 1);
        dispatch(call, token -> resolveIfAlive(call,
            catalogPageJson(runtime.categories(category, order, page, token))));
    }

    @PluginMethod
    public void getAlbum(PluginCall call) {
        String albumId = call.getString("albumId");
        if (blank(albumId)) {
            reject(call, new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "album"));
            return;
        }
        PicacomicRuntime runtime = requireRuntime(call, "album");
        if (runtime == null) return;
        dispatch(call, token -> resolveIfAlive(call,
            albumJson(runtime.getAlbum(albumId, token))));
    }

    @PluginMethod
    public void getPhoto(PluginCall call) {
        String albumId = call.getString("albumId");
        String chapterId = call.getString("chapterId");
        Integer order = call.getInt("order");
        if (blank(albumId) || blank(chapterId) || order == null || order <= 0) {
            reject(call, new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "chapter"));
            return;
        }
        final ChapterRef ref;
        try {
            ref = ChapterRef.of(albumId, chapterId, order);
        } catch (IllegalArgumentException error) {
            reject(call, PicacomicErrorMapper.map("chapter", error));
            return;
        }
        PicacomicRuntime runtime = requireRuntime(call, "chapter");
        if (runtime == null) return;
        dispatch(call, token -> resolveIfAlive(call,
            chapterJson(runtime.getPhoto(ref, token))));
    }

    @PluginMethod
    public void requestImages(PluginCall call) {
        JSArray values = call.getArray("imageKeys");
        if (values == null) {
            reject(call, new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "images"));
            return;
        }
        List<String> imageKeys = new java.util.ArrayList<>();
        try {
            for (int index = 0; index < values.length(); index++) {
                imageKeys.add(values.getString(index));
            }
        } catch (Exception error) {
            reject(call, PicacomicErrorMapper.map("images", error));
            return;
        }
        PicacomicRuntime runtime = requireRuntime(call, "images");
        if (runtime == null) return;
        boolean replacePending = call.getBoolean("replacePending", false);
        dispatch(call, token -> {
            PicacomicImageRequestResult result = runtime.requestImages(
                imageKeys, replacePending, imageEventSink, token);
            resolveIfAlive(call, imageResultJson(result));
        });
    }

    @PluginMethod
    public void retryImage(PluginCall call) {
        String imageKey = call.getString("imageKey");
        if (blank(imageKey)) {
            reject(call, new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "image"));
            return;
        }
        PicacomicRuntime runtime = requireRuntime(call, "image");
        if (runtime == null) return;
        dispatch(call, token -> {
            PicacomicImageRequestResult result = runtime.retryImage(
                imageKey, imageEventSink, token);
            resolveIfAlive(call, imageResultJson(result));
        });
    }

    @Override
    protected void handleOnDestroy() {
        synchronized (dispatchLock) {
            if (destroyed) return;
            destroyed = true;
            PicacomicRuntime runtime = currentRuntime();
            if (runtime != null) runtime.cancelPendingImages(imageEventSink);
            for (PendingCall pending : pendingCalls) pending.cancel();
            pendingCalls.clear();
            if (dispatchExecutor != null) {
                dispatchExecutor.shutdownNow();
                dispatchExecutor = null;
            }
        }
        // The process runtime intentionally survives plugin/activity recreation.
    }

    private PicacomicRuntime currentRuntime() {
        if (injectedRuntime != null) return injectedRuntime;
        return PicacomicRuntime.peekProcess();
    }

    private PicacomicRuntime ensureRuntime() {
        if (injectedRuntime != null) return injectedRuntime;
        return PicacomicRuntime.getOrCreate(getContext());
    }

    private PicacomicRuntime requireRuntime(PluginCall call, String operation) {
        PicacomicRuntime runtime = currentRuntime();
        if (runtime == null) {
            reject(call, new PicacomicException(PicacomicErrorCode.AUTH_REQUIRED, operation));
        }
        return runtime;
    }

    private void dispatch(PluginCall call, Operation operation) {
        PendingCall pending = new PendingCall();
        synchronized (dispatchLock) {
            if (destroyed) {
                reject(call, new PicacomicException(PicacomicErrorCode.CANCELLED, "plugin"));
                return;
            }
            if (dispatchExecutor == null) {
                dispatchExecutor = Executors.newFixedThreadPool(4,
                    daemonThreadFactory("picacomic-plugin"));
            }
            call.setKeepAlive(true);
            pendingCalls.add(pending);
            FutureTask<Void> task = new FutureTask<>(() -> {
                try {
                    operation.run(pending.token);
                } catch (Throwable error) {
                    if (!destroyed) reject(call, PicacomicErrorMapper.map("bridge", error));
                } finally {
                    pendingCalls.remove(pending);
                }
                return null;
            });
            pending.task = task;
            try {
                dispatchExecutor.execute(task);
            } catch (RuntimeException error) {
                pendingCalls.remove(pending);
                reject(call, PicacomicErrorMapper.map("bridge", error));
            }
        }
    }

    private void publishImageEvent(PicacomicImageEvent event) {
        if (destroyed) return;
        if (injectedEventSink != null) {
            injectedEventSink.accept(event);
        } else {
            notifyListeners(event.type.getEventName(), event.toJsObject());
        }
    }

    private void resolveIfAlive(PluginCall call, JSObject data) {
        if (!destroyed) call.resolve(data);
    }

    private void resolve(PluginCall call, JSObject data) {
        if (!destroyed) call.resolve(data);
    }

    private void reject(PluginCall call, PicacomicException error) {
        if (!destroyed) {
            call.reject(error.getMessage(), error.getCode(), error, error.toJsData());
        }
    }

    private static JSObject authJson(AuthSnapshot snapshot) {
        JSObject result = new JSObject();
        result.put("state", snapshot.state.getWireValue());
        if (snapshot.user != null) {
            JSObject user = new JSObject();
            user.put("id", snapshot.user.id);
            user.put("username", snapshot.user.username);
            result.put("user", user);
        }
        return result;
    }

    private static JSObject catalogOptionsJson() {
        PicacomicCatalogOptions options = new PicacomicCatalogOptions(
            Arrays.asList(
                new PicacomicCatalogOption("all", "All", "All categories")
            ),
            Arrays.asList(
                new PicacomicCatalogOption("latest", "Latest", "Recently updated"),
                new PicacomicCatalogOption("popular", "Popular", "Most viewed")
            ));
        JSObject result = new JSObject();
        result.put("categories", optionsJson(options.categories));
        result.put("orderBy", optionsJson(options.orderBy));
        return result;
    }

    private static JSArray optionsJson(List<PicacomicCatalogOption> options) {
        JSArray result = new JSArray();
        for (PicacomicCatalogOption option : options) {
            JSObject item = new JSObject();
            item.put("id", option.id);
            item.put("label", option.label);
            if (!option.description.isEmpty()) item.put("description", option.description);
            result.put(item);
        }
        return result;
    }

    private static JSObject catalogPageJson(PicacomicCatalogPage page) {
        JSObject result = new JSObject();
        result.put("currentPage", page.currentPage);
        result.put("totalPages", page.totalPages);
        result.put("totalItems", page.totalItems);
        JSArray items = new JSArray();
        for (PicacomicCatalogItem item : page.items) {
            JSObject json = new JSObject();
            putAlbumRef(json, item.ref);
            json.put("title", item.title);
            json.put("authors", stringsJson(item.authors));
            if (!item.translator.isEmpty()) json.put("translator", item.translator);
            if (item.cover != null) json.put("cover", imageJson(item.cover));
            json.put("pagesCount", item.pagesCount);
            json.put("finished", item.finished);
            items.put(json);
        }
        result.put("items", items);
        return result;
    }

    private static JSObject albumJson(PicacomicAlbumDetail album) {
        JSObject result = new JSObject();
        putAlbumRef(result, album.ref);
        result.put("title", album.title);
        result.put("authors", stringsJson(album.authors));
        if (!album.translator.isEmpty()) result.put("translator", album.translator);
        result.put("categories", stringsJson(album.categories));
        result.put("tags", stringsJson(album.tags));
        if (album.cover != null) result.put("cover", imageJson(album.cover));
        result.put("description", album.description);
        result.put("pagesCount", album.pagesCount);
        result.put("epsCount", album.epsCount);
        result.put("finished", album.finished);
        result.put("createdAt", album.createdAt);
        result.put("updatedAt", album.updatedAt);
        JSArray chapters = new JSArray();
        for (PicacomicChapterSummary chapter : album.chapters) {
            chapters.put(chapterJson(chapter));
        }
        result.put("chapters", chapters);
        return result;
    }

    private static JSObject chapterJson(PicacomicChapterSummary chapter) {
        JSObject result = new JSObject();
        putChapterRef(result, chapter.ref);
        result.put("title", chapter.title);
        result.put("updatedAt", chapter.updatedAt);
        if (chapter instanceof PicacomicChapterDetail) {
            PicacomicChapterDetail detail = (PicacomicChapterDetail) chapter;
            result.put("contentRevision", detail.contentRevision);
            result.put("isSingleChapterAlbum", detail.isSingleChapterAlbum);
            JSArray images = new JSArray();
            for (PicacomicImageRef image : detail.images) images.put(imageJson(image));
            result.put("images", images);
        }
        return result;
    }

    private static JSObject imageResultJson(PicacomicImageRequestResult result) {
        JSObject json = new JSObject();
        json.put("cached", stringsJson(result.cached));
        json.put("pending", stringsJson(result.pending));
        return json;
    }

    private static JSObject imageJson(PicacomicImageRef image) {
        JSObject result = new JSObject();
        result.put("imageKey", image.imageKey);
        result.put("pageIndex", image.pageIndex);
        result.put("cacheUrl", image.cacheUrl);
        return result;
    }

    private static void putAlbumRef(JSObject result, AlbumRef ref) {
        result.put("provider", ref.provider);
        result.put("albumId", ref.albumId);
        JSObject identity = new JSObject();
        identity.put("provider", ref.provider);
        identity.put("albumId", ref.albumId);
        result.put("ref", identity);
    }

    private static void putChapterRef(JSObject result, ChapterRef ref) {
        putAlbumRef(result, ref);
        result.put("chapterId", ref.chapterId);
        result.put("order", ref.order);
        JSObject identity = new JSObject();
        identity.put("provider", ref.provider);
        identity.put("albumId", ref.albumId);
        identity.put("chapterId", ref.chapterId);
        identity.put("order", ref.order);
        result.put("ref", identity);
    }

    private static JSArray stringsJson(List<String> values) {
        JSArray result = new JSArray();
        if (values != null) for (String value : values) result.put(value);
        return result;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private interface Operation {
        void run(PicacomicCancellationToken token) throws Exception;
    }

    private static final class PendingCall {
        private final PicacomicCancellationToken token = new PicacomicCancellationToken();
        private volatile FutureTask<Void> task;

        private void cancel() {
            token.cancel();
            FutureTask<Void> current = task;
            if (current != null) current.cancel(true);
        }
    }
}
