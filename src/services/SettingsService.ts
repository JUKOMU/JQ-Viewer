/**
 * 应用设置服务（Android SQLite 持久化 + 内存缓存）。
 * 启动时由 App.vue 调用 initSettings() 从 DB 加载到缓存，
 * 之后所有读写走同步缓存，持久化由 SettingPage handler 调用 JmcomicService 完成。
 */
import { JmcomicService } from './JmcomicService'

let settingsLoaded = false

// ---- 同步缓存（App.vue 启动时由 initSettings 填充） ----
let cachedReaderPreloadPages = 15
let cachedPreloadConcurrency = 6
let cachedDownloadConcurrency = 6
let cachedDownloadPublic = false
let cachedCacheCapacityMb = 640

/** App.vue onMounted 调用，从 DB 加载到缓存（幂等）。 */
export async function initSettings(): Promise<void> {
    if (settingsLoaded) return
    try {
        const all = await JmcomicService.getAllSettings()
        cachedReaderPreloadPages = all.readerPreloadPages
        cachedPreloadConcurrency = all.preloadConcurrency
        cachedDownloadConcurrency = all.downloadConcurrency
        cachedDownloadPublic = all.downloadPublic
        cachedCacheCapacityMb = all.cacheCapacityMb
        settingsLoaded = true
    } catch (e) {
        // 使用默认值（已在缓存变量中预设）
        console.warn('[SettingsService] initSettings failed, using defaults:', e)
    }
}

export const SettingsStore = {
    // ---- 阅读：预加载页数 ----
    getReaderPreloadPages(): number { return cachedReaderPreloadPages },
    setReaderPreloadPages(n: number) { cachedReaderPreloadPages = n },

    // ---- 阅读：预加载并发数 ----
    getPreloadConcurrency(): number { return cachedPreloadConcurrency },
    setPreloadConcurrency(n: number) { cachedPreloadConcurrency = n },

    // ---- 下载：并发数 ----
    getDownloadConcurrency(): number { return cachedDownloadConcurrency },
    setDownloadConcurrency(n: number) { cachedDownloadConcurrency = n },

    // ---- 下载：是否公开 ----
    getDownloadPublic(): boolean { return cachedDownloadPublic },
    setDownloadPublic(open: boolean) { cachedDownloadPublic = open },

    // ---- 缓存容量 ----
    getCacheCapacityMb(): number { return cachedCacheCapacityMb },
    setCacheCapacityMb(mb: number) { cachedCacheCapacityMb = mb },
}
