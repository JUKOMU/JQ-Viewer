import type {
  AlbumDetail,
  AllSettings,
  BrowseHistoryOverview,
  BrowseHistoryItem,
  BrowseHistoryRange,
  CacheCapacityInfo,
  CommentList,
  DomainStates,
  DownloadProgressEvent,
  DownloadTasksResult,
  FavoriteQuery,
  FavoriteResult,
  ForumQuery,
  HistoryPageResult,
  ImageInfo,
  ImageCacheEntry,
  ImportedPdf,
  ImportPdfItem,
  ImportPdfsResult,
  LatencyResult,
  NetworkProbeEvent,
  OfflineFavoritesResult,
  OfflineFolderInfo,
  ParseHistoryItem,
  PdfExportTask,
  PdfExportProgressEvent,
  PdfExportStatus,
  PdfExportTaskRecord,
  PdfExportSubmissionTaskResult,
  PdfManagementState,
  PdfStorageDeleteResult,
  PdfScanItem,
  PhotoDetail,
  PreloadResult,
  RelocationProgress,
  SearchQuery,
  SearchResult,
  SearchResultItem,
  UserInfo,
  UserProfile,
  UpdateManifest,
  UpdateProgressEvent,
} from '../JmcomicTypes'

export interface ImageReadyEvent {
  photoId: string
  sortOrder: number
  type: string
}

export type ImageFailedEvent = ImageReadyEvent

export interface JmcomicListenerHandle {
  remove: () => Promise<void>
}

/** Raw DTOs kept inside the Android adapter to preserve the Plugin contract. */
export type AndroidPdfExportTask = Omit<PdfExportTask, 'target' | 'displayPath'> & {
  savePath: string
}
export type AndroidPdfExportTaskRecord = Omit<
  PdfExportTaskRecord,
  'outputFile' | 'displayPath'
> & {
  savePath: string
}
export type AndroidPdfExportSubmissionTaskResult = Partial<AndroidPdfExportTaskRecord> &
  Pick<PdfExportSubmissionTaskResult, 'accepted' | 'errorCode' | 'errorMessage'>
export type AndroidPdfScanItem = Omit<PdfScanItem, 'ref' | 'displayPath'> & { filePath: string }
export type AndroidImportedPdf = Omit<ImportedPdf, 'fileRef' | 'displayPath'> & {
  filePath: string
}
export type AndroidPdfStorageDeleteResult = Omit<PdfStorageDeleteResult, 'file'> & {
  filePath: string
  fileName: string
}
export type AndroidImportPdfItem = Omit<ImportPdfItem, 'fileRef' | 'displayPath'> & {
  filePath: string
}
export type AndroidPdfExportBatchResult = {
  tasks: AndroidPdfExportSubmissionTaskResult[]
}
export type AndroidImportedPdfsResult = { pdfs: AndroidImportedPdf[] }
export type AndroidImportPdfsResult = Omit<ImportPdfsResult, 'results'> & {
  results?: Array<{ result: string; filePath?: string; fileName?: string; id?: number }>
}

export interface JmcomicClient {
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

  retryImage(options: { photoId: string; image: ImageInfo }): Promise<{ success: boolean }>

  setCacheCapacity(options: { mb: number }): Promise<CacheCapacityInfo & { success: boolean }>

  getCacheCapacityInfo(): Promise<CacheCapacityInfo>

  getImageCacheContents(): Promise<{ entries: ImageCacheEntry[] }>

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
  ): Promise<JmcomicListenerHandle>

  addListener(
    event: 'imageFailed',
    handler: (data: ImageFailedEvent) => void,
  ): Promise<JmcomicListenerHandle>

  addListener(
    event: 'downloadProgress',
    handler: (data: DownloadProgressEvent) => void,
  ): Promise<JmcomicListenerHandle>

  addListener(
    event: 'relocationProgress',
    handler: (data: RelocationProgress) => void,
  ): Promise<JmcomicListenerHandle>

  addListener(
    event: 'networkProbe',
    handler: (data: NetworkProbeEvent) => void,
  ): Promise<JmcomicListenerHandle>

  addListener(
    event: 'launchRoute',
    handler: (data: { route: string }) => void,
  ): Promise<JmcomicListenerHandle>

  addListener(
    event: 'updateProgress',
    handler: (data: UpdateProgressEvent) => void,
  ): Promise<JmcomicListenerHandle>

  getDomainStates(): Promise<DomainStates>

  reprobeDomains(): Promise<void>

  measureLatency(): Promise<{ results: LatencyResult[] }>

  getInitStatus(): Promise<{ complete: boolean }>

  login(options: { username: string; password: string }): Promise<UserInfo>

  logout(): Promise<{ success: boolean }>

  checkLoginState(): Promise<{ loggedIn: boolean; username?: string; userInfo?: UserInfo }>

  autoLogin(): Promise<{ success: boolean; userInfo?: UserInfo }>

  getUserProfile(options: { uid: string }): Promise<UserProfile>

  getBrowseHistory(options: {
    limit: number
    offset: number
    startInclusive?: number | null
    endExclusive?: number | null
  }): Promise<HistoryPageResult<BrowseHistoryItem>>

  getBrowseHistoryOverview(options: {
    ranges: readonly BrowseHistoryRange[]
  }): Promise<BrowseHistoryOverview>

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

  getParseHistory(options: {
    limit: number
    offset: number
  }): Promise<HistoryPageResult<ParseHistoryItem>>

  addParseHistory(options: { text: string; mode: string }): Promise<{ success: boolean }>

  clearParseHistory(): Promise<{ success: boolean }>

  deleteParseItem(options: { id: number }): Promise<{ success: boolean }>

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

  pickImageAndOcr(): Promise<{ text: string; error?: string }>

  exportPdfBatch(options: { tasks: AndroidPdfExportTask[] }): Promise<AndroidPdfExportBatchResult>

  pickFolder(): Promise<{ path: string; treeUri?: string; cancelled: boolean }>

  checkFilesExist(options: { paths: string[] }): Promise<{ existing: string[] }>

  getExternalStoragePath(): Promise<{ path: string }>

  checkNotificationPermission(): Promise<{ granted: boolean }>

  requestNotificationPermission(): Promise<{ granted: boolean }>

  openNotificationSettings(): Promise<{ opened: boolean }>

  checkUpdate(): Promise<{ updateAvailable: boolean; manifest: UpdateManifest }>

  startUpdate(): Promise<{ started: boolean }>

  cancelUpdate(): Promise<{ cancelled: boolean }>

  getUpdateState(): Promise<UpdateProgressEvent>

  installUpdate(): Promise<{ started: boolean; permissionRequired: boolean }>

  requestInstallPermission(): Promise<{ requested: boolean }>

  consumeLaunchRoute(): Promise<{ route?: string }>

  scanPdfFiles(options: { path: string; treeUri?: string }): Promise<{ files: AndroidPdfScanItem[] }>

  importPdfs(options: { items: AndroidImportPdfItem[] }): Promise<AndroidImportPdfsResult>

  getImportedPdfs(): Promise<AndroidImportedPdfsResult>

  getPdfFiles(options: {
    sourceType?: 'imported' | 'exported'
    availability?: ImportedPdf['availability'] | 'problem'
    folderId?: string
    query?: string
    cursor?: string
    limit: number
  }): Promise<{ files: AndroidImportedPdf[]; nextCursor?: string }>

  refreshPdfFileAvailability(options: { ids: number[] }): Promise<{ files: AndroidImportedPdf[] }>

  inspectPdfFileForDeletion(options: { id: number }): Promise<AndroidImportedPdf>

  verifyPdfFile(options: { id: number }): Promise<AndroidImportedPdf>

  removePdfFromLibrary(options: { id: number }): Promise<{ success: boolean }>

  deletePdfFile(options: { id: number }): Promise<AndroidPdfStorageDeleteResult>

  getPdfManagementState(): Promise<PdfManagementState>

  acknowledgePdfDatabaseReset(): Promise<{ acknowledged: boolean }>

  getPdfExportTasks(options: {
    status?: PdfExportStatus
    cursor?: string
    limit: number
  }): Promise<{ tasks: AndroidPdfExportTaskRecord[]; nextCursor?: string }>

  getPdfExportTask(options: { exportId: string }): Promise<AndroidPdfExportTaskRecord>

  cancelPdfExport(options: { exportId: string }): Promise<AndroidPdfExportTaskRecord>

  retryPdfExport(options: {
    exportId: string
    allowOverwrite?: boolean
  }): Promise<AndroidPdfExportTaskRecord>

  deletePdfExportTask(options: { exportId: string }): Promise<{ success: boolean }>

  updateLocalEpisodeType(options: {
    albumId: string
    isSingleEpisode: boolean
  }): Promise<{ success: boolean; updatedDownloads: number; updatedPdfs: number }>

  deleteImportedPdf(options: { id: number }): Promise<{ success: boolean }>

  openPdf(options: { filePath: string }): Promise<{ success: boolean }>

  openPdfFolder(options: { filePath: string }): Promise<{ success: boolean }>

  getPdfInfo(options: { filePath: string }): Promise<{ pageCount: number }>

  renderPdfPage(options: {
    filePath: string
    page: number
    targetWidth: number
  }): Promise<{ imageUrl: string }>

  setReaderDisplayMode(options: { mode: string }): Promise<{ success: boolean }>

  setReaderScreenOrientation(options: { orientation: string }): Promise<{ success: boolean }>

  setReaderBrightness(options: { brightness: number }): Promise<{ success: boolean }>

  setReaderKeepScreenOn(options: { enabled: boolean }): Promise<{ success: boolean }>

  setReaderFullscreen(options: { enabled: boolean }): Promise<{ success: boolean }>

  setReaderVolumeNavigation(options: { enabled: boolean }): Promise<{ success: boolean }>

  setReaderAutoShowToolbarAtEnd(options: { enabled: boolean }): Promise<{ success: boolean }>

  setReaderState(options: { isActive: boolean; isVertical: boolean }): Promise<{ success: boolean }>

  addListener(
    event: 'pdfExportProgress',
    handler: (data: PdfExportProgressEvent) => void,
  ): Promise<JmcomicListenerHandle>

  addListener(
    event: 'volumeKey',
    handler: (data: { direction: 'up' | 'down' }) => void,
  ): Promise<JmcomicListenerHandle>
}
