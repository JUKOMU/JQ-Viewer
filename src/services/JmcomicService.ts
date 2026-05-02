import {registerPlugin} from '@capacitor/core'
import type {PluginListenerHandle} from '@capacitor/core'
import type {
    AlbumDetail,
    CacheCapacityInfo,
    CommentList,
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
    toggleAlbumLike(options: { id: string }): Promise<{ success: boolean }>
    toggleAlbumFavorite(options: { id: string; folderId: string }): Promise<{ success: boolean }>
    preloadImages(options: { photoId: string; images: ImageInfo[]; type: string }): Promise<PreloadResult>
    setCacheCapacity(options: { mb: number }): Promise<{ success: boolean; capacityMb: number }>
    getCacheCapacityInfo(): Promise<CacheCapacityInfo>
    addListener(event: 'imageReady', handler: (data: ImageReadyEvent) => void): Promise<PluginListenerHandle>
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
}
