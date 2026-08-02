package io.github.jukomu.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import io.github.jukomu.service.PreloadService;
import io.github.jukomu.service.SettingsService;

import org.json.JSONObject;
import org.junit.Test;

public class CacheSettingsInstrumentedTest {

    @Test
    public void settingsAndCapacityInfoExposeCompatibleRuntimeFields() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SettingsStore settingsStore = SettingsStore.getInstance(context);
        long requestedMb = settingsStore.getLong(
            "cache_capacity_mb", CacheCapacityPolicy.DEFAULT_REQUESTED_MB);

        CacheCapacityPolicy policy = new CacheCapacityPolicy();
        CacheCapacityPolicy.Result result = policy.calculate(
            requestedMb, 512L * CacheCapacityPolicy.MIB, false,
            CacheCapacityPolicy.PressureLevel.NORMAL);
        ImageCache imageCache = ImageCache.createIsolated();
        imageCache.applyPolicy(result);

        SettingsService settingsService = new SettingsService(
            settingsStore, null, null, null, context, null, imageCache);
        JSONObject allSettings = settingsService.getAllSettings();

        PreloadService preloadService = new PreloadService(
            imageCache, null, settingsStore, null, null, null, context, policy);
        JSONObject capacityInfo = preloadService.getCacheCapacityInfo();

        assertEquals(requestedMb, allSettings.getLong("cacheRequestedMb"));
        assertEquals(result.effectiveMb, allSettings.getLong("cacheCapacityMb"));
        assertEquals(result.effectiveMb, allSettings.getLong("cacheEffectiveMb"));
        assertEquals(result.effectiveMb, capacityInfo.getLong("capacityMb"));
        assertEquals(requestedMb, capacityInfo.getLong("requestedMb"));
        assertEquals(result.effectiveMb, capacityInfo.getLong("effectiveMb"));
        assertEquals(512L, capacityInfo.getLong("maxHeapMb"));
        assertFalse(capacityInfo.getBoolean("temporaryClamp"));
        assertEquals(requestedMb, settingsStore.getLong(
            "cache_capacity_mb", CacheCapacityPolicy.DEFAULT_REQUESTED_MB));
    }
}
