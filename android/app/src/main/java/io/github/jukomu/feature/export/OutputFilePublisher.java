package io.github.jukomu.feature.export;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** PDF/归档共用的最终文件提交；临时文件完成后再发布到最终路径。 */
public final class OutputFilePublisher {
    private OutputFilePublisher() {
    }

    public static void publish(File temporary, File target, boolean allowOverwrite)
        throws IOException {
        if (target.isDirectory()) {
            throw new IOException("导出目标是目录: " + target.getAbsolutePath());
        }
        if (allowOverwrite) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // 同目录移动默认不替换已有目标，并在发布时才让目标路径可见。
        Files.move(temporary.toPath(), target.toPath());
    }
}
