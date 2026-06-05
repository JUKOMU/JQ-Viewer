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
  SearchResult,
  SearchResultItem,
  UserInfo,
  UserProfile,
} from '../JmcomicTypes'
import type {ImageReadyEvent, JmcomicClient, JmcomicListenerHandle} from './JmcomicClient'
import {desktopRequest, jsonBody} from './http'
import {rememberDesktopImageUrls} from './desktopImageUrls'

const noopListenerHandle: JmcomicListenerHandle = {
  remove: () => Promise.resolve(),
}

function unsupported(): Promise<never> {
  return Promise.reject(new Error('This feature is not available in desktop stage 3.'))
}

async function updateSettings<T>(patch: Partial<AllSettings>): Promise<T> {
  return desktopRequest<T>('/settings', {
    method: 'POST',
    body: jsonBody(patch),
  })
}

export const jmcomicDesktopClient: JmcomicClient = {
  search({query}) {
    return desktopRequest<SearchResult>('/search', {
      method: 'POST',
      body: jsonBody({query}),
    })
  },

  categories({query}) {
    return desktopRequest<SearchResult>('/categories', {
      method: 'POST',
      body: jsonBody({query}),
    })
  },

  getAlbum({id}) {
    return desktopRequest<AlbumDetail>(`/albums/${encodeURIComponent(id)}`)
  },

  getPhoto({id}) {
    return desktopRequest<PhotoDetail>(`/photos/${encodeURIComponent(id)}`)
  },

  getComments({albumId, page}) {
    const params = new URLSearchParams({albumId, page: String(page)})
    return desktopRequest<CommentList>(`/comments?${params}`)
  },

  getFavorites(_options: {query: FavoriteQuery}): Promise<FavoriteResult> {
    return unsupported()
  },

  toggleAlbumLike(_options: {id: string}) {
    return unsupported()
  },

  toggleAlbumFavorite(_options: {id: string; folderId: string}) {
    return unsupported()
  },

  manageFavoriteFolder(_options: {
    type: string
    folderId: string
    folderName?: string
    albumId?: string
  }) {
    return unsupported()
  },

  async preloadImages({photoId, images, type}): Promise<PreloadResult> {
    const imageType = type === 'thumb' ? 'thumb' : 'image'
    rememberDesktopImageUrls(photoId, images, imageType)
    return {cached: images.map((image) => image.sortOrder), pending: []}
  },

  async setCacheCapacity({mb}) {
    await updateSettings<AllSettings>({cacheCapacityMb: mb})
    return {success: true, capacityMb: mb}
  },

  async getCacheCapacityInfo(): Promise<CacheCapacityInfo> {
    const settings = await this.getAllSettings()
    return {capacityMb: settings.cacheCapacityMb, usedMb: 0}
  },

  clearImageCache() {
    return Promise.resolve({success: true})
  },

  async setDownloadConcurrency({n}) {
    await updateSettings<AllSettings>({downloadConcurrency: n})
    return {success: true}
  },

  async setPreloadConcurrency({n}) {
    await updateSettings<AllSettings>({preloadConcurrency: n})
    return {success: true}
  },

  async setOcrEnabled({enabled}) {
    await updateSettings<AllSettings>({ocrEnabled: enabled})
    return {success: true}
  },

  async setDownloadPublic({open}) {
    await updateSettings<AllSettings>({downloadPublic: open})
    return {success: true, downloadPublic: open, moved: 0}
  },

  async getDownloadPublic() {
    const settings = await this.getAllSettings()
    return {downloadPublic: settings.downloadPublic}
  },

  requestManageStorage() {
    return Promise.resolve({granted: false, permissionType: 'not_supported', apiLevel: 0})
  },

  getAllSettings() {
    return desktopRequest<AllSettings>('/settings')
  },

  async setReaderPreloadPages({n}) {
    await updateSettings<AllSettings>({readerPreloadPages: n})
    return {success: true}
  },

  downloadChapter(): Promise<{taskId: string}> {
    return unsupported()
  },

  getDownloadTasks(): Promise<DownloadTasksResult> {
    return Promise.resolve({tasks: [], usedBytes: 0, availableBytes: 0})
  },

  cancelDownload() {
    return unsupported()
  },

  pauseDownload() {
    return unsupported()
  },

  resumeDownload() {
    return unsupported()
  },

  deleteDownloaded() {
    return unsupported()
  },

  getDownloadedPhoto(): Promise<PhotoDetail> {
    return unsupported()
  },

  addListener(
    _event: 'imageReady' | 'downloadProgress' | 'relocationProgress' | 'networkProbe' | 'launchRoute' | 'volumeKey',
    _handler:
      | ((data: ImageReadyEvent) => void)
      | ((data: DownloadProgressEvent) => void)
      | ((data: RelocationProgress) => void)
      | ((data: NetworkProbeEvent) => void)
      | ((data: {route: string}) => void)
      | ((data: {direction: 'up' | 'down'}) => void),
  ) {
    return Promise.resolve(noopListenerHandle)
  },

  getDomainStates(): Promise<DomainStates> {
    return Promise.resolve({domains: [], alive: 0, total: 0, allDeadFallback: false})
  },

  reprobeDomains() {
    return Promise.resolve()
  },

  measureLatency(): Promise<{results: LatencyResult[]}> {
    return Promise.resolve({results: []})
  },

  getInitStatus() {
    return desktopRequest<{complete: boolean}>('/init-status')
  },

  login({username, password}) {
    return desktopRequest<UserInfo>('/auth/login', {
      method: 'POST',
      body: jsonBody({username, password}),
    })
  },

  logout() {
    return desktopRequest<{success: boolean}>('/auth/logout', {method: 'POST'})
  },

  checkLoginState() {
    return desktopRequest<{loggedIn: boolean; username?: string; userInfo?: UserInfo}>('/auth/state')
  },

  autoLogin() {
    return Promise.resolve({success: false})
  },

  getUserProfile({uid}) {
    return desktopRequest<UserProfile>(`/users/${encodeURIComponent(uid)}`)
  },

  getBrowseHistory({limit, offset}) {
    const params = new URLSearchParams({limit: String(limit), offset: String(offset)})
    return desktopRequest<{items: BrowseHistoryItem[]}>(`/history/browse?${params}`)
  },

  recordBrowse(options) {
    return desktopRequest<{success: boolean}>('/history/browse', {
      method: 'POST',
      body: jsonBody(options),
    })
  },

  clearBrowseHistory() {
    return desktopRequest<{success: boolean}>('/history/browse', {method: 'DELETE'})
  },

  deleteBrowseItem({id}) {
    return desktopRequest<{success: boolean}>(`/history/browse/${id}`, {method: 'DELETE'})
  },

  getParseHistory(): Promise<{items: ParseHistoryItem[]}> {
    return Promise.resolve({items: []})
  },

  addParseHistory() {
    return Promise.resolve({success: true})
  },

  clearParseHistory() {
    return Promise.resolve({success: true})
  },

  deleteParseItem() {
    return Promise.resolve({success: true})
  },

  getOfflineFolders(): Promise<{folders: OfflineFolderInfo[]}> {
    return Promise.resolve({folders: []})
  },

  createOfflineFolder() {
    return unsupported()
  },

  renameOfflineFolder() {
    return unsupported()
  },

  deleteOfflineFolder() {
    return unsupported()
  },

  addOfflineFavorite(_options: {folderId: string; item: SearchResultItem}) {
    return unsupported()
  },

  removeOfflineFavorite() {
    return unsupported()
  },

  getOfflineFavorites(): Promise<OfflineFavoritesResult> {
    return Promise.resolve({totalItems: 0, totalPages: 0, currentPage: 1, content: []})
  },

  getAllOfflineFavorites(): Promise<{items: SearchResultItem[]}> {
    return Promise.resolve({items: []})
  },

  getOfflineFavoritesTotalCount() {
    return Promise.resolve({count: 0})
  },

  getAllOfflineFavoritesMerged(): Promise<{items: SearchResultItem[]}> {
    return Promise.resolve({items: []})
  },

  moveAllOfflineFavorites() {
    return unsupported()
  },

  copyOfflineFolder() {
    return unsupported()
  },

  addOfflineFavoritesBatch() {
    return unsupported()
  },

  mergeOfflineAllToFolder() {
    return unsupported()
  },

  saveOfflineBackup() {
    return unsupported()
  },

  loadOfflineBackup(): Promise<{items: SearchResultItem[] | null}> {
    return Promise.resolve({items: null})
  },

  deleteOfflineBackup() {
    return unsupported()
  },

  listOfflineBackupKeys() {
    return Promise.resolve({keys: []})
  },

  pickImageAndOcr() {
    return unsupported()
  },

  exportPdfBatch(_options: {tasks: PdfExportTask[]}) {
    return unsupported()
  },

  pickFolder() {
    return Promise.resolve({path: '', cancelled: true})
  },

  checkFilesExist(_options: {paths: string[]}) {
    return Promise.resolve({existing: []})
  },

  getExternalStoragePath() {
    return Promise.resolve({path: ''})
  },

  checkNotificationPermission() {
    return Promise.resolve({granted: true})
  },

  requestNotificationPermission() {
    return Promise.resolve({granted: true})
  },

  consumeLaunchRoute() {
    return Promise.resolve({})
  },

  scanPdfFiles(_options: {path: string; treeUri?: string}): Promise<{files: PdfScanItem[]}> {
    return unsupported()
  },

  importPdfs(_options: {items: ImportPdfItem[]}): Promise<ImportPdfsResult> {
    return unsupported()
  },

  getImportedPdfs(): Promise<ImportedPdfsResult> {
    return Promise.resolve({pdfs: []})
  },

  updateLocalEpisodeType() {
    return Promise.resolve({success: true, updatedDownloads: 0, updatedPdfs: 0})
  },

  deleteImportedPdf() {
    return unsupported()
  },

  openPdf() {
    return unsupported()
  },

  getPdfInfo() {
    return unsupported()
  },

  renderPdfPage() {
    return unsupported()
  },

  async setReaderDisplayMode({mode}) {
    await updateSettings<AllSettings>({readerDisplayMode: mode})
    return {success: true}
  },

  async setReaderScreenOrientation({orientation}) {
    await updateSettings<AllSettings>({readerScreenOrientation: orientation})
    return {success: true}
  },

  async setReaderBrightness({brightness}) {
    await updateSettings<AllSettings>({readerBrightness: brightness})
    return {success: true}
  },

  async setReaderKeepScreenOn({enabled}) {
    await updateSettings<AllSettings>({readerKeepScreenOn: enabled})
    return {success: true}
  },

  async setReaderFullscreen() {
    return {success: true}
  },

  async setReaderVolumeNavigation({enabled}) {
    await updateSettings<AllSettings>({readerVolumeNavigation: enabled})
    return {success: true}
  },

  async setReaderState() {
    return {success: true}
  },
}
