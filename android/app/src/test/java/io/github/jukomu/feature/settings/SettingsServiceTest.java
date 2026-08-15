package io.github.jukomu.feature.settings;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SettingsServiceTest {

    @Test
    public void invalidConcurrencyFallsBackToDefault() {
        assertEquals(SettingsService.DEFAULT_CONCURRENCY,
            SettingsService.normalizeConcurrency(0));
        assertEquals(SettingsService.DEFAULT_CONCURRENCY,
            SettingsService.normalizeConcurrency(-1));
        assertEquals(SettingsService.DEFAULT_CONCURRENCY,
            SettingsService.normalizeConcurrency(13));
        assertEquals(SettingsService.DEFAULT_CONCURRENCY,
            SettingsService.normalizeConcurrency(100));
    }

    @Test
    public void validConcurrencyKeepsConfiguredValue() {
        assertEquals(1, SettingsService.normalizeConcurrency(1));
        assertEquals(12, SettingsService.normalizeConcurrency(12));
    }
}
