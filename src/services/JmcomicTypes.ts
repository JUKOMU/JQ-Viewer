export interface SearchQuery {
    keyword?: string
    category?: string
    orderBy: string
    time: string
    searchMainTag: number
    page?: number
}

export interface SearchResultItem {
    id: string
    title: string
    coverUrl: string
    authors: string[]
    tags: string[]
}

export interface SearchResult {
    currentPage: number
    totalItems: number
    totalPages: number
    content: SearchResultItem[]
}

// --- 详情页类型 ---

export interface CategoryMeta {
    id: string
    title: string
}

export interface PhotoMeta {
    id: string
    title: string
    sortOrder: number
}

export interface ImageInfo {
    photoId: string
    scrambleId: string
    filename: string
    url: string
    queryParams: string
    sortOrder: number
}

export interface PhotoDetail {
    id: string
    title: string
    albumId: string
    sortOrder: number
    author: string
    tags: string[]
    images: ImageInfo[]
}

export interface AlbumDetail {
    id: string
    title: string
    description: string
    addTime: string
    pageCount: number
    likes: string
    views: string
    commentCount: number
    image: string
    category: CategoryMeta | null
    subCategory: CategoryMeta | null
    authors: string[]
    works: string[]
    actors: string[]
    tags: string[]
    relatedAlbums: AlbumMeta[]
    photoMetas: PhotoMeta[]
    seriesId: string
    isFavorite: boolean
    isLiked: boolean
    price: string
    purchased: string
}

export interface AlbumMeta {
    id: string
    title: string
    coverUrl: string
    authors: string[]
    tags: string[]
    description: string
    image: string
    category: CategoryMeta | null
    subCategory: CategoryMeta | null
}

export interface CommentItem {
    commentId: string
    userId: string
    username: string
    nickname: string
    content: string
    postDate: string
    photo: string
    expinfo: string
    aid: string
    name: string
    likes: number
    voteUp: number
    voteDown: number
    replys: CommentItem[]
}

export interface CommentList {
    total: number
    list: CommentItem[]
}

export interface ForumQuery {
    albumId: string
    page: number
}

// --- 图片预加载与缓存 ---

export interface PreloadResult {
    cached: number[]   // sortOrders already in cache
    pending: number[]  // sortOrders being downloaded
}

export interface CacheCapacityInfo {
    capacityMb: number
    usedMb: number
}

export interface AllSettings {
    readerPreloadPages: number
    preloadConcurrency: number
    downloadConcurrency: number
    downloadPublic: boolean
    cacheCapacityMb: number
}

// --- 设置页：文件搬迁 ---

export interface RelocationProgress {
    current: number      // 已完成文件数
    total: number        // 文件总数
    phase: 'copying' | 'verifying' | 'deleting' | 'scanning'
    currentFile?: string // 当前文件名，如 "albumId/chapterId/05.jpg"
}

// --- 收藏夹类型 ---

export interface FavoriteQuery {
    folderId: string  // 收藏夹ID，"0"=全部
    page: number
    keyword?: string  // 收藏夹内搜索关键词
}

export interface FavoriteResult {
    folderName: string
    folderId: number
    currentPage: number
    totalItems: number
    totalPages: number
    content: SearchResultItem[]
    folderList: Record<string, string>  // id -> name
}

// --- 下载类型 ---

/** 构建下载任务 ID */
export function makeTaskId(albumId: string, chapterId: string): string {
    return albumId + '_' + chapterId
}

export type DownloadStatus = 'queued' | 'downloading' | 'paused' | 'completed' | 'failed'

export interface DownloadTask {
    taskId: string          // "albumId_chapterId"
    albumId: string
    chapterId: string
    albumTitle: string
    chapterTitle: string
    coverUrl: string
    firstImageSortOrder?: number
    chapterSortOrder?: number
    totalPages: number
    downloadedPages: number
    status: DownloadStatus
    createdAt: number
    completedAt?: number
    error?: string
    speed?: number
    totalSize?: number
}

export interface CompletedGroup {
    type: 'single' | 'multi'
    albumId: string
    albumTitle: string
    coverUrl: string
    chapters: DownloadTask[]     // 按 chapterSortOrder 升序
    totalSize: number
}

export interface DownloadTasksResult {
    tasks: DownloadTask[]
    usedBytes: number
    availableBytes: number
}

export interface DownloadProgressEvent {
    taskId: string
    albumId: string
    chapterId: string
    downloadedPages: number
    totalPages: number
    status: DownloadStatus
    error?: string
    speed: number
    totalSize?: number
}
