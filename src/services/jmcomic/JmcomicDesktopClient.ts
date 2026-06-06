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
import {desktopRequest, desktopResourceUrl, jsonBody} from './http'

const noopListenerHandle: JmcomicListenerHandle = {
  remove: () => Promise.resolve(),
}

function unsupported(): Promise<never> {
  return Promise.reject(new Error('This feature is not available in desktop.'))
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

  preloadImages({photoId, images, type}): Promise<PreloadResult> {
    const imageType = type === 'thumb' ? 'thumb' : 'image'
    return desktopRequest<PreloadResult>('/preload-images', {
      method: 'POST',
      body: jsonBody({photoId, images, type: imageType}),
    })
  },

  async setCacheCapacity({mb}) {
    return desktopRequest<{success: boolean; capacityMb: number}>('/cache/capacity', {
      method: 'POST',
      body: jsonBody({mb}),
    })
  },

  getCacheCapacityInfo(): Promise<CacheCapacityInfo> {
    return desktopRequest<CacheCapacityInfo>('/cache/capacity')
  },

  clearImageCache() {
    return desktopRequest<{success: boolean}>('/cache/clear', {method: 'POST'})
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

  downloadChapter({albumId, chapterId, albumTitle, chapterTitle, coverUrl}): Promise<{taskId: string}> {
    return desktopRequest<{taskId: string}>('/downloads', {
      method: 'POST',
      body: jsonBody({albumId, chapterId, albumTitle, chapterTitle, coverUrl}),
    })
  },

  getDownloadTasks(): Promise<DownloadTasksResult> {
    return desktopRequest<DownloadTasksResult>('/downloads')
  },

  cancelDownload({taskId}) {
    return desktopRequest<{success: boolean}>(`/downloads/${encodeURIComponent(taskId)}/cancel`, {method: 'POST'})
  },

  pauseDownload({taskId}) {
    return desktopRequest<{success: boolean}>(`/downloads/${encodeURIComponent(taskId)}/pause`, {method: 'POST'})
  },

  resumeDownload({taskId}) {
    return desktopRequest<{success: boolean}>(`/downloads/${encodeURIComponent(taskId)}/resume`, {method: 'POST'})
  },

  deleteDownloaded({albumId, chapterId}) {
    return desktopRequest<{success: boolean}>(
      `/downloads/${encodeURIComponent(albumId)}/${encodeURIComponent(chapterId)}`,
      {method: 'DELETE'},
    )
  },

  getDownloadedPhoto({albumId, chapterId}): Promise<PhotoDetail> {
    return desktopRequest<PhotoDetail>(
      `/downloaded/${encodeURIComponent(albumId)}/${encodeURIComponent(chapterId)}`,
    )
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
    return desktopRequest<{accepted: boolean}>('/pdf/export', {
      method: 'POST',
      body: jsonBody(_options),
    })
  },

  pickFolder() {
    return desktopRequest<{pdfRootDir: string}>('/files/roots')
      .then((roots) => ({path: roots.pdfRootDir, cancelled: false}))
  },

  checkFilesExist(options: {paths: string[]}) {
    return desktopRequest<{existing: string[]}>('/files/check', {
      method: 'POST',
      body: jsonBody(options),
    })
  },

  getExternalStoragePath() {
    return desktopRequest<{pdfExportDir: string}>('/files/roots')
      .then((roots) => ({path: roots.pdfExportDir}))
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

  scanPdfFiles(options: {path: string; treeUri?: string}): Promise<{files: PdfScanItem[]}> {
    return desktopRequest<{files: PdfScanItem[]}>('/pdf/scan', {
      method: 'POST',
      body: jsonBody(options),
    })
  },

  importPdfs(options: {items: ImportPdfItem[]}): Promise<ImportPdfsResult> {
    return desktopRequest<ImportPdfsResult>('/pdf/import', {
      method: 'POST',
      body: jsonBody(options),
    })
  },

  getImportedPdfs(): Promise<ImportedPdfsResult> {
    return desktopRequest<ImportedPdfsResult>('/pdf/imports')
  },

  updateLocalEpisodeType(options: {albumId: string; isSingleEpisode: boolean}) {
    return desktopRequest<{success: boolean; updatedDownloads: number; updatedPdfs: number}>(
      '/local-episode-type',
      {
        method: 'POST',
        body: jsonBody(options),
      },
    )
  },

  deleteImportedPdf({id}) {
    return desktopRequest<{success: boolean}>(`/pdf/imports/${encodeURIComponent(id)}`, {
      method: 'DELETE',
    })
  },

  openPdf() {
    return Promise.resolve({success: true})
  },

  getPdfInfo({filePath}) {
    const params = new URLSearchParams({path: filePath})
    return desktopRequest<{pageCount: number}>(`/pdf/info?${params}`)
  },

  renderPdfPage({filePath, page, targetWidth}) {
    const params = new URLSearchParams({
      path: filePath,
      page: String(page),
      targetWidth: String(targetWidth),
    })
    return Promise.resolve({imageUrl: desktopResourceUrl(`/api/pdf/page?${params}`)})
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
