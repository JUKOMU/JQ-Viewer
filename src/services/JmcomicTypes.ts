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
  isSingleEpisode?: boolean
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
  isSingleEpisode?: boolean
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
  requestedMb?: number
  effectiveMb?: number
  maxHeapMb?: number
  safeRatio?: number
  pressureLevel?: string
  temporaryClamp?: boolean
  limitReason?: string
}

export interface ImageCacheEntry {
  photoId: string
  sortOrder: number
  type: 'image' | 'thumb'
  sizeBytes: number
  mimeType: string
}

export interface AllSettings {
  readerPreloadPages: number
  preloadConcurrency: number
  downloadConcurrency: number
  downloadPublic: boolean
  cacheCapacityMb: number
  cacheRequestedMb?: number
  cacheEffectiveMb?: number
  cacheMaxHeapMb?: number
  cacheTemporaryClamp?: boolean
  cacheLimitReason?: string
  ocrEnabled: boolean
  readerDisplayMode: string
  readerScreenOrientation: string
  readerBrightness: number
  readerKeepScreenOn: boolean
  readerVolumeNavigation: boolean
  readerAutoShowToolbarAtEnd?: boolean
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

export type DownloadStatus =
  | 'queued'
  | 'downloading'
  | 'paused'
  | 'verifying'
  | 'completed'
  | 'failed'
  | 'cancelled'

export interface DownloadTask {
  taskId: string // "albumId_chapterId"
  albumId: string
  chapterId: string
  albumTitle: string
  chapterTitle: string
  coverUrl: string
  firstImageSortOrder?: number
  chapterSortOrder?: number
  isSingleEpisode?: boolean
  totalPages: number
  downloadedPages: number
  status: DownloadStatus
  createdAt: number
  completedAt?: number
  error?: string
  speed?: number
  downloadedBytes?: number
  totalSize?: number
}

export interface CompletedGroup {
  type: 'single' | 'multi'
  albumId: string
  albumTitle: string
  coverUrl: string
  chapters: CompletedEntry[] // 按 chapterSortOrder 升序
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
  downloadedBytes?: number
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

// --- 应用内更新 ---

export interface UpdateManifest {
  tag: string
  versionName: string
  versionCode: number
  packageName: string
  apkName: string
  sizeBytes: number
  sha256: string
  signingCertificateSha256: string
  releaseNotes: string
  sources: {
    github: string
    gitee: string
  }
}

export type UpdatePhase =
  | 'idle'
  | 'racing'
  | 'selected'
  | 'verifying'
  | 'ready_to_install'
  | 'install_permission_required'
  | 'installing'
  | 'failed'
  | 'cancelled'
  | 'up_to_date'
  | 'update_available'

export interface UpdateProgressEvent {
  revision: number
  phase: UpdatePhase
  source: string
  githubBytes: number
  giteeBytes: number
  totalBytes: number
  speedBytesPerSecond: number
  error: string
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

/** 章节阅读进度：独立于浏览历史和 App 路由恢复。 */
export interface ReadingProgressItem {
  albumId: string
  chapterId: string
  page: number
  totalPages: number
  updatedAt: number
}

// --- PDF 导出 ---

export type PdfExportMode = 'chapter' | 'merged'

export interface PdfExportChapter {
  albumId: string
  chapterId: string
  chapterTitle: string
  sortOrder: number
}

export interface PdfExportTask {
  mode: PdfExportMode
  albumId: string
  albumTitle?: string
  coverUrl?: string
  authors?: string
  isSingleEpisode?: boolean
  chapterId?: string
  chapterTitle: string // 用于通知显示
  chapters?: PdfExportChapter[]
  savePath: string // 完整路径（含文件名.pdf）
  useOriginal: boolean
  compressionRatio: number // 0.1~1.0
  splitPages: number // 0=不分卷, >0=每卷页数
  allowOverwrite?: boolean
}

export type PdfExportStatus =
  | 'queued'
  | 'running'
  | 'cancelling'
  | 'cancelled'
  | 'completed'
  | 'partial'
  | 'failed'
  | 'interrupted'

export interface PdfExportProgressEvent {
  exportId: string
  batchId: string
  status: PdfExportStatus
  phase: string
  currentPage: number
  totalPages: number
  currentVolume: number
  totalVolumes: number
  snapshotRevision: number
  errorCode?: string
  errorMessage?: string
}

export interface PdfExportTaskRecord extends PdfExportProgressEvent {
  mode: PdfExportMode
  albumId: string
  albumTitle: string
  coverUrl: string
  authors: string
  isSingleEpisode?: boolean
  chapterId?: string
  displayTitle: string
  savePath: string
  allowOverwrite: boolean
  useOriginal: boolean
  compressionRatio: number
  splitPages: number
  cancelRequested: boolean
  createdAt: number
  startedAt?: number
  updatedAt: number
  completedAt?: number
}

export type PdfExportSubmissionTaskResult = Partial<PdfExportTaskRecord> & {
  accepted: boolean
  errorCode?: string
  errorMessage?: string
}

export interface PdfExportBatchResult {
  tasks: PdfExportSubmissionTaskResult[]
}

export interface PdfManagementState {
  recoveryState: 'ready'
  databaseResetInfo?: {
    pending: boolean
    resetAt?: number
    fromVersion?: number
    reason?: string
  }
}

// --- PDF 导入 ---

/** scanPdfFiles 返回的单个 PDF 文件条目 */
export interface PdfScanItem {
  fileName: string
  filePath: string
}

/** 已导入的 PDF 记录（从数据库返回） */
export interface ImportedPdf {
  id: number
  filePath: string
  fileName: string
  sourceType: 'imported' | 'exported'
  ownership: 'external_reference' | 'app_created'
  chapterLinkStatus: 'resolved' | 'unresolved' | 'multi_chapter'
  albumId: string
  albumTitle: string
  coverUrl: string
  authors: string
  chapterId?: string
  chapterTitle: string
  chapterSortOrder: number
  isSingleEpisode?: boolean
  createdAt: number
  folderId?: string
  fileSize: number
  pageCount: number
  availability: 'unknown' | 'available' | 'missing' | 'inaccessible' | 'invalid'
  verificationStatus: 'unverified' | 'valid' | 'corrupt' | 'page_mismatch'
  verificationError?: string
  updatedAt: number
  verifiedAt?: number
}

export interface PdfStorageDeleteResult {
  result: 'deleted' | 'already_missing'
  id: number
  sourceType: ImportedPdf['sourceType']
  ownership: ImportedPdf['ownership']
  filePath: string
  fileName: string
}

/** importPdfs 调用的导入项 */
export interface ImportPdfItem {
  filePath: string
  fileName: string
  albumId: string
  albumTitle: string
  coverUrl: string
  authors: string
  chapterId: string
  chapterTitle: string
  chapterSortOrder: number
  isSingleEpisode?: boolean
  folderId?: string
}

export interface ImportPdfsResult {
  imported: number
  skipped: number
  duplicateCount: number
  errorCount: number
}

export interface ImportedPdfsResult {
  pdfs: ImportedPdf[]
}

// --- 已完成区统一展示类型 ---

/**
 * 下载页面"已完成"区域的统一展示类型。
 * 图片下载（DownloadTask）和 PDF 导入各自映射为此类型，下游组件只接触 CompletedEntry。
 */
export interface CompletedEntry {
  albumId: string
  albumTitle: string
  coverUrl: string
  chapterId: string // download: chapterId; pdf: 内部唯一 key
  displayId?: string // chapterId 不是用户可见 ID 时的展示兜底
  chapterTitle: string // download: chapterTitle; pdf: fileName
  chapterSortOrder: number
  isSingleEpisode?: boolean
  authors: string
  createdAt: number
  completedAt: number
  totalSize: number // download: totalSize; pdf: 0
  source: 'download' | 'pdf-import'
  downloadTask?: DownloadTask // source='download' 时的原始数据
  pdfData?: ImportedPdf // source='pdf-import' 时的原始数据
}
