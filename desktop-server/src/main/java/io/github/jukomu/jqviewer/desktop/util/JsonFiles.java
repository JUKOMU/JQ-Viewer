package io.github.jukomu.jqviewer.desktop.util;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class JsonFiles {
    private JsonFiles() {
    }

    public static <T> T readJson(Path file, Gson gson, Class<T> type, T fallback) throws IOException {
        return readJson(file, gson, (Type) type, fallback);
    }

    @SuppressWarnings("unchecked")
    public static <T> T readJson(Path file, Gson gson, Type type, T fallback) throws IOException {
        if (!Files.exists(file)) return fallback;
        try {
            T parsed = (T) read(file, gson, type);
            return parsed == null ? fallback : parsed;
        } catch (RuntimeException primaryError) {
            Path backup = backupFile(file);
            if (Files.exists(backup)) {
                try {
                    T restored = (T) read(backup, gson, type);
                    if (restored != null) {
                        quarantineCorruptFile(file);
                        writeJson(file, gson, restored);
                        return restored;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            quarantineCorruptFile(file);
            return fallback;
        }
    }

    public static void writeJson(Path file, Gson gson, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = tempFile(file);
        Path backup = backupFile(file);
        try {
            Files.writeString(
                temp,
                gson.toJson(value),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            forceFile(temp);
            if (Files.exists(file)) {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            moveReplacing(temp, file);
            forceDirectory(file.getParent());
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static Object read(Path file, Gson gson, Type type) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, type);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void forceDirectory(Path dir) {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException ignored) {
        }
    }

    private static void quarantineCorruptFile(Path file) {
        try {
            if (Files.exists(file)) {
                Files.move(
                    file,
                    file.resolveSibling(file.getFileName() + ".corrupt-" + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException ignored) {
        }
    }

    private static Path tempFile(Path file) {
        return file.resolveSibling(file.getFileName() + ".tmp");
    }

    private static Path backupFile(Path file) {
        return file.resolveSibling(file.getFileName() + ".bak");
    }
}
