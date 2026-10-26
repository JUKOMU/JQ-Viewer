package io.github.jukomu.feature.export;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class OutputFilePublisherInstrumentedTest {
    @Test
    public void rejectsLateConflictAndSupportsExplicitOverwrite() throws Exception {
        File directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getCacheDir().toPath(), "publisher-").toFile();
        File temporary = new File(directory, "volume.tmp");
        File target = new File(directory, "volume.pdf");
        try {
            Files.write(temporary.toPath(), new byte[]{1, 2, 3});
            Files.write(target.toPath(), new byte[]{9});
            assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> OutputFilePublisher.publish(temporary, target, false));
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(target.toPath()));
            assertTrue(temporary.isFile());
            OutputFilePublisher.publish(temporary, target, true);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(target.toPath()));
            assertFalse(temporary.exists());
        } finally {
            Files.deleteIfExists(temporary.toPath());
            Files.deleteIfExists(target.toPath());
            Files.delete(directory.toPath());
        }
    }

    @Test
    public void cleansOnlyCreatedPartialFileAndNeverReplacesDirectory() throws Exception {
        File directory = Files.createTempDirectory(InstrumentationRegistry.getInstrumentation()
            .getTargetContext().getCacheDir().toPath(), "publisher-").toFile();
        File target = new File(directory, "volume.cbz");
        File missingSource = new File(directory, "missing.tmp");
        try {
            assertThrows(IOException.class,
                () -> OutputFilePublisher.publish(missingSource, target, false));
            assertFalse(target.exists());
            assertTrue(target.mkdir());
            File source = new File(directory, "source.tmp");
            Files.write(source.toPath(), new byte[]{7});
            try {
                assertThrows(IOException.class,
                    () -> OutputFilePublisher.publish(source, target, true));
                assertTrue(target.isDirectory());
                assertTrue(source.isFile());
            } finally {
                Files.deleteIfExists(source.toPath());
            }
        } finally {
            Files.deleteIfExists(target.toPath());
            Files.delete(directory.toPath());
        }
    }
}
