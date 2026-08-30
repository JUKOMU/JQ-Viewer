package io.github.jukomu.picacomic;

import android.content.Context;

import io.github.jukomu.feature.cache.ImageCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Process-scoped, provider-specific Picacomic runtime.
 *
 * <p>The process singleton is created only when a Picacomic operation needs
 * it.  It is not touched by plugin load or by {@code getAuthState()} when no
 * session exists, so normal JMComic startup has no Pica client or executor
 * side effect.</p>
 */
public final class PicacomicRuntime implements AutoCloseable {

    public interface ClientFactory {
        PicacomicRemoteClient create() throws Exception;
    }

    public interface ImageEventSink {
        void onEvent(PicacomicImageEvent event);
    }

    private static final int IMAGE_EXECUTOR_SIZE = 4;
    private static final Object PROCESS_LOCK = new Object();
    private static volatile PicacomicRuntime processInstance;

    private static final ClientFactory UNAVAILABLE_CLIENT_FACTORY =
        () -> new UnavailablePicacomicClient();

    private final ClientFactory clientFactory;
    private final ImageCache imageCache;
    private final PicacomicImageRegistry imageRegistry = new PicacomicImageRegistry();
    private final Object lifecycleLock = new Object();
    private final Map<String, PendingImage> pendingImages = new ConcurrentHashMap<>();
    private final Set<PicacomicCancellationToken> activeOperations =
        ConcurrentHashMap.newKeySet();

    private volatile AuthSnapshot authSnapshot = AuthSnapshot.signedOut();
    private volatile PicacomicRemoteClient client;
    private volatile PicacomicImageLoader imageLoader;
    private volatile ExecutorService imageExecutor;
    private volatile boolean closed;

    private PicacomicRuntime(ClientFactory clientFactory, ImageCache imageCache) {
        this.clientFactory = clientFactory == null ? UNAVAILABLE_CLIENT_FACTORY : clientFactory;
        this.imageCache = imageCache == null ? ImageCache.getInstance() : imageCache;
    }

    /** Production process runtime.  The default client is intentionally unavailable in CP2. */
    public static PicacomicRuntime getOrCreate(Context context) {
        return getOrCreate(context, UNAVAILABLE_CLIENT_FACTORY);
    }

    /** Injectable overload used by a future artifact adapter and contract tests. */
    public static PicacomicRuntime getOrCreate(Context context, ClientFactory factory) {
        synchronized (PROCESS_LOCK) {
            if (processInstance == null || processInstance.closed) {
                processInstance = new PicacomicRuntime(factory, ImageCache.getInstance());
            }
            return processInstance;
        }
    }

    /** Returns the process runtime without creating it. */
    public static PicacomicRuntime peekProcess() {
        PicacomicRuntime runtime = processInstance;
        return runtime == null || runtime.closed ? null : runtime;
    }

    public static boolean exists() {
        return peekProcess() != null;
    }

    /** Releases a process runtime only if it is still the current singleton. */
    public static void releaseProcess(PicacomicRuntime runtime) {
        if (runtime == null) return;
        synchronized (PROCESS_LOCK) {
            if (processInstance == runtime) processInstance = null;
        }
        runtime.close();
    }

    /** Isolated runtime constructor for JVM/Android contract tests. */
    public static PicacomicRuntime createIsolated(ClientFactory factory, ImageCache cache) {
        return new PicacomicRuntime(factory, cache);
    }

    /** Test cleanup for a process singleton; it is not used by application code. */
    public static void resetProcessForTests() {
        PicacomicRuntime runtime;
        synchronized (PROCESS_LOCK) {
            runtime = processInstance;
            processInstance = null;
        }
        if (runtime != null) runtime.close();
    }

    public AuthSnapshot getAuthState() {
        return authSnapshot;
    }

    public boolean isClosed() {
        return closed;
    }

    public PicacomicImageRegistry getImageRegistry() {
        return imageRegistry;
    }

    public ImageCache getImageCache() {
        return imageCache;
    }

    public PicacomicUser login(String usernameOrEmail, String password,
                               PicacomicCancellationToken cancellation)
        throws PicacomicException {
        if (blank(usernameOrEmail) || blank(password) || cancellation == null) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "login");
        }
        synchronized (lifecycleLock) {
            ensureOpen();
            if (authSnapshot.state == AuthSnapshot.State.AUTHENTICATING) {
                throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "login");
            }
            authSnapshot = AuthSnapshot.authenticating();
        }

        activeOperations.add(cancellation);
        try {
            PicacomicRemoteClient remote = ensureClient();
            cancellation.throwIfCancelled();
            PicacomicRemoteModels.User rawUser = remote.login(
                usernameOrEmail, password, cancellation);
            cancellation.throwIfCancelled();
            PicacomicUser user = PicacomicMapper.mapUser(rawUser);
            synchronized (lifecycleLock) {
                ensureOpen();
                authSnapshot = AuthSnapshot.signedIn(user);
            }
            return user;
        } catch (Throwable error) {
            PicacomicException mapped = PicacomicErrorMapper.map("login", error);
            synchronized (lifecycleLock) {
                if (!closed) {
                    authSnapshot = mapped.getErrorCode() == PicacomicErrorCode.AUTH_EXPIRED
                        ? AuthSnapshot.expired() : AuthSnapshot.signedOut();
                }
            }
            throw mapped;
        } finally {
            activeOperations.remove(cancellation);
        }
    }

    public PicacomicCatalogPage search(String query, String order, int page,
                                       PicacomicCancellationToken cancellation)
        throws PicacomicException {
        validatePage(page, "search");
        requireSignedIn();
        PicacomicRemoteModels.Page result = callRemote("search", cancellation,
            remote -> remote.search(value(query), value(order), page, cancellation));
        return mapCatalog(result, "search");
    }

    public PicacomicCatalogPage categories(String category, String order, int page,
                                           PicacomicCancellationToken cancellation)
        throws PicacomicException {
        validatePage(page, "categories");
        requireSignedIn();
        PicacomicRemoteModels.Page result = callRemote("categories", cancellation,
            remote -> remote.categories(value(category), value(order), page, cancellation));
        return mapCatalog(result, "categories");
    }

    public PicacomicAlbumDetail getAlbum(String albumId,
                                         PicacomicCancellationToken cancellation)
        throws PicacomicException {
        AlbumRef ref;
        try {
            ref = AlbumRef.of(albumId);
        } catch (IllegalArgumentException error) {
            throw PicacomicErrorMapper.map("album", error);
        }
        requireSignedIn();
        PicacomicRemoteModels.Album result = callRemote("album", cancellation,
            remote -> remote.getAlbum(ref.albumId, cancellation));
        return mapAlbum(result);
    }

    /**
     * Resolves a chapter by stable id while using order only as the provider
     * locator.  A mismatch causes one authoritative album refresh and at
     * most one re-query at the current order.
     */
    public PicacomicChapterDetail getPhoto(ChapterRef requested,
                                           PicacomicCancellationToken cancellation)
        throws PicacomicException {
        if (requested == null || cancellation == null) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "chapter");
        }
        requireSignedIn();
        PicacomicRemoteModels.Photo first = callRemote("chapter", cancellation,
            remote -> remote.getPhoto(requested.albumId, requested.order, cancellation));
        ChapterRef resolvedRef = requested;
        PicacomicRemoteModels.Photo resolved = first;
        if (!matches(requested, first)) {
            PicacomicRemoteModels.Album refreshed = callRemote("chapter", cancellation,
                remote -> remote.getAlbum(requested.albumId, cancellation));
            PicacomicRemoteModels.Photo current = findChapter(
                refreshed, requested.albumId, requested.chapterId);
            if (current == null || current.order <= 0) {
                throw new PicacomicException(PicacomicErrorCode.STALE_RESOURCE, "chapter");
            }
            ChapterRef retryRef = requested.withOrder(current.order);
            resolvedRef = retryRef;
            try {
                resolved = callRemote("chapter", cancellation,
                    remote -> remote.getPhoto(requested.albumId, retryRef.order, cancellation));
            } catch (PicacomicException error) {
                if (error.getErrorCode() == PicacomicErrorCode.NOT_FOUND) {
                    throw new PicacomicException(PicacomicErrorCode.STALE_RESOURCE, "chapter",
                        error);
                }
                throw error;
            }
            if (!matches(resolvedRef, resolved)) {
                throw new PicacomicException(PicacomicErrorCode.STALE_RESOURCE, "chapter");
            }
        }
        return mapChapter(resolved, resolvedRef);
    }

    /**
     * Accepts image keys and starts bounded native loads.  The returned
     * snapshot distinguishes entries already cached from work in flight.
     */
    public PicacomicImageRequestResult requestImages(List<String> imageKeys,
                                                     boolean replacePending,
                                                     ImageEventSink sink)
        throws PicacomicException {
        return requestImages(imageKeys, replacePending, sink, null);
    }

    /** Variant that ties all accepted image work to one plugin call scope. */
    public PicacomicImageRequestResult requestImages(List<String> imageKeys,
                                                     boolean replacePending,
                                                     ImageEventSink sink,
                                                     PicacomicCancellationToken ownerCancellation)
        throws PicacomicException {
        requireSignedIn();
        if (ownerCancellation != null) ownerCancellation.throwIfCancelled();
        ensureClient();
        ExecutorService executor = imageExecutor;
        if (executor == null) {
            throw new PicacomicException(PicacomicErrorCode.INTERNAL, "images");
        }
        if (imageKeys == null) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "images");
        }

        List<String> cached = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        Set<String> uniqueKeys = new LinkedHashSet<>(imageKeys);
        for (String key : uniqueKeys) {
            PicacomicImageRef image = imageRefForKey(key);
            String cacheKey = image.cacheKey();
            if (imageCache.has(cacheKey)) {
                cached.add(key);
                continue;
            }

            PendingImage previous = pendingImages.get(key);
            if (previous != null && !replacePending) {
                pending.add(key);
                continue;
            }
            if (previous != null && pendingImages.remove(key, previous)) {
                previous.cancel();
            }

            PendingImage work = new PendingImage(image, sink, ownerCancellation);
            pendingImages.put(key, work);
            try {
                work.future = executor.submit(() -> loadPending(work));
                pending.add(key);
            } catch (RuntimeException error) {
                pendingImages.remove(key, work);
                throw PicacomicErrorMapper.map("images", error);
            }
        }
        return new PicacomicImageRequestResult(cached, pending);
    }

    public PicacomicImageRequestResult retryImage(String imageKey, ImageEventSink sink)
        throws PicacomicException {
        return retryImage(imageKey, sink, null);
    }

    public PicacomicImageRequestResult retryImage(String imageKey, ImageEventSink sink,
                                                  PicacomicCancellationToken ownerCancellation)
        throws PicacomicException {
        if (imageKey == null || imageKey.isEmpty()) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "image");
        }
        return requestImages(Collections.singletonList(imageKey), true, sink, ownerCancellation);
    }

    public int pendingImageCount() {
        return pendingImages.size();
    }

    /** Cancels image work owned by one plugin listener scope without closing the process runtime. */
    public void cancelPendingImages(ImageEventSink sink) {
        if (sink == null) return;
        for (Map.Entry<String, PendingImage> entry : pendingImages.entrySet()) {
            PendingImage work = entry.getValue();
            if (work.sink == sink && pendingImages.remove(entry.getKey(), work)) {
                work.cancel();
            }
        }
    }

    public AuthSnapshot logout() {
        close();
        return AuthSnapshot.signedOut();
    }

    @Override
    public void close() {
        PicacomicRemoteClient remote;
        ExecutorService executor;
        synchronized (lifecycleLock) {
            if (closed) return;
            closed = true;
            authSnapshot = AuthSnapshot.signedOut();
            for (PicacomicCancellationToken operation : activeOperations) {
                operation.cancel();
            }
            activeOperations.clear();
            for (PendingImage work : pendingImages.values()) work.cancel();
            pendingImages.clear();
            imageRegistry.clear();
            PicacomicCacheNamespace.clear(imageCache);
            remote = client;
            client = null;
            imageLoader = null;
            executor = imageExecutor;
            imageExecutor = null;
        }
        if (remote != null) remote.close();
        if (executor != null) executor.shutdownNow();
    }

    private PicacomicRemoteClient ensureClient() throws PicacomicException {
        PicacomicRemoteClient current = client;
        if (current != null) return current;
        synchronized (lifecycleLock) {
            ensureOpen();
            if (client == null) {
                try {
                    client = clientFactory.create();
                    if (client == null) throw new IllegalStateException("client unavailable");
                    imageExecutor = Executors.newFixedThreadPool(
                        IMAGE_EXECUTOR_SIZE, daemonThreadFactory("picacomic-image"));
                    imageLoader = new PicacomicImageLoader(client, imageRegistry, imageCache);
                } catch (PicacomicException error) {
                    throw error;
                } catch (Exception error) {
                    throw PicacomicErrorMapper.map("runtime", error);
                }
            }
            return client;
        }
    }

    private <T> T callRemote(String operation, PicacomicCancellationToken cancellation,
                             RemoteOperation<T> operationCall) throws PicacomicException {
        if (cancellation == null) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, operation);
        }
        activeOperations.add(cancellation);
        try {
            PicacomicRemoteClient remote = ensureClient();
            cancellation.throwIfCancelled();
            T result = operationCall.run(remote);
            cancellation.throwIfCancelled();
            return result;
        } catch (Throwable error) {
            PicacomicException mapped = PicacomicErrorMapper.map(operation, error);
            if (mapped.getErrorCode() == PicacomicErrorCode.AUTH_EXPIRED) markExpired();
            throw mapped;
        } finally {
            activeOperations.remove(cancellation);
        }
    }

    private PicacomicCatalogPage mapCatalog(PicacomicRemoteModels.Page raw, String operation)
        throws PicacomicException {
        try {
            return PicacomicMapper.mapCatalogPage(raw, imageRegistry);
        } catch (PicacomicException error) {
            throw error;
        } catch (Throwable error) {
            throw PicacomicErrorMapper.map(operation, error);
        }
    }

    private PicacomicAlbumDetail mapAlbum(PicacomicRemoteModels.Album raw)
        throws PicacomicException {
        try {
            return PicacomicMapper.mapAlbumDetail(raw, imageRegistry);
        } catch (PicacomicException error) {
            throw error;
        } catch (Throwable error) {
            throw PicacomicErrorMapper.map("album", error);
        }
    }

    private PicacomicChapterDetail mapChapter(PicacomicRemoteModels.Photo raw,
                                               ChapterRef expected)
        throws PicacomicException {
        try {
            return PicacomicMapper.mapChapterDetail(raw, expected, imageRegistry);
        } catch (PicacomicException error) {
            throw error;
        } catch (Throwable error) {
            throw PicacomicErrorMapper.map("chapter", error);
        }
    }

    private PicacomicImageRef imageRefForKey(String key) throws PicacomicException {
        if (key == null || key.isEmpty()) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "image");
        }
        PicacomicImageSource source = imageRegistry.resolve(key);
        if (source == null) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "image");
        }
        PicacomicImageRef ref;
        try {
            ref = PicacomicCacheNamespace.imageRef(source);
        } catch (IllegalArgumentException error) {
            throw PicacomicErrorMapper.map("image", error);
        }
        if (!key.equals(ref.imageKey)) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, "image");
        }
        return ref;
    }

    private void loadPending(PendingImage work) {
        try {
            if (closed || work.token.isCancelled()
                || (work.ownerCancellation != null && work.ownerCancellation.isCancelled())) return;
            PicacomicImageLoader loader = imageLoader;
            if (loader == null) return;
            loader.load(work.image, work.token);
            if (!closed && !work.token.isCancelled()
                && (work.ownerCancellation == null || !work.ownerCancellation.isCancelled())) {
                publish(work.sink,
                    PicacomicImageEvent.ready(work.image.imageKey));
            }
        } catch (Throwable error) {
            PicacomicException mapped = PicacomicErrorMapper.map("image", error);
            if (mapped.getErrorCode() == PicacomicErrorCode.AUTH_EXPIRED) markExpired();
            if (mapped.getErrorCode() != PicacomicErrorCode.CANCELLED && !closed
                && !work.token.isCancelled()
                && (work.ownerCancellation == null || !work.ownerCancellation.isCancelled())) {
                publish(work.sink, PicacomicImageEvent.failed(work.image.imageKey,
                    mapped.getErrorCode()));
            }
        } finally {
            pendingImages.remove(work.image.imageKey, work);
        }
    }

    private static void publish(ImageEventSink sink, PicacomicImageEvent event) {
        if (sink == null) return;
        try {
            sink.onEvent(event);
        } catch (RuntimeException ignored) {
            // A detached WebView must not terminate the image executor.
        }
    }

    private void requireSignedIn() throws PicacomicException {
        AuthSnapshot.State state = authSnapshot.state;
        if (state == AuthSnapshot.State.SIGNED_IN) return;
        if (state == AuthSnapshot.State.EXPIRED) {
            throw new PicacomicException(PicacomicErrorCode.AUTH_EXPIRED, "auth");
        }
        throw new PicacomicException(PicacomicErrorCode.AUTH_REQUIRED, "auth");
    }

    private void markExpired() {
        synchronized (lifecycleLock) {
            if (closed) return;
            authSnapshot = AuthSnapshot.expired();
            for (PendingImage work : pendingImages.values()) work.cancel();
            pendingImages.clear();
        }
    }

    private void ensureOpen() throws PicacomicException {
        if (closed) throw new PicacomicException(PicacomicErrorCode.CANCELLED, "runtime");
    }

    private static boolean matches(ChapterRef expected, PicacomicRemoteModels.Photo photo) {
        return photo != null && expected.matchesResponse(photo.albumId, photo.id, photo.order);
    }

    private static PicacomicRemoteModels.Photo findChapter(PicacomicRemoteModels.Album album,
                                                            String albumId, String chapterId) {
        if (album == null || album.photos == null) return null;
        PicacomicRemoteModels.Photo found = null;
        for (PicacomicRemoteModels.Photo photo : album.photos) {
            if (photo == null || !chapterId.equals(photo.id)) continue;
            if (photo.albumId != null && !photo.albumId.isEmpty()
                && !albumId.equals(photo.albumId)) return null;
            if (found != null) return null;
            found = photo;
        }
        return found;
    }

    private static void validatePage(int page, String operation) throws PicacomicException {
        if (page <= 0) {
            throw new PicacomicException(PicacomicErrorCode.INVALID_ARGUMENT, operation);
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private interface RemoteOperation<T> {
        T run(PicacomicRemoteClient client) throws Exception;
    }

    private static final class PendingImage {
        private final PicacomicImageRef image;
        private final PicacomicCancellationToken token = new PicacomicCancellationToken();
        private final ImageEventSink sink;
        private final PicacomicCancellationToken ownerCancellation;
        private volatile Future<?> future;

        private PendingImage(PicacomicImageRef image, ImageEventSink sink,
                             PicacomicCancellationToken ownerCancellation) {
            this.image = image;
            this.sink = sink;
            this.ownerCancellation = ownerCancellation;
        }

        private void cancel() {
            token.cancel();
            Future<?> current = future;
            if (current != null) current.cancel(true);
        }
    }

    /** No network is reachable from the CP2 default bridge. */
    private static final class UnavailablePicacomicClient implements PicacomicRemoteClient {
        @Override
        public PicacomicRemoteModels.User login(String usernameOrEmail, String password,
                                                PicacomicCancellationToken cancellation)
            throws Exception {
            throw new PicacomicRemoteException(PicacomicRemoteException.Kind.OTHER);
        }

        @Override
        public PicacomicRemoteModels.Page search(String query, String order, int page,
                                                 PicacomicCancellationToken cancellation)
            throws Exception {
            throw new PicacomicRemoteException(PicacomicRemoteException.Kind.OTHER);
        }

        @Override
        public PicacomicRemoteModels.Page categories(String category, String order, int page,
                                                     PicacomicCancellationToken cancellation)
            throws Exception {
            throw new PicacomicRemoteException(PicacomicRemoteException.Kind.OTHER);
        }

        @Override
        public PicacomicRemoteModels.Album getAlbum(String albumId,
                                                    PicacomicCancellationToken cancellation)
            throws Exception {
            throw new PicacomicRemoteException(PicacomicRemoteException.Kind.OTHER);
        }

        @Override
        public PicacomicRemoteModels.Photo getPhoto(String albumId, int order,
                                                    PicacomicCancellationToken cancellation)
            throws Exception {
            throw new PicacomicRemoteException(PicacomicRemoteException.Kind.OTHER);
        }

        @Override
        public byte[] fetchImageBytes(PicacomicImageSource source,
                                      PicacomicCancellationToken cancellation)
            throws Exception {
            throw new PicacomicRemoteException(PicacomicRemoteException.Kind.OTHER);
        }

        @Override
        public void close() {
        }
    }
}
