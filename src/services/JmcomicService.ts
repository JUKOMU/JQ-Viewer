import type {PluginListenerHandle} from '@capacitor/core'
import {registerPlugin} from '@capacitor/core'
import {alertController, toastController} from '@ionic/vue'
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
  ImportedPdfsResult,
  ImportPdfItem,
  ImportPdfsResult,
  LatencyResult,
  NetworkProbeEvent,
  OfflineFavoritesResult,
  OfflineFolderInfo,
  ParseHistoryItem,
  PdfExportTask,
  PdfScanItem,
  PhotoDetail,
  PreloadResult,
  RelocationProgress,
  SearchQuery,
  SearchResult,
  SearchResultItem,
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

  manageFavoriteFolder(options: {
    type: string
    folderId: string
    folderName?: string
    albumId?: string
  }): Promise<{ status: string; msg: string }>

  preloadImages(options: {
    photoId: string
    images: ImageInfo[]
    type: string
    replacePending?: boolean
  }): Promise<PreloadResult>

  setCacheCapacity(options: { mb: number }): Promise<{ success: boolean; capacityMb: number }>

  getCacheCapacityInfo(): Promise<CacheCapacityInfo>

  clearImageCache(): Promise<{ success: boolean }>

  setDownloadConcurrency(options: { n: number }): Promise<{ success: boolean }>

  setPreloadConcurrency(options: { n: number }): Promise<{ success: boolean }>

  setOcrEnabled(options: { enabled: boolean }): Promise<{ success: boolean }>

  setDownloadPublic(options: {
    open: boolean
  }): Promise<{ success: boolean; downloadPublic: boolean; moved: number }>

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

  addListener(
    event: 'imageReady',
    handler: (data: ImageReadyEvent) => void,
  ): Promise<PluginListenerHandle>

  addListener(
    event: 'downloadProgress',
    handler: (data: DownloadProgressEvent) => void,
  ): Promise<PluginListenerHandle>

  addListener(
    event: 'relocationProgress',
    handler: (data: RelocationProgress) => void,
  ): Promise<PluginListenerHandle>

  addListener(
    event: 'networkProbe',
    handler: (data: NetworkProbeEvent) => void,
  ): Promise<PluginListenerHandle>

  addListener(
    event: 'launchRoute',
    handler: (data: { route: string }) => void,
  ): Promise<PluginListenerHandle>

  getDomainStates(): Promise<DomainStates>

  reprobeDomains(): Promise<void>

  measureLatency(): Promise<{ results: LatencyResult[] }>

  /** 查询客户端预热是否完成 */
  getInitStatus(): Promise<{ complete: boolean }>

  login(options: { username: string; password: string }): Promise<UserInfo>

  logout(): Promise<{ success: boolean }>

  checkLoginState(): Promise<{ loggedIn: boolean; username?: string; userInfo?: UserInfo }>

  autoLogin(): Promise<{ success: boolean; userInfo?: UserInfo }>

  getUserProfile(options: { uid: string }): Promise<UserProfile>

  // 浏览历史
  getBrowseHistory(options: {
    limit: number
    offset: number
  }): Promise<{ items: BrowseHistoryItem[] }>

  recordBrowse(options: {
    albumId: string
    albumTitle: string
    coverUrl: string
    authors: string
    chapterId: string
    chapterTitle: string
  }): Promise<{ success: boolean }>

  clearBrowseHistory(): Promise<{ success: boolean }>

  deleteBrowseItem(options: { id: number }): Promise<{ success: boolean }>

  // 解析历史
  getParseHistory(options: {
    limit: number
    offset: number
  }): Promise<{ items: ParseHistoryItem[] }>

  addParseHistory(options: { text: string; mode: string }): Promise<{ success: boolean }>

  clearParseHistory(): Promise<{ success: boolean }>

  deleteParseItem(options: { id: number }): Promise<{ success: boolean }>

  // 离线收藏夹
  getOfflineFolders(): Promise<{ folders: OfflineFolderInfo[] }>

  createOfflineFolder(options: { name: string }): Promise<{ folderId: string }>

  renameOfflineFolder(options: { folderId: string; name: string }): Promise<{ success: boolean }>

  deleteOfflineFolder(options: { folderId: string }): Promise<{ success: boolean }>

  addOfflineFavorite(options: {
    folderId: string
    item: SearchResultItem
  }): Promise<{ success: boolean }>

  removeOfflineFavorite(options: {
    folderId: string
    albumId: string
  }): Promise<{ success: boolean }>

  getOfflineFavorites(options: {
    folderId: string
    keyword?: string
    page: number
    pageSize: number
  }): Promise<OfflineFavoritesResult>

  getAllOfflineFavorites(options: { folderId: string }): Promise<{ items: SearchResultItem[] }>

  getOfflineFavoritesTotalCount(): Promise<{ count: number }>

  getAllOfflineFavoritesMerged(): Promise<{ items: SearchResultItem[] }>

  moveAllOfflineFavorites(options: {
    sourceId: string
    targetId: string
  }): Promise<{ success: boolean }>

  copyOfflineFolder(options: { sourceId: string; name: string }): Promise<{ folderId: string }>

  addOfflineFavoritesBatch(options: {
    folderId: string
    items: SearchResultItem[]
  }): Promise<{ count: number }>

  mergeOfflineAllToFolder(options: { targetId: string }): Promise<{ success: boolean }>

  saveOfflineBackup(options: {
    key: string
    items: SearchResultItem[]
  }): Promise<{ success: boolean }>

  loadOfflineBackup(options: { key: string }): Promise<{ items: SearchResultItem[] | null }>

  deleteOfflineBackup(options: { key: string }): Promise<{ success: boolean }>

  listOfflineBackupKeys(): Promise<{ keys: string[] }>

  // OCR
  pickImageAndOcr(): Promise<{ text: string; error?: string }>

  // PDF 导出
  exportPdfBatch(options: { tasks: PdfExportTask[] }): Promise<{ accepted: boolean }>
  pickFolder(): Promise<{ path: string; treeUri?: string; cancelled: boolean }>
  checkFilesExist(options: { paths: string[] }): Promise<{ existing: string[] }>
  getExternalStoragePath(): Promise<{ path: string }>
  checkNotificationPermission(): Promise<{ granted: boolean }>
  requestNotificationPermission(): Promise<{ granted: boolean }>
  consumeLaunchRoute(): Promise<{ route?: string }>

  // PDF 导入
  scanPdfFiles(options: { path: string; treeUri?: string }): Promise<{ files: PdfScanItem[] }>
  importPdfs(options: { items: ImportPdfItem[] }): Promise<ImportPdfsResult>
  getImportedPdfs(): Promise<ImportedPdfsResult>
  deleteImportedPdf(options: { id: number }): Promise<{ success: boolean }>
  openPdf(options: { filePath: string }): Promise<{ success: boolean }>

  // 阅读器设置
  setReaderDisplayMode(options: { mode: string }): Promise<{ success: boolean }>
  setReaderScreenOrientation(options: { orientation: string }): Promise<{ success: boolean }>
  setReaderBrightness(options: { brightness: number }): Promise<{ success: boolean }>
  setReaderKeepScreenOn(options: { enabled: boolean }): Promise<{ success: boolean }>
  setReaderFullscreen(options: { enabled: boolean }): Promise<{ success: boolean }>
  setReaderVolumeNavigation(options: { enabled: boolean }): Promise<{ success: boolean }>

  // 阅读器状态（供音量键拦截判断）
  setReaderState(options: { isActive: boolean; isVertical: boolean }): Promise<{ success: boolean }>

  addListener(
    event: 'volumeKey',
    handler: (data: { direction: 'up' | 'down' }) => void,
  ): Promise<PluginListenerHandle>
}

interface ImageReadyEvent {
  photoId: string
  sortOrder: number
  type: string
}

const native = registerPlugin<JmcomicPlugin>('Jmcomic')

let downloadNotificationPrompted = false
let downloadNotificationPromptPromise: Promise<void> | null = null

async function ensureDownloadNotificationPermission(): Promise<void> {
  if (downloadNotificationPrompted) return
  if (downloadNotificationPromptPromise) return downloadNotificationPromptPromise

  downloadNotificationPromptPromise = (async () => {
    try {
      const check = await native.checkNotificationPermission()
      if (check.granted) {
        downloadNotificationPrompted = true
        return
      }

      const alert = await alertController.create({
        header: '需要通知权限',
        message: '章节下载将在后台进行，需要通过通知查看进度。拒绝后仍会继续下载，但不会显示系统通知。',
        buttons: [
          {text: '暂不授权', role: 'cancel'},
          {text: '允许通知', role: 'confirm'},
        ],
      })
      await alert.present()
      const dismissed = await alert.onDidDismiss()
      if (dismissed.role === 'confirm') {
        await native.requestNotificationPermission()
      }
    } catch {
      // Web 调试或旧系统异常时不阻塞下载提交。
    } finally {
      downloadNotificationPrompted = true
      downloadNotificationPromptPromise = null
    }
  })()

  return downloadNotificationPromptPromise
}

/** 虚拟 URL 基地址，与 Android 侧 ImageRegistry.VIRTUAL_HOST 一致 */
const VIRTUAL_BASE = 'https://jqviewer.local'

/** 根据 photoId、sortOrder、type 构建虚拟图片 URL */
export function getImageUrl(
  photoId: string,
  sortOrder: number,
  type: 'image' | 'thumb' = 'image',
): string {
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

  manageFavoriteFolder(
    type: string,
    folderId: string = '0',
    folderName: string = '',
    albumId: string = '',
  ) {
    return native.manageFavoriteFolder({type, folderId, folderName, albumId})
  },

  /** 登录 */
  login(username: string, password: string) {
    return native.login({username, password})
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
    return native.getUserProfile({uid})
  },

  /**
   * 预加载图片到 Android 侧 ImageRegistry。
   * @param photoId 章节 ID
   * @param images 图片元数据列表
   * @param type "image" 加载原图，"thumb" 加载缩略图
   * @returns {cached: 已在缓存中的 sortOrder[], pending: 正在下载中的 sortOrder[]}
   */
  preloadImages(
    photoId: string,
    images: ImageInfo[],
    type: 'image' | 'thumb' = 'image',
    options: { replacePending?: boolean } = {},
  ) {
    return native.preloadImages({photoId, images, type, replacePending: options.replacePending})
  },

  /** 设置图片缓存容量（MB），供设置页调用 */
  setCacheCapacity(mb: number) {
    return native.setCacheCapacity({mb})
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
    return native.setDownloadConcurrency({n})
  },

  /** 设置下载内容是否公开（系统相册可见） */
  setDownloadPublic(open: boolean) {
    return native.setDownloadPublic({open})
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
    return native.setReaderPreloadPages({n})
  },

  /** 持久化 OCR 开关 */
  setOcrEnabled(enabled: boolean) {
    return native.setOcrEnabled({enabled})
  },

  /** 持久化预加载并发数（下次启动生效） */
  setPreloadConcurrency(n: number) {
    return native.setPreloadConcurrency({n})
  },

  /**
   * 注册图片就绪监听。
   * @param photoId 当前章节 ID，仅接收此章节的事件
   * @param handler 每张图片下载完成时调用
   */
  addImageReadyListener(
    photoId: string,
    handler: (sortOrder: number) => void,
  ): Promise<PluginListenerHandle> {
    return native.addListener('imageReady', (data: ImageReadyEvent) => {
      if (data.photoId === photoId) {
        handler(data.sortOrder)
      }
    })
  },

  /**
   * 注册网络探活事件监听。
   */
  addNetworkProbeListener(
    handler: (data: NetworkProbeEvent) => void,
  ): Promise<PluginListenerHandle> {
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

  /** 查询客户端预热是否完成 */
  getInitStatus() {
    return native.getInitStatus()
  },

  // ---- 下载相关 ----

  /**
   * 提交章节下载任务。
   * @returns 返回 taskId（albumId_chapterId）
   */
  async downloadChapter(
    albumId: string,
    chapterId: string,
    albumTitle: string,
    chapterTitle: string,
    coverUrl: string,
  ) {
    await ensureDownloadNotificationPermission()
    return native.downloadChapter({albumId, chapterId, albumTitle, chapterTitle, coverUrl})
  },

  /** 获取全部下载任务列表 */
  getDownloadTasks() {
    return native.getDownloadTasks()
  },

  /** 取消/删除下载任务 */
  cancelDownload(taskId: string) {
    return native.cancelDownload({taskId})
  },

  /** 暂停下载任务 */
  pauseDownload(taskId: string) {
    return native.pauseDownload({taskId})
  },

  /** 恢复已暂停的下载任务 */
  resumeDownload(taskId: string) {
    return native.resumeDownload({taskId})
  },

  /** 删除已下载的章节（文件 + 记录） */
  deleteDownloaded(albumId: string, chapterId: string) {
    return native.deleteDownloaded({albumId, chapterId})
  },

  /** 获取离线下载的章节详情（用于离线阅读） */
  getDownloadedPhoto(albumId: string, chapterId: string) {
    return native.getDownloadedPhoto({albumId, chapterId})
  },

  /**
   * 注册下载进度监听。
   * @param handler 进度更新回调
   */
  addDownloadProgressListener(
    handler: (data: DownloadProgressEvent) => void,
  ): Promise<PluginListenerHandle> {
    return native.addListener('downloadProgress', handler)
  },

  addLaunchRouteListener(
    handler: (data: { route: string }) => void,
  ): Promise<PluginListenerHandle> {
    return native.addListener('launchRoute', handler)
  },

  /**
   * 注册文件搬迁进度监听。
   * @param handler 进度更新回调
   */
  addRelocationProgressListener(
    handler: (data: RelocationProgress) => void,
  ): Promise<PluginListenerHandle> {
    return native.addListener('relocationProgress', handler)
  },

  // ========== 浏览历史 ==========

  getBrowseHistory(limit: number, offset: number = 0) {
    return native.getBrowseHistory({limit, offset})
  },
  recordBrowse(item: Omit<BrowseHistoryItem, 'id' | 'timestamp'>) {
    return native.recordBrowse(item)
  },
  clearBrowseHistory() {
    return native.clearBrowseHistory()
  },

  deleteBrowseItem(id: number) {
    return native.deleteBrowseItem({id})
  },

  // ========== 解析历史 ==========

  getParseHistory(limit: number, offset: number = 0) {
    return native.getParseHistory({limit, offset})
  },
  addParseHistory(text: string, mode: string) {
    return native.addParseHistory({text, mode})
  },
  clearParseHistory() {
    return native.clearParseHistory()
  },

  deleteParseItem(id: number) {
    return native.deleteParseItem({id})
  },

  // ========== 离线收藏夹 ==========

  getOfflineFolders() {
    return native.getOfflineFolders()
  },
  createOfflineFolder(name: string) {
    return native.createOfflineFolder({name})
  },
  renameOfflineFolder(folderId: string, name: string) {
    return native.renameOfflineFolder({folderId, name})
  },
  deleteOfflineFolder(folderId: string) {
    return native.deleteOfflineFolder({folderId})
  },
  addOfflineFavorite(folderId: string, item: SearchResultItem) {
    return native.addOfflineFavorite({folderId, item})
  },
  removeOfflineFavorite(folderId: string, albumId: string) {
    return native.removeOfflineFavorite({folderId, albumId})
  },
  getOfflineFavorites(folderId: string, keyword?: string, page: number = 1, pageSize: number = 20) {
    return native.getOfflineFavorites({folderId, keyword, page, pageSize})
  },
  getAllOfflineFavorites(folderId: string) {
    return native.getAllOfflineFavorites({folderId})
  },
  getOfflineFavoritesTotalCount() {
    return native.getOfflineFavoritesTotalCount()
  },
  getAllOfflineFavoritesMerged() {
    return native.getAllOfflineFavoritesMerged()
  },
  moveAllOfflineFavorites(sourceId: string, targetId: string) {
    return native.moveAllOfflineFavorites({sourceId, targetId})
  },
  copyOfflineFolder(sourceId: string, name: string) {
    return native.copyOfflineFolder({sourceId, name})
  },
  addOfflineFavoritesBatch(folderId: string, items: SearchResultItem[]) {
    return native.addOfflineFavoritesBatch({folderId, items})
  },
  mergeOfflineAllToFolder(targetId: string) {
    return native.mergeOfflineAllToFolder({targetId})
  },

  saveOfflineBackup(key: string, items: SearchResultItem[]) {
    return native.saveOfflineBackup({key, items})
  },
  loadOfflineBackup(key: string) {
    return native.loadOfflineBackup({key})
  },
  deleteOfflineBackup(key: string) {
    return native.deleteOfflineBackup({key})
  },
  listOfflineBackupKeys() {
    return native.listOfflineBackupKeys()
  },

  // ========== OCR ==========

  pickImageAndOcr() {
    return native.pickImageAndOcr()
  },

  // ========== PDF 导出 ==========

  exportPdfBatch(tasks: PdfExportTask[]) {
    return native.exportPdfBatch({ tasks })
  },

  pickFolder() {
    return native.pickFolder()
  },

  // ========== PDF 导入 ==========

  scanPdfFiles(path: string, treeUri?: string) {
    return native.scanPdfFiles({ path, treeUri })
  },

  importPdfs(items: ImportPdfItem[]) {
    return native.importPdfs({ items })
  },

  getImportedPdfs() {
    return native.getImportedPdfs()
  },

  deleteImportedPdf(id: number) {
    return native.deleteImportedPdf({ id })
  },

  openPdf(filePath: string) {
    return native.openPdf({ filePath })
  },

  checkFilesExist(paths: string[]) {
    return native.checkFilesExist({ paths })
  },

  getExternalStoragePath() {
    return native.getExternalStoragePath()
  },

  checkNotificationPermission() {
    return native.checkNotificationPermission()
  },

  requestNotificationPermission() {
    return native.requestNotificationPermission()
  },

  consumeLaunchRoute() {
    return native.consumeLaunchRoute()
  },

  // ========== 阅读器设置 ==========

  setReaderDisplayMode(mode: string) {
    return native.setReaderDisplayMode({mode})
  },
  setReaderScreenOrientation(orientation: string) {
    return native.setReaderScreenOrientation({orientation})
  },
  setReaderBrightness(brightness: number) {
    return native.setReaderBrightness({brightness})
  },
  setReaderKeepScreenOn(enabled: boolean) {
    return native.setReaderKeepScreenOn({enabled})
  },
  setReaderFullscreen(enabled: boolean) {
    return native.setReaderFullscreen({enabled})
  },
  setReaderVolumeNavigation(enabled: boolean) {
    return native.setReaderVolumeNavigation({enabled})
  },

  // ========== 阅读器状态 ==========

  setReaderState(isActive: boolean, isVertical: boolean) {
    return native.setReaderState({isActive, isVertical})
  },

  addVolumeKeyListener(
    handler: (direction: 'up' | 'down') => void,
  ): Promise<PluginListenerHandle> {
    return native.addListener('volumeKey', (data: { direction: 'up' | 'down' }) => {
      handler(data.direction)
    })
  },
}

/** 清洗错误消息，将技术性错误替换为用户友好提示 */
export function sanitizeError(error: unknown, fallback: string): string {
  const msg =
    error instanceof Error ? error.message : typeof error === 'string' && error ? error : null
  if (msg) {
    if (/mysql|database|syntax|sql|jdbc|JsonSyntax/i.test(msg)) return '服务器繁忙，请稍后重试'
    return msg
  }
  return fallback
}

/** 显示简短 toast 提示 */
export async function showToast(
  message: string,
  color: 'success' | 'danger' | 'medium' = 'medium',
  duration: number = 1500,
  position: "top" | "bottom" | "middle" = "bottom"
) {
  const toast = await toastController.create({
    message: message,
    duration: duration,
    position: position,
    cssClass: `warm-toast toast-${color}`,
  })
  await toast.present()
  return toast
}
