package io.github.jukomu.desktop.feature.settings.model;

/** 返回当前平台支持的全部基础设置。 */
public record SettingsResponse(
        int readerPreloadPages,
        int preloadConcurrency,
        int downloadConcurrency,
        boolean downloadPublic,
        int cacheCapacityMb,
        int cacheRequestedMb,
        int cacheEffectiveMb,
        long cacheMaxHeapMb,
        boolean cacheTemporaryClamp,
        String cacheLimitReason,
        boolean ocrEnabled,
        String readerDisplayMode,
        String readerScreenOrientation,
        int readerBrightness,
        boolean readerKeepScreenOn,
        boolean readerVolumeNavigation,
        boolean readerAutoShowToolbarAtEnd
) {
}
