// --- 用户相关 ---

export interface UserInfo {
  uid: string
  username: string
  email: string
  emailVerified: boolean
  avatarUrl: string
  firstName: string
  gender: string
  message: string
  level: number
  levelName: string
  nextLevelExp: number
  currentExp: number
  expPercent: number
  coin: number
  albumFavorites: number
  maxAlbumFavorites: number
}

/** 用户个人资料 */
export interface UserProfile {
  username: string
  email: string
  nickname: string
  birthday: string
  city: string
  country: string
  occupation: string
  aboutMe: string
  website: string
}

// --- 搜索 ---

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
  cached: number[] // sortOrders already in cache
  pending: number[] // sortOrders being downloaded
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
  ocrEnabled: boolean
  readerDisplayMode: string
  readerScreenOrientation: string
  readerBrightness: number
  readerKeepScreenOn: boolean
  readerVolumeNavigation: boolean
}

// --- 设置页：文件搬迁 ---

export interface RelocationProgress {
  current: number // 已完成文件数
  total: number // 文件总数
  phase: 'copying' | 'verifying' | 'deleting' | 'scanning'
  currentFile?: string // 当前文件名，如 "albumId/chapterId/05.jpg"
}

// --- 收藏夹类型 ---

export interface FavoriteQuery {
  folderId: string // 收藏夹ID，"0"=全部
  page: number
  keyword?: string // 收藏夹内搜索关键词
}

export interface FavoriteResult {
  folderName: string
  folderId: string
  currentPage: number
  totalItems: number
  totalPages: number
  content: SearchResultItem[]
  folderList: Record<string, string> // id -> name
}

/** 收藏夹文件夹条目（在线/离线通用） */
export interface FolderEntry {
  id: string
  name: string
  count: number
}

/** 离线"全部"收藏夹虚拟 ID */
export const OFFLINE_ALL_FOLDER_ID = 'offline_all'

// --- 收藏夹管理 ---

export type FavoriteFolderManageType = 'add' | 'edit' | 'move' | 'del'

export interface FavoriteFolderManageOptions {
  type: FavoriteFolderManageType
  folderId: string
  folderName?: string
  albumId?: string
}

export interface FavoriteFolderManageResult {
  status: string // "ok" on success
  msg: string
}

// --- 离线收藏夹 ---

export interface OfflineFolderInfo {
  folderId: string
  name: string
  count: number
}

export interface OfflineFavoritesResult {
  totalItems: number
  totalPages: number
  currentPage: number
  content: SearchResultItem[]
}

// --- 下载类型 ---

/** 构建下载任务 ID */
export function makeTaskId(albumId: string, chapterId: string): string {
  return albumId + '_' + chapterId
}

export type DownloadStatus = 'queued' | 'downloading' | 'paused' | 'completed' | 'failed'

export interface DownloadTask {
  taskId: string // "albumId_chapterId"
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
  chapters: DownloadTask[] // 按 chapterSortOrder 升序
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

// --- 网络探活事件 ---

/**
 * getDomainStates() 返回的当前域名状态快照。
 */
export interface DomainStates {
  domains: { domain: string; reachable: boolean }[]
  alive: number
  total: number
  allDeadFallback: boolean
}

/**
 * 网络探活事件。由 Android 侧 notifyListeners("networkProbe") 推送。
 * - phase=network_changed|network_lost|probing|error: 仅含 message + timestamp
 * - phase=result: 额外含 domains (域名+可达性) + alive (可达数) + total (总数) + allDeadFallback (全死回退标记)
 */
export interface NetworkProbeEvent {
  phase: 'network_changed' | 'network_lost' | 'probing' | 'result' | 'error'
  message: string
  timestamp: number
  domains?: { domain: string; reachable: boolean }[]
  alive?: number
  total?: number
  allDeadFallback?: boolean
}

// --- 网络测速 ---

/** 单域名延迟测试结果 */
export interface LatencyResult {
  domain: string
  latencyMs: number
  timedOut: boolean
}

// --- 历史记录 ---

/** 浏览历史条目 */
export interface BrowseHistoryItem {
  id: number
  albumId: string
  albumTitle: string
  coverUrl: string
  authors: string
  chapterId: string
  chapterTitle: string
  timestamp: number
}

/** 解析历史条目 */
export interface ParseHistoryItem {
  id: number
  text: string
  timestamp: number
  mode: 'single-mode' | 'batch-mode'
}

// --- PDF 导出 ---

export interface PdfExportTask {
  albumId: string
  chapterId: string
  chapterTitle: string       // 用于通知显示
  savePath: string           // 完整路径（含文件名.pdf）
  useOriginal: boolean
  compressionRatio: number   // 0.1~1.0
  splitPages: number         // 0=不分卷, >0=每卷页数
}
