/**
 * 应用设置持久化服务（localStorage）。
 * 所有设置项均有默认值，读取时若不存在则返回默认值。
 */

const KEYS = {
    READER_PRELOAD_PAGES: 'settings_reader_preload_pages',
    DOWNLOAD_CONCURRENCY: 'settings_download_concurrency',
    DOWNLOAD_PUBLIC: 'settings_download_public',
} as const

function getNumber(key: string, defaultVal: number): number {
    try {
        const raw = localStorage.getItem(key)
        if (raw === null) return defaultVal
        const n = parseInt(raw, 10)
        return Number.isFinite(n) && n > 0 ? n : defaultVal
    } catch { return defaultVal }
}

function getBool(key: string, defaultVal: boolean): boolean {
    try {
        const raw = localStorage.getItem(key)
        if (raw === null) return defaultVal
        return raw === 'true'
    } catch { return defaultVal }
}

export const SettingsStore = {
    // ---- 阅读：预加载页数 ----
    getReaderPreloadPages(): number {
        return getNumber(KEYS.READER_PRELOAD_PAGES, 15)
    },
    setReaderPreloadPages(n: number) {
        localStorage.setItem(KEYS.READER_PRELOAD_PAGES, String(n))
    },

    // ---- 下载：并发线程数 ----
    getDownloadConcurrency(): number {
        return getNumber(KEYS.DOWNLOAD_CONCURRENCY, 6)
    },
    setDownloadConcurrency(n: number) {
        localStorage.setItem(KEYS.DOWNLOAD_CONCURRENCY, String(n))
    },

    // ---- 下载：是否公开下载内容 ----
    getDownloadPublic(): boolean {
        return getBool(KEYS.DOWNLOAD_PUBLIC, false)
    },
    setDownloadPublic(open: boolean) {
        localStorage.setItem(KEYS.DOWNLOAD_PUBLIC, String(open))
    },
}
