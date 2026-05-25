import {registerPlugin} from '@capacitor/core'
import type {PluginListenerHandle} from '@capacitor/core'
import { toastController } from '@ionic/vue'
import type {
    AlbumDetail,
    AllSettings,
    BrowseHistoryItem,
    CacheCapacityInfo,
    CommentList,
    DomainStates,
    DownloadProgressEvent,
    DownloadTasksResult,
    FavoriteQuery,
    FavoriteResult,
    ForumQuery,
    ImageInfo,
    LatencyResult,
    NetworkProbeEvent,
    ParseHistoryItem,
    PhotoDetail,
    PreloadResult,
    RelocationProgress,
    SearchQuery,
    SearchResult,
    UserInfo,
    UserProfile,
} from './JmcomicTypes'

interface JmcomicPlugin {
    search(options: { query: SearchQuery }): Promise<SearchResult>
    categories(options: { query: SearchQuery }): Promise<SearchResult>
    getAlbum(options: { id: string }): Promise<AlbumDetail>
    getPhoto(options: { id: string }): Promise<PhotoDetail>
    getComments(options: ForumQuery): Promise<CommentList>
    getFavorites(options: { query: FavoriteQuery }): Promise<FavoriteResult>
    toggleAlbumLike(options: { id: string }): Promise<{ success: boolean }>
    toggleAlbumFavorite(options: { id: string; folderId: string }): Promise<{ success: boolean }>
    preloadImages(options: { photoId: string; images: ImageInfo[]; type: string }): Promise<PreloadResult>
    setCacheCapacity(options: { mb: number }): Promise<{ success: boolean; capacityMb: number }>
    getCacheCapacityInfo(): Promise<CacheCapacityInfo>
    clearImageCache(): Promise<{ success: boolean }>
    setDownloadConcurrency(options: { n: number }): Promise<{ success: boolean }>
    setPreloadConcurrency(options: { n: number }): Promise<{ success: boolean }>
    setOcrEnabled(options: { enabled: boolean }): Promise<{ success: boolean }>
    setDownloadPublic(options: { open: boolean }): Promise<{ success: boolean; downloadPublic: boolean; moved: number }>
    getDownloadPublic(): Promise<{ downloadPublic: boolean }>
    requestManageStorage(): Promise<{ granted: boolean; permissionType: string; apiLevel: number }>
    getAllSettings(): Promise<AllSettings>
    setReaderPreloadPages(options: { n: number }): Promise<{ success: boolean }>
    downloadChapter(options: {
        albumId: string
        chapterId: string
        albumTitle: string
        chapterTitle: string
        coverUrl: string
    }): Promise<{ taskId: string }>
    getDownloadTasks(): Promise<DownloadTasksResult>
    cancelDownload(options: { taskId: string }): Promise<{ success: boolean }>
    pauseDownload(options: { taskId: string }): Promise<{ success: boolean }>
    resumeDownload(options: { taskId: string }): Promise<{ success: boolean }>
    deleteDownloaded(options: { albumId: string; chapterId: string }): Promise<{ success: boolean }>
    getDownloadedPhoto(options: { albumId: string; chapterId: string }): Promise<PhotoDetail>
    addListener(event: 'imageReady', handler: (data: ImageReadyEvent) => void): Promise<PluginListenerHandle>
    addListener(event: 'downloadProgress', handler: (data: DownloadProgressEvent) => void): Promise<PluginListenerHandle>
    addListener(event: 'relocationProgress', handler: (data: RelocationProgress) => void): Promise<PluginListenerHandle>
    addListener(event: 'networkProbe', handler: (data: NetworkProbeEvent) => void): Promise<PluginListenerHandle>
    getDomainStates(): Promise<DomainStates>
    reprobeDomains(): Promise<void>
    measureLatency(): Promise<{ results: LatencyResult[] }>
    login(options: { username: string; password: string }): Promise<UserInfo>
    logout(): Promise<{ success: boolean }>
    checkLoginState(): Promise<{ loggedIn: boolean; username?: string; userInfo?: UserInfo }>
    autoLogin(): Promise<{ success: boolean; userInfo?: UserInfo; reason?: string }>
    getUserProfile(options: { uid: string }): Promise<UserProfile>
    // 浏览历史
    getBrowseHistory(options: { limit: number; offset: number }): Promise<{ items: BrowseHistoryItem[] }>
    recordBrowse(options: {
        albumId: string
        albumTitle: string
        coverUrl: string
        authors: string
        chapterId: string
        chapterTitle: string
    }): Promise<{ success: boolean }>
    clearBrowseHistory(): Promise<{ success: boolean }>
    // 解析历史
    getParseHistory(options: { limit: number; offset: number }): Promise<{ items: ParseHistoryItem[] }>
    addParseHistory(options: { text: string }): Promise<{ success: boolean }>
    clearParseHistory(): Promise<{ success: boolean }>
    // OCR
    pickImageAndOcr(): Promise<{ text: string; error?: string }>
}

interface ImageReadyEvent {
    photoId: string
    sortOrder: number
    type: string
}

const native = registerPlugin<JmcomicPlugin>('Jmcomic')

/** 虚拟 URL 基地址，与 Android 侧 ImageRegistry.VIRTUAL_HOST 一致 */
const VIRTUAL_BASE = 'https://jqviewer.local'

/** 根据 photoId、sortOrder、type 构建虚拟图片 URL */
export function getImageUrl(photoId: string, sortOrder: number, type: 'image' | 'thumb' = 'image'): string {
    return `${VIRTUAL_BASE}/${type}/${photoId}/${sortOrder}`
}

export const JmcomicService = {
    native,
    search(query: SearchQuery) {
        return native.search({query})
    },
    categories(query: SearchQuery) {
        return native.categories({query})
    },
    getAlbum(id: string) {
        return native.getAlbum({id})
    },
    getPhoto(id: string) {
        return native.getPhoto({id})
    },
    getComments(query: ForumQuery) {
        return native.getComments(query)
    },
    toggleAlbumLike(id: string) {
        return native.toggleAlbumLike({id})
    },
    favorites(query: FavoriteQuery) {
        return native.getFavorites({query})
    },
    toggleAlbumFavorite(id: string, folderId: string = '0') {
        return native.toggleAlbumFavorite({id, folderId})
    },

    /** 登录 */
    login(username: string, password: string) {
        return native.login({ username, password })
    },

    /** 登出 */
    logout() {
        return native.logout()
    },

    /** 检查本地登录态（不涉及网络） */
    checkLoginState() {
        return native.checkLoginState()
    },

    /** 自动登录（使用保存的凭据，重启时调用） */
    autoLogin() {
        return native.autoLogin()
    },

    /** 获取用户个人资料 */
    getUserProfile(uid: string) {
        return native.getUserProfile({ uid })
    },

    /**
     * 预加载图片到 Android 侧 ImageRegistry。
     * @param photoId 章节 ID
     * @param images 图片元数据列表
     * @param type "image" 加载原图，"thumb" 加载缩略图
     * @returns {cached: 已在缓存中的 sortOrder[], pending: 正在下载中的 sortOrder[]}
     */
    preloadImages(photoId: string, images: ImageInfo[], type: 'image' | 'thumb' = 'image') {
        return native.preloadImages({ photoId, images, type })
    },

    /** 设置图片缓存容量（MB），供设置页调用 */
    setCacheCapacity(mb: number) {
        return native.setCacheCapacity({ mb })
    },

    /** 查询缓存容量状态，供设置页展示 */
    getCacheCapacityInfo() {
        return native.getCacheCapacityInfo()
    },

    /** 清空全部图片缓存 */
    clearImageCache() {
        return native.clearImageCache()
    },

    /** 设置下载并发数（1-12） */
    setDownloadConcurrency(n: number) {
        return native.setDownloadConcurrency({ n })
    },

    /** 设置下载内容是否公开（系统相册可见） */
    setDownloadPublic(open: boolean) {
        return native.setDownloadPublic({ open })
    },

    /** 查询下载内容是否公开 */
    getDownloadPublic() {
        return native.getDownloadPublic()
    },

    /** 请求"所有文件访问权限"（公开下载需要） */
    requestManageStorage() {
        return native.requestManageStorage()
    },

    /** 获取全部设置（启动时一次性加载） */
    getAllSettings() {
        return native.getAllSettings()
    },

    /** 持久化阅读器预加载页数 */
    setReaderPreloadPages(n: number) {
        return native.setReaderPreloadPages({ n })
    },

    /** 持久化 OCR 开关 */
    setOcrEnabled(enabled: boolean) {
        return native.setOcrEnabled({ enabled })
    },

    /** 持久化预加载并发数（下次启动生效） */
    setPreloadConcurrency(n: number) {
        return native.setPreloadConcurrency({ n })
    },

    /**
     * 注册图片就绪监听。
     * @param photoId 当前章节 ID，仅接收此章节的事件
     * @param handler 每张图片下载完成时调用
     */
    addImageReadyListener(photoId: string, handler: (sortOrder: number) => void): Promise<PluginListenerHandle> {
        return native.addListener('imageReady', (data: ImageReadyEvent) => {
            if (data.photoId === photoId) {
                handler(data.sortOrder)
            }
        })
    },

    /**
     * 注册网络探活事件监听。
     */
    addNetworkProbeListener(handler: (data: NetworkProbeEvent) => void): Promise<PluginListenerHandle> {
        return native.addListener('networkProbe', handler)
    },

    /** 读取 domainManager 中已有的域名连通性状态（同步返回，不触发探活） */
    getDomainStates() {
        return native.getDomainStates()
    },

    /** 手动触发域名重新探活（结果通过 networkProbe 事件推送） */
    reprobeDomains() {
        return native.reprobeDomains()
    },

    /** 对可达域名进行延迟测试（HEAD 请求计时，并行执行） */
    measureLatency() {
        return native.measureLatency()
    },

    // ---- 下载相关 ----

    /**
     * 提交章节下载任务。
     * @returns 返回 taskId（albumId_chapterId）
     */
    downloadChapter(albumId: string, chapterId: string,
                     albumTitle: string, chapterTitle: string, coverUrl: string) {
        return native.downloadChapter({ albumId, chapterId, albumTitle, chapterTitle, coverUrl })
    },

    /** 获取全部下载任务列表 */
    getDownloadTasks() {
        return native.getDownloadTasks()
    },

    /** 取消/删除下载任务 */
    cancelDownload(taskId: string) {
        return native.cancelDownload({ taskId })
    },

    /** 暂停下载任务 */
    pauseDownload(taskId: string) {
        return native.pauseDownload({ taskId })
    },

    /** 恢复已暂停的下载任务 */
    resumeDownload(taskId: string) {
        return native.resumeDownload({ taskId })
    },

    /** 删除已下载的章节（文件 + 记录） */
    deleteDownloaded(albumId: string, chapterId: string) {
        return native.deleteDownloaded({ albumId, chapterId })
    },

    /** 获取离线下载的章节详情（用于离线阅读） */
    getDownloadedPhoto(albumId: string, chapterId: string) {
        return native.getDownloadedPhoto({ albumId, chapterId })
    },

    /**
     * 注册下载进度监听。
     * @param handler 进度更新回调
     */
    addDownloadProgressListener(handler: (data: DownloadProgressEvent) => void): Promise<PluginListenerHandle> {
        return native.addListener('downloadProgress', handler)
    },

    /**
     * 注册文件搬迁进度监听。
     * @param handler 进度更新回调
     */
    addRelocationProgressListener(handler: (data: RelocationProgress) => void): Promise<PluginListenerHandle> {
        return native.addListener('relocationProgress', handler)
    },

    // ========== 浏览历史 ==========

    getBrowseHistory(limit: number, offset: number = 0) {
        return native.getBrowseHistory({ limit, offset })
    },
    recordBrowse(item: Omit<BrowseHistoryItem, 'timestamp'>) {
        return native.recordBrowse(item)
    },
    clearBrowseHistory() {
        return native.clearBrowseHistory()
    },

    // ========== 解析历史 ==========

    getParseHistory(limit: number, offset: number = 0) {
        return native.getParseHistory({ limit, offset })
    },
    addParseHistory(text: string) {
        return native.addParseHistory({ text })
    },
    clearParseHistory() {
        return native.clearParseHistory()
    },

    // ========== OCR ==========

    pickImageAndOcr() {
        return native.pickImageAndOcr()
    },
}

/** 显示简短 toast 提示 */
export async function showToast(message: string, color: 'success' | 'danger' | 'medium' = 'medium') {
    const toast = await toastController.create({
        message, duration: 1500, position: 'middle', color,
    })
    await toast.present()
}
