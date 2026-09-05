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
let cachedCacheCapacityMb = 256
let cachedOcrEnabled = true
let cachedReaderDisplayMode = 'vertical'
let cachedReaderScreenOrientation = 'auto'
let cachedReaderBrightness = -1
let cachedReaderKeepScreenOn = true
let cachedReaderVolumeNavigation = false
let cachedReaderAutoShowToolbarAtEnd = true
let cachedPicacomicEnabled = false
let cachedPicacomicConversionEnabled = false
let confirmedPreloadConcurrency = 6
let confirmedDownloadConcurrency = 6
let preloadConcurrencySaveVersion = 0
let downloadConcurrencySaveVersion = 0
let preloadConcurrencySaveQueue: Promise<void> = Promise.resolve()
let downloadConcurrencySaveQueue: Promise<void> = Promise.resolve()

/** App.vue onMounted 调用，从 DB 加载到缓存（幂等）。 */
export async function initSettings(): Promise<void> {
  if (settingsLoaded) return
  try {
    const all = await JmcomicService.getAllSettings()
    cachedReaderPreloadPages = all.readerPreloadPages
    cachedPreloadConcurrency = all.preloadConcurrency
    cachedDownloadConcurrency = all.downloadConcurrency
    confirmedPreloadConcurrency = all.preloadConcurrency
    confirmedDownloadConcurrency = all.downloadConcurrency
    cachedDownloadPublic = all.downloadPublic
    cachedCacheCapacityMb = all.cacheRequestedMb ?? all.cacheCapacityMb
    cachedOcrEnabled = all.ocrEnabled
    cachedReaderDisplayMode = all.readerDisplayMode || 'vertical'
    cachedReaderScreenOrientation = all.readerScreenOrientation || 'auto'
    cachedReaderBrightness = all.readerBrightness ?? -1
    cachedReaderKeepScreenOn = all.readerKeepScreenOn ?? true
    cachedReaderVolumeNavigation = all.readerVolumeNavigation ?? false
    cachedReaderAutoShowToolbarAtEnd = all.readerAutoShowToolbarAtEnd ?? true
    cachedPicacomicEnabled = all.picacomicEnabled ?? false
    cachedPicacomicConversionEnabled =
      cachedPicacomicEnabled && (all.picacomicConversionEnabled ?? false)
    settingsLoaded = true
  } catch (e) {
    // 使用默认值（已在缓存变量中预设）
    console.warn('[SettingsService] initSettings failed, using defaults:', e)
  }
}

export const SettingsStore = {
  // ---- 阅读：预加载页数 ----
  getReaderPreloadPages(): number {
    return cachedReaderPreloadPages
  },
  setReaderPreloadPages(n: number) {
    cachedReaderPreloadPages = n
  },

  // ---- 阅读：预加载并发数 ----
  getPreloadConcurrency(): number {
    return cachedPreloadConcurrency
  },
  setPreloadConcurrency(n: number) {
    cachedPreloadConcurrency = n
  },

  // ---- 下载：并发数 ----
  getDownloadConcurrency(): number {
    return cachedDownloadConcurrency
  },
  setDownloadConcurrency(n: number) {
    cachedDownloadConcurrency = n
  },

  // ---- 下载：是否公开 ----
  getDownloadPublic(): boolean {
    return cachedDownloadPublic
  },
  setDownloadPublic(open: boolean) {
    cachedDownloadPublic = open
  },

  // ---- 缓存容量 ----
  getCacheCapacityMb(): number {
    return cachedCacheCapacityMb
  },
  setCacheCapacityMb(mb: number) {
    cachedCacheCapacityMb = mb
  },

  // ---- OCR 图片解析 ----
  getOcrEnabled(): boolean {
    return cachedOcrEnabled
  },
  setOcrEnabled(enabled: boolean) {
    cachedOcrEnabled = enabled
  },

  // ---- 阅读器：显示模式 ----
  getReaderDisplayMode(): string {
    return cachedReaderDisplayMode
  },
  setReaderDisplayMode(mode: string) {
    cachedReaderDisplayMode = mode
  },

  // ---- 阅读器：屏幕方向 ----
  getReaderScreenOrientation(): string {
    return cachedReaderScreenOrientation
  },
  setReaderScreenOrientation(orientation: string) {
    cachedReaderScreenOrientation = orientation
  },

  // ---- 阅读器：亮度 ----
  getReaderBrightness(): number {
    return cachedReaderBrightness
  },
  setReaderBrightness(brightness: number) {
    cachedReaderBrightness = brightness
  },

  // ---- 阅读器：防止熄屏 ----
  getReaderKeepScreenOn(): boolean {
    return cachedReaderKeepScreenOn
  },
  setReaderKeepScreenOn(enabled: boolean) {
    cachedReaderKeepScreenOn = enabled
  },

  // ---- 阅读器：音量键翻页 ----
  getReaderVolumeNavigation(): boolean {
    return cachedReaderVolumeNavigation
  },
  setReaderVolumeNavigation(enabled: boolean) {
    cachedReaderVolumeNavigation = enabled
  },

  // ---- 阅读器：阅读结束时展开工具栏 ----
  getReaderAutoShowToolbarAtEnd(): boolean {
    return cachedReaderAutoShowToolbarAtEnd
  },
  setReaderAutoShowToolbarAtEnd(enabled: boolean) {
    cachedReaderAutoShowToolbarAtEnd = enabled
  },

  // ---- PicaComic 实验性功能 ----
  getPicacomicEnabled(): boolean {
    return cachedPicacomicEnabled
  },
  setPicacomicEnabled(enabled: boolean) {
    cachedPicacomicEnabled = enabled
    if (!enabled) cachedPicacomicConversionEnabled = false
  },
  getPicacomicConversionEnabled(): boolean {
    return cachedPicacomicConversionEnabled
  },
  setPicacomicConversionEnabled(enabled: boolean) {
    cachedPicacomicConversionEnabled = enabled && cachedPicacomicEnabled
  },
}

export function persistPreloadConcurrency(n: number): Promise<void> {
  const version = ++preloadConcurrencySaveVersion
  cachedPreloadConcurrency = n
  const operation = preloadConcurrencySaveQueue
    .then(async () => {
      const result = await JmcomicService.setPreloadConcurrency(n)
      if (!result.success) throw new Error('保存失败')
      confirmedPreloadConcurrency = n
      if (version === preloadConcurrencySaveVersion) cachedPreloadConcurrency = n
    })
    .catch((error) => {
      if (version === preloadConcurrencySaveVersion) {
        cachedPreloadConcurrency = confirmedPreloadConcurrency
      }
      throw error
    })
  preloadConcurrencySaveQueue = operation.catch(() => undefined)
  return operation
}

export function persistDownloadConcurrency(n: number): Promise<void> {
  const version = ++downloadConcurrencySaveVersion
  cachedDownloadConcurrency = n
  const operation = downloadConcurrencySaveQueue
    .then(async () => {
      const result = await JmcomicService.setDownloadConcurrency(n)
      if (!result.success) throw new Error('保存失败')
      confirmedDownloadConcurrency = n
      if (version === downloadConcurrencySaveVersion) cachedDownloadConcurrency = n
    })
    .catch((error) => {
      if (version === downloadConcurrencySaveVersion) {
        cachedDownloadConcurrency = confirmedDownloadConcurrency
      }
      throw error
    })
  downloadConcurrencySaveQueue = operation.catch(() => undefined)
  return operation
}
