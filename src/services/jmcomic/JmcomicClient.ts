import type {
  AlbumDetail,
  AllSettings,
  BrowseHistoryOverview,
  BrowseHistoryItem,
  BrowseHistoryRange,
  CbzDocumentInfo,
  CacheCapacityInfo,
  ClientStateSnapshot,
  CommentList,
  DomainStates,
  DownloadProgressEvent,
  DownloadActiveProgress,
  DownloadTask,
  DownloadTaskPageQuery,
  DownloadTaskStatusResult,
  DownloadTasksPageResult,
  FavoriteQuery,
  FavoriteResult,
  ForumQuery,
  HistoryPageResult,
  ImageInfo,
  ImageCacheEntry,
  LocalFileRecord,
  ImportLocalFileItem,
  ImportLocalFilesResult,
  LatencyResult,
  NetworkProbeEvent,
  OfflineFavoritesResult,
  OfflineFolderInfo,
  ParseHistoryItem,
  ExportTask,
  ExportFormat,
  ExportProgressEvent,
  ExportStatus,
  ExportTaskRecord,
  ExportSubmissionTaskResult,
  LocalFileManagementState,
  LocalFileStorageDeleteResult,
  LocalFileScanItem,
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

/** Android Plugin 的原生 DTO；文件位置只以 opaque ref 传输。 */
export type AndroidExportTask = Omit<ExportTask, 'target' | 'displayPath'> & {
  targetFolderRef: string
  targetName: string
  displayPath: string
}
export type AndroidExportTaskRecord = Omit<ExportTaskRecord, 'outputFile' | 'displayPath'> & {
  targetFolderRef: string
  targetName: string
  outputFileRef?: string
  displayPath?: string
}
export type AndroidExportSubmissionTaskResult = Partial<AndroidExportTaskRecord> &
  Pick<ExportSubmissionTaskResult, 'accepted' | 'errorCode' | 'errorMessage'>
export type AndroidLocalFileScanItem = Omit<LocalFileScanItem, 'ref'> & { fileRef: string }
export type AndroidLocalFileRecord = Omit<LocalFileRecord, 'fileRef' | 'displayPath'> & {
  fileRef: string
  displayPath: string
}
export type AndroidLocalFileStorageDeleteResult = Omit<LocalFileStorageDeleteResult, 'file'> & {
  fileRef: string
  displayPath: string
  fileName: string
}
export type AndroidImportLocalFileItem = Omit<ImportLocalFileItem, 'fileRef' | 'displayPath'> & {
  fileRef: string
  displayPath: string
}
export type AndroidExportBatchResult = {
  tasks: AndroidExportSubmissionTaskResult[]
}
export type AndroidImportedLocalFilesResult = { files: AndroidLocalFileRecord[] }
export type AndroidImportLocalFilesResult = Omit<ImportLocalFilesResult, 'results'> & {
  results?: Array<{
    result: string
    fileRef?: string
    displayPath?: string
    fileName?: string
    id?: number
  }>
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

  setDownloadPublic(options: { open: boolean }): Promise<{
    success: boolean
    downloadPublic: boolean
    moved: number
    displayPath?: string
    cleanupPending?: boolean
    cleanupMessage?: string
  }>

  getDownloadPublic(): Promise<{
    downloadPublic: boolean
    displayPath?: string
    cleanupPending?: boolean
    cleanupMessage?: string
  }>

  requestManageStorage(): Promise<{ granted: boolean; permissionType: string; apiLevel: number }>

  getLogs(options: { fromLine: number; fromOffset: number }): Promise<{
    fileName: string
    updatedAt: number
    nextLine: number
    nextOffset: number
    content: string
    reset: boolean
  }>

  listLogFiles(): Promise<{
    files: Array<{ fileName: string; updatedAt: number; sizeBytes: number; current: boolean }>
  }>

  exportLogFiles(options: { folderRef: string; fileNames: string[] }): Promise<{
    exported: string[]
    skipped: string[]
    failed: Array<{ fileName: string; message: string }>
  }>

  getAllSettings(): Promise<AllSettings>

  setReaderPreloadPages(options: { n: number }): Promise<{ success: boolean }>

  downloadChapter(options: {
    albumId: string
    chapterId: string
    albumTitle: string
    chapterTitle: string
    coverUrl: string
  }): Promise<{ taskId: string }>

  getDownloadTasksPage(options: DownloadTaskPageQuery): Promise<DownloadTasksPageResult>

  getCompletedDownloadAlbumIds(options: { albumIds: string[] }): Promise<{ albumIds: string[] }>

  getDownloadTaskStatuses(options: { albumId: string }): Promise<DownloadTaskStatusResult>

  getCompletedDownloadChapters(options: { albumId: string }): Promise<{ tasks: DownloadTask[] }>

  getActiveDownloadProgress(): Promise<{ tasks: DownloadActiveProgress[] }>

  getDownloadedResourceKeys(options: {
    items: Array<{ albumId: string; chapterId: string }>
  }): Promise<{ keys: string[] }>

  clearDownloadTasks(options: {
    status: Extract<DownloadTaskPageQuery['status'], 'completed' | 'failed'>
  }): Promise<{ success: boolean; deletedCount: number; failedCount: number }>

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
    event: 'clientStateChanged',
    handler: (data: ClientStateSnapshot) => void,
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

  getUsedDomain(): Promise<{ domain: string | null }>

  applyApiRoute(options: {
    mode: 'auto' | 'manual'
    domain?: string
  }): Promise<{ domain: string | null }>

  setApiRoutePreference(options: {
    mode: 'auto' | 'manual'
    domain?: string
  }): Promise<{ success: boolean }>

  reprobeDomains(): Promise<void>

  measureLatency(): Promise<{ results: LatencyResult[] }>

  /** 测量当前图片 Host 候选的有效下载吞吐量。 */
  measureImageHosts(): Promise<{
    selectedHost: string | null
    hosts: {
      host: string
      available: boolean
      elapsedMs: number
      bytes: number
      bytesPerSecond: number
      error?: string
    }[]
  }>

  /** 读取当前图片 Host 候选列表。 */
  getImageRouteCandidates(): Promise<{ hosts: string[] }>

  /** 保存并立即应用自动或手动图片 Host。 */
  setImageRoutePreference(options: { mode: 'auto' | 'manual'; host: string }): Promise<{ success: boolean }>

  getInitStatus(): Promise<{ complete: boolean }>

  getClientState(): Promise<ClientStateSnapshot>

  /** rememberMe 缺省为 true，兼容未携带新字段的旧前端。 */
  login(options: { username: string; password: string; rememberMe?: boolean }): Promise<UserInfo>

  logout(): Promise<{ success: boolean }>

  checkLoginState(): Promise<{
    loggedIn: boolean
    username?: string
    userInfo?: UserInfo
    /** 只有明确为 false 时，才表示当前会话未选择保存自动登录凭据。 */
    hasSavedCredentials?: boolean
  }>

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
    fileId?: number
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

  exportBatch(options: { tasks: AndroidExportTask[] }): Promise<AndroidExportBatchResult>

  pickFolder(): Promise<{
    folderRef: string
    displayPath: string
    provider: 'path' | 'saf'
    cancelled: boolean
  }>

  checkFilesExist(options: { fileRefs: string[] }): Promise<{ existingFileRefs: string[] }>

  getExternalStoragePath(): Promise<{
    folderRef: string
    displayPath: string
    provider: 'path' | 'saf'
  }>

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

  scanImportableFiles(options: {
    folderRef: string
    formats: ExportFormat[]
  }): Promise<{ files: AndroidLocalFileScanItem[] }>

  importLocalFiles(options: {
    items: AndroidImportLocalFileItem[]
  }): Promise<AndroidImportLocalFilesResult>

  getImportedLocalFiles(): Promise<AndroidImportedLocalFilesResult>

  getLocalFiles(options: {
    formats?: ExportFormat[]
    sourceType?: 'imported' | 'exported'
    availability?: LocalFileRecord['availability'] | 'problem'
    fileId?: number
    albumId?: string
    chapterId?: string
    folderId?: string
    query?: string
    cursor?: string
    limit: number
  }): Promise<{ files: AndroidLocalFileRecord[]; nextCursor?: string }>

  refreshLocalFileAvailability(options: {
    ids: number[]
  }): Promise<{ files: AndroidLocalFileRecord[] }>

  inspectLocalFileForDeletion(options: { id: number }): Promise<AndroidLocalFileRecord>

  verifyLocalFile(options: { id: number }): Promise<AndroidLocalFileRecord>

  removeLocalFileFromLibrary(options: { id: number }): Promise<{ success: boolean }>

  deleteLocalFile(options: { id: number }): Promise<AndroidLocalFileStorageDeleteResult>

  getLocalFileManagementState(): Promise<LocalFileManagementState>

  acknowledgeLocalFileDatabaseReset(): Promise<{ acknowledged: boolean }>

  getExportTasks(options: {
    format?: ExportFormat
    status?: ExportStatus
    cursor?: string
    limit: number
  }): Promise<{ tasks: AndroidExportTaskRecord[]; nextCursor?: string }>

  getExportTask(options: { exportId: string }): Promise<AndroidExportTaskRecord>

  cancelExport(options: { exportId: string }): Promise<AndroidExportTaskRecord>

  retryExport(options: {
    exportId: string
    allowOverwrite?: boolean
  }): Promise<AndroidExportTaskRecord>

  deleteExportTask(options: { exportId: string }): Promise<{ success: boolean }>

  updateLocalEpisodeType(options: {
    albumId: string
    isSingleEpisode: boolean
  }): Promise<{ success: boolean; updatedDownloads: number; updatedLocalFiles: number }>

  deleteImportedLocalFile(options: { id: number }): Promise<{ success: boolean }>

  openLocalFile(options: { fileRef: string }): Promise<{ success: boolean }>

  openLocalFileFolder(options: { fileRef: string }): Promise<{ success: boolean }>

  getPdfInfo(options: { fileRef: string }): Promise<{ pageCount: number }>

  getCbzInfo(options: { fileRef: string }): Promise<CbzDocumentInfo>

  renderPdfPage(options: {
    fileRef: string
    page: number
    targetWidth: number
  }): Promise<{ resourceUrl: string }>

  setReaderDisplayMode(options: { mode: string }): Promise<{ success: boolean }>

  setReaderWidthPercent(options: { value: number | null }): Promise<{ success: boolean }>

  setReaderScreenOrientation(options: { orientation: string }): Promise<{ success: boolean }>

  setReaderBrightness(options: { brightness: number }): Promise<{ success: boolean }>

  setReaderKeepScreenOn(options: { enabled: boolean }): Promise<{ success: boolean }>

  setReaderFullscreen(options: { enabled: boolean }): Promise<{ success: boolean }>

  setReaderVolumeNavigation(options: { enabled: boolean }): Promise<{ success: boolean }>

  setReaderAutoShowToolbarAtEnd(options: { enabled: boolean }): Promise<{ success: boolean }>

  setReaderState(options: { isActive: boolean; isVertical: boolean }): Promise<{ success: boolean }>

  addListener(
    event: 'exportProgress',
    handler: (data: ExportProgressEvent) => void,
  ): Promise<JmcomicListenerHandle>

  addListener(
    event: 'volumeKey',
    handler: (data: { direction: 'up' | 'down' }) => void,
  ): Promise<JmcomicListenerHandle>
}
