import {registerPlugin} from '@capacitor/core'
import type {PluginListenerHandle} from '@capacitor/core'
import { toastController } from '@ionic/vue'
import type {
    AlbumDetail,
    CacheCapacityInfo,
    CommentList,
    DownloadProgressEvent,
    DownloadTasksResult,
    FavoriteQuery,
    FavoriteResult,
    ForumQuery,
    ImageInfo,
    PhotoDetail,
    PreloadResult,
    SearchQuery,
    SearchResult,
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
    setDownloadConcurrency(options: { n: number }): Promise<{ success: boolean; concurrency: number }>
    setDownloadPublic(options: { open: boolean }): Promise<{ success: boolean; downloadPublic: boolean }>
    getDownloadPublic(): Promise<{ downloadPublic: boolean }>
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
}

/** 显示简短 toast 提示 */
export async function showToast(message: string, color: 'success' | 'danger' | 'medium' = 'medium') {
    const toast = await toastController.create({
        message, duration: 1500, position: 'middle', color,
    })
    await toast.present()
}
