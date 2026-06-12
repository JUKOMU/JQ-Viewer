package io.github.jukomu.jqviewer.desktop.store;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class DesktopSettingsStoreTest {
    private static final Gson GSON = new Gson();

    @TempDir
    Path tempDir;

    @Test
    void desktopOcrDefaultsToDisabled() throws Exception {
        DesktopSettingsStore store = new DesktopSettingsStore(tempDir);

        assertFalse(store.getAll().get("ocrEnabled").getAsBoolean());
    }

    @Test
    void desktopOcrStaysDisabledWhenExistingSettingsEnabledIt() throws Exception {
        JsonObject existing = new JsonObject();
        existing.addProperty("ocrEnabled", true);
        Files.createDirectories(tempDir);
        Files.writeString(tempDir.resolve("settings.json"), GSON.toJson(existing));

        DesktopSettingsStore store = new DesktopSettingsStore(tempDir);

        assertFalse(store.getAll().get("ocrEnabled").getAsBoolean());
    }

    @Test
    void desktopOcrUpdateIsIgnoredBecauseFeatureIsUnsupported() throws Exception {
        DesktopSettingsStore store = new DesktopSettingsStore(tempDir);
        JsonObject patch = new JsonObject();
        patch.addProperty("ocrEnabled", true);

        JsonObject updated = store.update(patch);

        assertFalse(updated.get("ocrEnabled").getAsBoolean());
        assertFalse(store.getAll().get("ocrEnabled").getAsBoolean());
    }
}
