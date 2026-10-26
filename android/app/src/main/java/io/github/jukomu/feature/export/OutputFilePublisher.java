package io.github.jukomu.feature.export;

import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;

/** PDF/归档共用的最终文件提交；不覆盖模式在打开目标时排他创建。 */
public final class OutputFilePublisher {
    private OutputFilePublisher() {
    }

    public static void publish(File temporary, File target, boolean allowOverwrite)
        throws IOException {
        if (allowOverwrite) {
            try {
                Os.rename(temporary.getAbsolutePath(), target.getAbsolutePath());
            } catch (ErrnoException error) {
                throw new IOException(error.getMessage(), error);
            }
            return;
        }
        // 不能用 ATOMIC_MOVE 代替 no-replace；CREATE_NEW 在创建时检查冲突。
        OutputStream created = Files.newOutputStream(target.toPath(),
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try (OutputStream output = created) {
            Files.copy(temporary.toPath(), output);
        } catch (IOException | RuntimeException failure) {
            try {
                Files.delete(target.toPath());
            } catch (IOException | RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        Files.delete(temporary.toPath());
    }
}
