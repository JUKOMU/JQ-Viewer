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
import type {FolderPickPurpose} from '../platform/FilePickerPort'

const noopListenerHandle: JmcomicListenerHandle = {
  remove: () => Promise.resolve(),
}

type DesktopRoots = {
  pdfRootDir: string
  pdfExportDir: string
  downloadDir: string
}

function unsupported(): Promise<never> {
  return Promise.reject(new Error('This feature is not available in desktop.'))
}

async function pickDesktopFolder(purpose: FolderPickPurpose = 'pdfRoot') {
  const picked = await desktopRequest<{path?: string; cancelled: boolean}>('/files/pick-directory', {
    method: 'POST',
    body: jsonBody({purpose}),
  })
  if (picked.cancelled || !picked.path) {
    return {path: '', cancelled: true}
  }

  const patch = purpose === 'pdfExport'
    ? {pdfExportDir: picked.path}
    : purpose === 'download'
      ? {downloadDir: picked.path}
      : {pdfRootDir: picked.path}
  const roots = await desktopRequest<DesktopRoots>('/files/roots', {
    method: 'POST',
    body: jsonBody(patch),
  })
  const path = purpose === 'pdfExport'
    ? roots.pdfExportDir
    : purpose === 'download'
      ? roots.downloadDir
      : roots.pdfRootDir
  return {
    path,
    cancelled: false,
  }
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

  getFavorites({query}: {query: FavoriteQuery}): Promise<FavoriteResult> {
    return desktopRequest<FavoriteResult>('/favorites', {
      method: 'POST',
      body: jsonBody({query}),
    })
  },

  toggleAlbumLike({id}: {id: string}) {
    return desktopRequest<{success: boolean}>('/favorites/toggle-like', {
      method: 'POST',
      body: jsonBody({id}),
    })
  },

  toggleAlbumFavorite({id, folderId}: {id: string; folderId: string}) {
    return desktopRequest<{success: boolean}>('/favorites/toggle', {
      method: 'POST',
      body: jsonBody({id, folderId}),
    })
  },

  manageFavoriteFolder(options: {
    type: string
    folderId: string
    folderName?: string
    albumId?: string
  }) {
    return desktopRequest<{status: string; msg: string}>('/favorites/folders/manage', {
      method: 'POST',
      body: jsonBody(options),
    })
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

  getParseHistory({limit, offset}: {limit: number; offset: number}): Promise<{items: ParseHistoryItem[]}> {
    const params = new URLSearchParams({limit: String(limit), offset: String(offset)})
    return desktopRequest<{items: ParseHistoryItem[]}>(`/history/parse?${params}`)
  },

  addParseHistory({text, mode}: {text: string; mode: string}) {
    return desktopRequest<{success: boolean}>('/history/parse', {
      method: 'POST',
      body: jsonBody({text, mode}),
    })
  },

  clearParseHistory() {
    return desktopRequest<{success: boolean}>('/history/parse', {method: 'DELETE'})
  },

  deleteParseItem({id}: {id: number}) {
    return desktopRequest<{success: boolean}>(`/history/parse/${id}`, {method: 'DELETE'})
  },

  getOfflineFolders(): Promise<{folders: OfflineFolderInfo[]}> {
    return desktopRequest<{folders: OfflineFolderInfo[]}>('/offline-favorites/folders')
  },

  createOfflineFolder({name}: {name: string}) {
    return desktopRequest<{folderId: string}>('/offline-favorites/folders', {
      method: 'POST',
      body: jsonBody({name}),
    })
  },

  renameOfflineFolder({folderId, name}: {folderId: string; name: string}) {
    return desktopRequest<{success: boolean}>('/offline-favorites/folders/rename', {
      method: 'POST',
      body: jsonBody({folderId, name}),
    })
  },

  deleteOfflineFolder({folderId}: {folderId: string}) {
    return desktopRequest<{success: boolean}>('/offline-favorites/folders/delete', {
      method: 'POST',
      body: jsonBody({folderId}),
    })
  },

  addOfflineFavorite({folderId, item}: {folderId: string; item: SearchResultItem}) {
    return desktopRequest<{success: boolean}>('/offline-favorites/items', {
      method: 'POST',
      body: jsonBody({folderId, item}),
    })
  },

  removeOfflineFavorite({folderId, albumId}: {folderId: string; albumId: string}) {
    return desktopRequest<{success: boolean}>('/offline-favorites/items/remove', {
      method: 'POST',
      body: jsonBody({folderId, albumId}),
    })
  },

  getOfflineFavorites(
    {folderId, keyword, page, pageSize}: {folderId: string; keyword?: string; page: number; pageSize: number},
  ): Promise<OfflineFavoritesResult> {
    return desktopRequest<OfflineFavoritesResult>('/offline-favorites/items/query', {
      method: 'POST',
      body: jsonBody({folderId, keyword, page, pageSize}),
    })
  },

  getAllOfflineFavorites({folderId}: {folderId: string}): Promise<{items: SearchResultItem[]}> {
    return desktopRequest<{items: SearchResultItem[]}>('/offline-favorites/items/all', {
      method: 'POST',
      body: jsonBody({folderId}),
    })
  },

  getOfflineFavoritesTotalCount() {
    return desktopRequest<{count: number}>('/offline-favorites/count')
  },

  getAllOfflineFavoritesMerged(): Promise<{items: SearchResultItem[]}> {
    return desktopRequest<{items: SearchResultItem[]}>('/offline-favorites/items/merged')
  },

  moveAllOfflineFavorites({sourceId, targetId}: {sourceId: string; targetId: string}) {
    return desktopRequest<{success: boolean}>('/offline-favorites/items/move-all', {
      method: 'POST',
      body: jsonBody({sourceId, targetId}),
    })
  },

  copyOfflineFolder({sourceId, name}: {sourceId: string; name: string}) {
    return desktopRequest<{folderId: string}>('/offline-favorites/folders/copy', {
      method: 'POST',
      body: jsonBody({sourceId, name}),
    })
  },

  addOfflineFavoritesBatch({folderId, items}: {folderId: string; items: SearchResultItem[]}) {
    return desktopRequest<{count: number}>('/offline-favorites/items/batch', {
      method: 'POST',
      body: jsonBody({folderId, items}),
    })
  },

  mergeOfflineAllToFolder({targetId}: {targetId: string}) {
    return desktopRequest<{success: boolean}>('/offline-favorites/items/merge-all', {
      method: 'POST',
      body: jsonBody({targetId}),
    })
  },

  saveOfflineBackup({key, items}: {key: string; items: SearchResultItem[]}) {
    return desktopRequest<{success: boolean}>('/offline-favorites/backups', {
      method: 'POST',
      body: jsonBody({key, items}),
    })
  },

  loadOfflineBackup({key}: {key: string}): Promise<{items: SearchResultItem[] | null}> {
    return desktopRequest<{items: SearchResultItem[] | null}>('/offline-favorites/backups/load', {
      method: 'POST',
      body: jsonBody({key}),
    })
  },

  deleteOfflineBackup({key}: {key: string}) {
    return desktopRequest<{success: boolean}>('/offline-favorites/backups/delete', {
      method: 'POST',
      body: jsonBody({key}),
    })
  },

  listOfflineBackupKeys() {
    return desktopRequest<{keys: string[]}>('/offline-favorites/backups')
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

  pickFolder(options?: {purpose?: FolderPickPurpose}) {
    return pickDesktopFolder(options?.purpose)
  },

  checkFilesExist(options: {paths: string[]}) {
    return desktopRequest<{existing: string[]}>('/files/check', {
      method: 'POST',
      body: jsonBody(options),
    })
  },

  getExternalStoragePath() {
    return desktopRequest<DesktopRoots>('/files/roots')
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
