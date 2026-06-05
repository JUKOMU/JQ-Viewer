import {platformCapabilities} from '../../platform/activeCapabilities'
import type {
  BrowseHistoryItem,
  DownloadProgressEvent,
  FavoriteQuery,
  ForumQuery,
  ImageInfo,
  ImportPdfItem,
  NetworkProbeEvent,
  PdfExportTask,
  RelocationProgress,
  SearchQuery,
  SearchResultItem,
} from '../JmcomicTypes'
import type {JmcomicClient, JmcomicListenerHandle} from './JmcomicClient'
import {isDesktopRuntime} from '../../platform/runtime'
import {jmcomicDesktopClient} from './JmcomicDesktopClient'
import {jmcomicNativeClient} from './JmcomicNativeClient'

const native: JmcomicClient = isDesktopRuntime ? jmcomicDesktopClient : jmcomicNativeClient

export const JmcomicService = {
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
    return platformCapabilities.filePicker.requestManageStorage()
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
  ): Promise<JmcomicListenerHandle> {
    return platformCapabilities.events.addImageReadyListener(photoId, handler)
  },

  /**
   * 注册网络探活事件监听。
   */
  addNetworkProbeListener(
    handler: (data: NetworkProbeEvent) => void,
  ): Promise<JmcomicListenerHandle> {
    return platformCapabilities.events.addNetworkProbeListener(handler)
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
    await platformCapabilities.notification.ensureDownloadPermission()
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
  ): Promise<JmcomicListenerHandle> {
    return platformCapabilities.events.addDownloadProgressListener(handler)
  },

  addLaunchRouteListener(
    handler: (data: { route: string }) => void,
  ): Promise<JmcomicListenerHandle> {
    return platformCapabilities.events.addLaunchRouteListener(handler)
  },

  /**
   * 注册文件搬迁进度监听。
   * @param handler 进度更新回调
   */
  addRelocationProgressListener(
    handler: (data: RelocationProgress) => void,
  ): Promise<JmcomicListenerHandle> {
    return platformCapabilities.events.addRelocationProgressListener(handler)
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
    return platformCapabilities.filePicker.pickFolder()
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

  updateLocalEpisodeType(albumId: string, isSingleEpisode: boolean) {
    return native.updateLocalEpisodeType({ albumId, isSingleEpisode })
  },

  deleteImportedPdf(id: number) {
    return native.deleteImportedPdf({ id })
  },

  openPdf(filePath: string) {
    return native.openPdf({ filePath })
  },

  getPdfInfo(filePath: string) {
    return native.getPdfInfo({ filePath })
  },

  renderPdfPage(filePath: string, page: number, targetWidth: number) {
    return native.renderPdfPage({ filePath, page, targetWidth })
  },

  checkFilesExist(paths: string[]) {
    return platformCapabilities.filePicker.checkFilesExist(paths)
  },

  getExternalStoragePath() {
    return platformCapabilities.filePicker.getExternalStoragePath()
  },

  checkNotificationPermission() {
    return platformCapabilities.notification.checkPermission()
  },

  requestNotificationPermission() {
    return platformCapabilities.notification.requestPermission()
  },

  consumeLaunchRoute() {
    return platformCapabilities.events.consumeLaunchRoute()
  },

  // ========== 阅读器设置 ==========

  setReaderDisplayMode(mode: string) {
    return platformCapabilities.readerRuntime.setDisplayMode(mode)
  },
  setReaderScreenOrientation(orientation: string) {
    return platformCapabilities.readerRuntime.setScreenOrientation(orientation)
  },
  setReaderBrightness(brightness: number) {
    return platformCapabilities.readerRuntime.setBrightness(brightness)
  },
  setReaderKeepScreenOn(enabled: boolean) {
    return platformCapabilities.readerRuntime.setKeepScreenOn(enabled)
  },
  setReaderFullscreen(enabled: boolean) {
    return platformCapabilities.readerRuntime.setFullscreen(enabled)
  },
  setReaderVolumeNavigation(enabled: boolean) {
    return platformCapabilities.readerRuntime.setVolumeNavigation(enabled)
  },

  // ========== 阅读器状态 ==========

  setReaderState(isActive: boolean, isVertical: boolean) {
    return platformCapabilities.readerRuntime.setState(isActive, isVertical)
  },

  addVolumeKeyListener(
    handler: (direction: 'up' | 'down') => void,
  ): Promise<JmcomicListenerHandle> {
    return platformCapabilities.events.addVolumeNavigationListener(handler)
  },
}

