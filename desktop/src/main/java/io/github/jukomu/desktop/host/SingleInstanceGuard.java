package io.github.jukomu.desktop.host;

import io.github.jukomu.desktop.data.DesktopPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coordinates one Desktop process and a loopback-only open-home signal. */
public final class SingleInstanceGuard implements AutoCloseable {
    private static final String OPEN_HOME_COMMAND = "OPEN_HOME";
    private static final Duration SIGNAL_TIMEOUT = Duration.ofSeconds(1);

    private final Path lockPath;
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile FileChannel channel;
    private volatile FileLock lock;
    private volatile ServerSocket server;
    private volatile ExecutorService executor;
    private volatile Runnable onOpenHome;
    private volatile boolean owner;

    public SingleInstanceGuard(DesktopPaths paths) {
        this(paths.instanceLockPath());
    }

    public SingleInstanceGuard(Path lockPath) {
        this.lockPath = Objects.requireNonNull(lockPath, "lockPath").toAbsolutePath().normalize();
    }

    /** Attempts to become the primary process. Returns false when another process owns the lock. */
    public synchronized boolean tryAcquire(Runnable onOpenHome) throws IOException {
        Objects.requireNonNull(onOpenHome, "onOpenHome");
        if (closed.get()) {
            throw new IllegalStateException("Single-instance guard is closed");
        }
        if (owner) {
            return true;
        }

        Path parent = lockPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        FileChannel candidate = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE
        );
        FileLock candidateLock;
        try {
            candidateLock = candidate.tryLock();
        } catch (OverlappingFileLockException exception) {
            candidate.close();
            return false;
        } catch (IOException exception) {
            candidate.close();
            throw exception;
        }

        if (candidateLock == null) {
            candidate.close();
            return false;
        }

        ServerSocket candidateServer = null;
        try {
            candidateServer = new ServerSocket(
                    0,
                    16,
                    InetAddress.getLoopbackAddress()
            );
            writePort(candidate, candidateServer.getLocalPort());

            this.channel = candidate;
            this.lock = candidateLock;
            this.server = candidateServer;
            this.onOpenHome = onOpenHome;
            this.owner = true;
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "jq-viewer-desktop-instance");
                thread.setDaemon(true);
                return thread;
            });
            this.executor.execute(this::acceptSignals);
            return true;
        } catch (IOException | RuntimeException exception) {
            try {
                candidateLock.release();
            } catch (IOException ignored) {
                // Preserve the original startup failure.
            }
            try {
                candidate.close();
            } catch (IOException ignored) {
                // Preserve the original startup failure.
            }
            if (candidateServer != null) {
                try {
                    candidateServer.close();
                } catch (IOException ignored) {
                    // Preserve the original startup failure.
                }
            }
            throw exception;
        }
    }

    /** Signals the current owner to open its already-running home URL. */
    public boolean notifyExistingInstance() {
        Instant deadline = Instant.now().plus(SIGNAL_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            try {
                String value = Files.readString(lockPath, StandardCharsets.UTF_8).trim();
                int port = Integer.parseInt(value);
                try (Socket socket = new Socket()) {
                    socket.connect(
                            new InetSocketAddress(InetAddress.getLoopbackAddress(), port),
                            250
                    );
                    try (OutputStreamWriter writer = new OutputStreamWriter(
                            socket.getOutputStream(),
                            StandardCharsets.UTF_8
                    )) {
                        writer.write(OPEN_HOME_COMMAND);
                        writer.write('\n');
                        writer.flush();
                    }
                    return true;
                }
            } catch (NumberFormatException | IOException ignored) {
                // The primary may still be writing its port; retry briefly.
            }

            try {
                Thread.sleep(25);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void writePort(FileChannel target, int port) throws IOException {
        byte[] bytes = (Integer.toString(port) + "\n").getBytes(StandardCharsets.UTF_8);
        target.position(0);
        target.truncate(0);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            target.write(buffer);
        }
        target.force(true);
    }

    private void acceptSignals() {
        while (!closed.get()) {
            ServerSocket currentServer = server;
            if (currentServer == null) {
                return;
            }
            try (Socket socket = currentServer.accept();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(
                         socket.getInputStream(),
                         StandardCharsets.UTF_8
                 ))) {
                if (OPEN_HOME_COMMAND.equals(reader.readLine())) {
                    try {
                        onOpenHome.run();
                    } catch (RuntimeException ignored) {
                        // A tray/browser callback must not terminate the signal loop.
                    }
                }
            } catch (IOException exception) {
                if (!closed.get()) {
                    // Closing the server socket is the normal way to leave this loop.
                    Thread.yield();
                }
            }
        }
    }

    public Path lockPath() {
        return lockPath;
    }

    public boolean isOwner() {
        return owner;
    }

    public int ipcPort() {
        ServerSocket current = server;
        return current == null ? -1 : current.getLocalPort();
    }

    @Override
    public synchronized void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        ServerSocket currentServer = server;
        if (currentServer != null) {
            try {
                currentServer.close();
            } catch (IOException ignored) {
                // Shutdown remains idempotent.
            }
        }

        ExecutorService currentExecutor = executor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
        }

        FileLock currentLock = lock;
        if (currentLock != null && currentLock.isValid()) {
            try {
                currentLock.release();
            } catch (IOException ignored) {
                // Continue closing the channel and removing our metadata.
            }
        }

        FileChannel currentChannel = channel;
        if (currentChannel != null) {
            try {
                currentChannel.close();
            } catch (IOException ignored) {
                // Shutdown remains idempotent.
            }
        }

        if (owner) {
            try {
                Files.deleteIfExists(lockPath);
            } catch (IOException ignored) {
                // A stale lock file is harmless once the OS lock is released.
            }
        }

        owner = false;
        onOpenHome = null;
        server = null;
        executor = null;
        lock = null;
        channel = null;
    }
}
