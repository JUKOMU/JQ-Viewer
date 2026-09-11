package io.github.jukomu.desktop.host;

import io.github.jukomu.desktop.data.DesktopPaths;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 协调 Desktop 单实例生命周期，并提供仅限 loopback 的打开首页信号。 */
public final class SingleInstanceGuard implements AutoCloseable {
    private static final String OPEN_HOME_COMMAND = "OPEN_HOME";
    private static final Duration SIGNAL_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration IPC_READ_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_IPC_COMMAND_BYTES = OPEN_HOME_COMMAND.length() + 2;

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

    /** 尝试成为主进程；已有进程持有锁时返回 false。 */
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
                // 保留原始启动异常。
            }
            try {
                candidate.close();
            } catch (IOException ignored) {
                // 保留原始启动异常。
            }
            if (candidateServer != null) {
                try {
                    candidateServer.close();
                } catch (IOException ignored) {
                    // 保留原始启动异常。
                }
            }
            throw exception;
        }
    }

    /** 通知当前主进程打开已经运行的首页 URL。 */
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
                // 主进程可能仍在写入端口，短暂重试。
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
            try (Socket socket = currentServer.accept()) {
                if (OPEN_HOME_COMMAND.equals(readCommand(socket))) {
                    try {
                        onOpenHome.run();
                    } catch (RuntimeException ignored) {
                        // 托盘或浏览器回调失败时仍保持信号循环运行。
                    }
                }
            } catch (IOException exception) {
                if (!closed.get()) {
                    // 关闭 server socket 是退出循环的正常方式。
                    Thread.yield();
                }
            }
        }
    }

    private static String readCommand(Socket socket) throws IOException {
        InputStream input = socket.getInputStream();
        byte[] bytes = new byte[MAX_IPC_COMMAND_BYTES];
        long deadline = System.nanoTime() + IPC_READ_TIMEOUT.toNanos();
        int length = 0;

        while (length < bytes.length) {
            int timeoutMillis = remainingTimeoutMillis(deadline);
            if (timeoutMillis <= 0) {
                return null;
            }
            socket.setSoTimeout(timeoutMillis);

            int value = input.read();
            if (value < 0) {
                return null;
            }
            bytes[length++] = (byte) value;
            if (value == '\n') {
                String command = new String(bytes, 0, length, StandardCharsets.UTF_8);
                if ((OPEN_HOME_COMMAND + "\n").equals(command)
                        || (OPEN_HOME_COMMAND + "\r\n").equals(command)) {
                    return OPEN_HOME_COMMAND;
                }
                return null;
            }
        }
        return null;
    }

    private static int remainingTimeoutMillis(long deadline) {
        long remainingNanos = deadline - System.nanoTime();
        if (remainingNanos <= 0) {
            return 0;
        }
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        return (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(1, remainingMillis)
        );
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
                // 关闭流程保持幂等。
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
                // 继续关闭通道。
            }
        }

        FileChannel currentChannel = channel;
        if (currentChannel != null) {
            try {
                currentChannel.close();
            } catch (IOException ignored) {
                // 关闭流程保持幂等。
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
