import packageInfo from '../../../package.json'
import type {
  AppInfo,
  Capability,
  DiagnosticSnapshot,
  DiagnosticsService,
  FileService,
  LocalFileService,
  PlatformServices,
  PublicDownloadService,
  ReaderPlatformServices,
} from '../PlatformServices'
import type { BackendEvents } from '../BackendEvents'
import {
  asFileRef,
  asFolderRef,
  fileNameFromDisplayPath,
  type FileDescriptor,
} from '../FileReferences'
import type {
  LocalFileRecord,
  ImportLocalFileItem,
  ImportLocalFilesResult,
  ExportBatchResult,
  ExportSubmissionTaskResult,
  ExportTask,
  ExportTaskRecord,
  LocalFileManagementState,
  LocalFileStorageDeleteResult,
  UpdateManifest,
} from '@/services/JmcomicTypes'
import type { UpdaterService, UpdateUserAction } from '../UpdateTypes'
import { RuntimeError } from '../errors'
import { requestBackend, type BackendFetch } from './backendClient'
import { createDesktopExportPreferencesStore } from './exportPreferences'

function createOcrService(fetcher: BackendFetch) {
  return {
    setEnabled: (enabled: boolean) =>
      requestBackend<{ success: boolean }>(fetcher, 'setOcrEnabled', { enabled }),
    pickImageAndOcr: () =>
      requestBackend<{ text: string; error?: string }>(fetcher, 'pickImageAndOcr', {}),
  }
}

function createDiagnosticsService(fetcher: BackendFetch): DiagnosticsService {
  return {
    getSnapshot: () => requestBackend<DiagnosticSnapshot>(fetcher, 'getDiagnostics', {}),
  }
}

function createDesktopUpdater(events: BackendEvents, fetcher: BackendFetch): UpdaterService {
  return {
    getState: () => requestBackend(fetcher, 'getUpdateState', {}),
    check: () =>
      requestBackend<{ updateAvailable: boolean; manifest: UpdateManifest }>(
        fetcher,
        'checkUpdate',
        {},
      ),
    start: () => requestBackend<{ started: boolean }>(fetcher, 'startUpdate', {}),
    cancel: () => requestBackend<{ cancelled: boolean }>(fetcher, 'cancelUpdate', {}),
    install: () =>
      requestBackend<{ started: boolean; permissionRequired: boolean }>(
        fetcher,
        'installUpdate',
        {},
      ),
    performUserAction: (_action: UpdateUserAction) =>
      Promise.reject(new RuntimeError('unavailable', 'Desktop 更新不需要应用内权限操作')),
    onProgress: (handler) => events.onUpdateProgress(handler),
  }
}

function unavailableCapability<T>(reason: string): Capability<T> {
  return { available: false, reason }
}

interface WakeLockSentinelLike extends EventTarget {
  release(): Promise<void>
}

interface NavigatorWithWakeLock {
  wakeLock?: {
    request(type: 'screen'): Promise<WakeLockSentinelLike>
  }
}

export interface DesktopReaderEnvironment {
  document?: Document
  navigator?: NavigatorWithWakeLock
  window?: Window
}

function browserOperationError(error: unknown, fallback: string): RuntimeError {
  if (error instanceof RuntimeError) return error
  if (typeof DOMException !== 'undefined' && error instanceof DOMException) {
    if (error.name === 'NotAllowedError' || error.name === 'SecurityError') {
      return new RuntimeError('permission-denied', error.message || fallback, { cause: error })
    }
    if (error.name === 'NotSupportedError') {
      return new RuntimeError('unavailable', error.message || fallback, { cause: error })
    }
  }
  return new RuntimeError(
    'internal',
    error instanceof Error && error.message ? error.message : fallback,
    { cause: error },
  )
}

class DesktopReaderHost {
  private active = false
  private vertical = true
  private keepAwakeRequested = false
  private wakeLock: WakeLockSentinelLike | null = null
  private wakeLockRequest: Promise<void> | null = null
  private fullscreenOwned = false
  private pageSuspended = false
  private disposed = false

  constructor(private readonly environment: DesktopReaderEnvironment) {
    environment.document?.addEventListener('visibilitychange', this.onVisibilityChange)
    environment.document?.addEventListener('fullscreenchange', this.onFullscreenChange)
    environment.window?.addEventListener('pagehide', this.onPageHide)
    environment.window?.addEventListener('pageshow', this.onPageShow)
  }

  createServices(): ReaderPlatformServices {
    const document = this.environment.document
    const wakeLockManager = this.environment.navigator?.wakeLock
    const wakeLockAvailable = typeof wakeLockManager?.request === 'function'
    const fullscreenAvailable = Boolean(
      document?.fullscreenEnabled !== false &&
      typeof document?.documentElement?.requestFullscreen === 'function' &&
      typeof document?.exitFullscreen === 'function',
    )

    return {
      orientation: unavailableCapability('桌面显示器不支持应用级屏幕方向控制'),
      brightness: unavailableCapability('桌面浏览器无法调整系统屏幕亮度'),
      keepAwake: wakeLockAvailable
        ? {
            available: true,
            api: { set: (enabled) => this.setKeepAwake(enabled) },
          }
        : unavailableCapability('当前浏览器不支持屏幕常亮'),
      fullscreen: fullscreenAvailable
        ? {
            available: true,
            api: { set: (enabled) => this.setFullscreen(enabled) },
          }
        : unavailableCapability('当前浏览器不支持页面全屏'),
      volumeKeys: unavailableCapability('桌面浏览器无法可靠拦截系统音量键'),
      hostState: document
        ? {
            available: true,
            api: { setState: (isActive, isVertical) => this.setState(isActive, isVertical) },
          }
        : unavailableCapability('当前环境不支持阅读器宿主状态'),
    }
  }

  private readonly onVisibilityChange = () => {
    if (this.disposed) return
    if (this.environment.document?.visibilityState === 'hidden') {
      void this.releaseWakeLock().catch(() => {})
      return
    }
    this.resumeWakeLock()
  }

  private readonly onFullscreenChange = () => {
    const document = this.environment.document
    if (!document || document.fullscreenElement !== document.documentElement) {
      this.fullscreenOwned = false
    }
  }

  private readonly onPageHide = (event: PageTransitionEvent) => {
    if (event.persisted) {
      this.pageSuspended = true
      void this.restoreHostState().catch(() => {})
      return
    }
    this.dispose()
  }

  private readonly onPageShow = (event: PageTransitionEvent) => {
    if (this.disposed || !event.persisted) return
    this.pageSuspended = false
    const pendingRequest = this.wakeLockRequest
    if (pendingRequest) {
      void pendingRequest.finally(() => this.resumeWakeLock()).catch(() => {})
      return
    }
    this.resumeWakeLock()
  }

  private resumeWakeLock(): void {
    if (this.disposed || this.pageSuspended || !this.active || !this.keepAwakeRequested) return
    void this.acquireWakeLock().catch(() => {})
  }

  private async setKeepAwake(enabled: boolean): Promise<{ success: boolean }> {
    this.ensureActiveEnvironment()
    this.keepAwakeRequested = enabled
    if (!enabled || !this.active) {
      await this.releaseWakeLock()
      return { success: true }
    }
    await this.acquireWakeLock()
    return { success: true }
  }

  private async setFullscreen(enabled: boolean): Promise<{ success: boolean }> {
    this.ensureActiveEnvironment()
    const document = this.environment.document
    if (
      document?.fullscreenEnabled === false ||
      typeof document?.documentElement?.requestFullscreen !== 'function' ||
      typeof document?.exitFullscreen !== 'function'
    ) {
      throw new RuntimeError('unavailable', '当前浏览器不支持页面全屏')
    }

    try {
      if (enabled) {
        if (document.fullscreenElement === document.documentElement) return { success: true }
        await document.documentElement.requestFullscreen({ navigationUI: 'hide' })
        this.fullscreenOwned = true
      } else {
        await this.exitOwnedFullscreen()
      }
      return { success: true }
    } catch (error) {
      if (enabled) this.fullscreenOwned = false
      throw browserOperationError(error, enabled ? '进入全屏失败' : '退出全屏失败')
    }
  }

  private async setState(isActive: boolean, isVertical: boolean): Promise<{ success: boolean }> {
    this.ensureActiveEnvironment()
    this.active = isActive
    this.vertical = isVertical
    this.updateDocumentState()

    if (!isActive) {
      await this.restoreHostState()
    } else if (this.keepAwakeRequested) {
      await this.acquireWakeLock()
    }
    return { success: true }
  }

  private async acquireWakeLock(): Promise<void> {
    if (
      this.wakeLock ||
      this.wakeLockRequest ||
      !this.active ||
      !this.keepAwakeRequested ||
      this.pageSuspended ||
      this.environment.document?.visibilityState === 'hidden'
    ) {
      return this.wakeLockRequest ?? Promise.resolve()
    }
    const manager = this.environment.navigator?.wakeLock
    if (typeof manager?.request !== 'function') {
      throw new RuntimeError('unavailable', '当前浏览器不支持屏幕常亮')
    }

    const request = manager
      .request('screen')
      .then((sentinel) => {
        if (this.disposed || this.pageSuspended || !this.active || !this.keepAwakeRequested) {
          return sentinel.release()
        }
        this.wakeLock = sentinel
        sentinel.addEventListener('release', this.onWakeLockRelease, { once: true })
      })
      .catch((error) => {
        throw browserOperationError(error, '启用屏幕常亮失败')
      })
      .finally(() => {
        if (this.wakeLockRequest === request) this.wakeLockRequest = null
      })
    this.wakeLockRequest = request
    await request
  }

  private readonly onWakeLockRelease = () => {
    this.wakeLock = null
  }

  private async releaseWakeLock(): Promise<void> {
    const sentinel = this.wakeLock
    this.wakeLock = null
    if (!sentinel) return
    sentinel.removeEventListener('release', this.onWakeLockRelease)
    try {
      await sentinel.release()
    } catch (error) {
      throw browserOperationError(error, '释放屏幕常亮失败')
    }
  }

  private async restoreHostState(): Promise<void> {
    let failure: unknown
    try {
      await this.releaseWakeLock()
    } catch (error) {
      failure = error
    }
    try {
      await this.exitOwnedFullscreen()
    } catch (error) {
      failure ??= error
    }
    if (failure) throw failure
  }

  private updateDocumentState(): void {
    const root = this.environment.document?.documentElement
    if (!root) return
    if (this.active) {
      root.dataset.jqReaderActive = 'true'
      root.dataset.jqReaderMode = this.vertical ? 'vertical' : 'horizontal'
    } else {
      delete root.dataset.jqReaderActive
      delete root.dataset.jqReaderMode
    }
  }

  private ensureActiveEnvironment(): void {
    if (this.disposed) throw new RuntimeError('unavailable', '阅读器宿主已关闭')
  }

  private async exitOwnedFullscreen(): Promise<void> {
    const document = this.environment.document
    if (!this.fullscreenOwned || !document?.fullscreenElement || !document.exitFullscreen) return
    this.fullscreenOwned = false
    try {
      await document.exitFullscreen()
    } catch (error) {
      throw browserOperationError(error, '退出全屏失败')
    }
  }

  private dispose(): void {
    if (this.disposed) return
    this.disposed = true
    this.pageSuspended = false
    this.active = false
    this.keepAwakeRequested = false
    this.updateDocumentState()
    this.environment.document?.removeEventListener('visibilitychange', this.onVisibilityChange)
    this.environment.document?.removeEventListener('fullscreenchange', this.onFullscreenChange)
    this.environment.window?.removeEventListener('pagehide', this.onPageHide)
    this.environment.window?.removeEventListener('pageshow', this.onPageShow)
    void this.releaseWakeLock().catch(() => {})
    void this.exitOwnedFullscreen().catch(() => {})
  }
}

export function createDesktopReaderServices(
  environment: DesktopReaderEnvironment = {
    document: typeof document === 'undefined' ? undefined : document,
    navigator:
      typeof navigator === 'undefined'
        ? undefined
        : (navigator as unknown as NavigatorWithWakeLock),
    window: typeof window === 'undefined' ? undefined : window,
  },
): ReaderPlatformServices {
  return new DesktopReaderHost(environment).createServices()
}

interface FolderResponse {
  ref: string
  displayPath: string
}

interface FileResponse {
  format: 'pdf' | 'cbz' | 'zip'
  ref: string
  fileName: string
  displayPath: string
}

type RawLocalFileRecord = Omit<LocalFileRecord, 'fileRef'> & { fileRef: string }

type RawImportLocalFilesResult = Omit<ImportLocalFilesResult, 'results'> & {
  results?: Array<{
    result: string
    fileRef?: string
    displayPath?: string
    fileName?: string
    id?: number
  }>
}

type RawLocalFileStorageDeleteResult = Omit<LocalFileStorageDeleteResult, 'file'> & {
  fileRef: string
  displayPath: string
  fileName: string
}

type RawExportTaskRecord = Omit<ExportTaskRecord, 'outputFile' | 'displayPath'> & {
  targetFolderRef: string
  targetName: string
  outputFileRef?: string
  displayPath?: string
}

type RawExportSubmissionTaskResult = Partial<RawExportTaskRecord> &
  Pick<ExportSubmissionTaskResult, 'accepted' | 'errorCode' | 'errorMessage'>

function toLocalFileRecord(file: RawLocalFileRecord): LocalFileRecord {
  return { ...file, fileRef: asFileRef(file.fileRef) }
}

function toFileDescriptor(fileRef: string, displayPath: string, fileName?: string): FileDescriptor {
  return { ref: asFileRef(fileRef), displayPath, fileName: fileName || displayPath }
}

function toExportTaskRecord(task: RawExportTaskRecord): ExportTaskRecord {
  const { outputFileRef, displayPath, targetFolderRef, targetName, ...rest } = task
  void targetFolderRef
  return {
    ...rest,
    ...(outputFileRef && displayPath
      ? {
          outputFile: toFileDescriptor(
            outputFileRef,
            displayPath,
            fileNameFromDisplayPath(displayPath, targetName),
          ),
        }
      : {}),
    displayPath,
  }
}

function toExportSubmissionTaskResult(
  task: RawExportSubmissionTaskResult,
): ExportSubmissionTaskResult {
  const { outputFileRef, displayPath, targetFolderRef, targetName, ...rest } = task
  void targetFolderRef
  return {
    ...rest,
    ...(outputFileRef && displayPath
      ? {
          outputFile: toFileDescriptor(
            outputFileRef,
            displayPath,
            fileNameFromDisplayPath(displayPath, targetName),
          ),
        }
      : {}),
    displayPath,
  }
}

function createFileService(fetcher: BackendFetch): FileService {
  return {
    pickFolder: (purpose) =>
      requestBackend<FolderResponse | null>(fetcher, 'pickFolder', { purpose }).then((folder) =>
        folder ? { ref: asFolderRef(folder.ref), displayPath: folder.displayPath } : null,
      ),
    getDefaultFolder: (purpose) =>
      requestBackend<FolderResponse>(fetcher, 'getDefaultFolder', { purpose }).then((folder) => ({
        ref: asFolderRef(folder.ref),
        displayPath: folder.displayPath,
      })),
    checkFilesExist: (files) =>
      requestBackend<{ existing: string[] }>(fetcher, 'checkFilesExist', {
        files: files.map(String),
      }).then((result) => ({ existing: result.existing.map(asFileRef) })),
    openFile: (file) =>
      requestBackend(fetcher, 'openFile', { file: String(file) }).then(() => undefined),
    openContainingFolder: (file) =>
      requestBackend(fetcher, 'openContainingFolder', { file: String(file) }).then(() => undefined),
    scanImportableFiles: (folder, formats) =>
      requestBackend<{ files: FileResponse[] }>(fetcher, 'scanImportableFiles', {
        folder: String(folder),
        formats,
      }).then((result) => ({
        files: result.files.map((file) => ({ ...file, ref: asFileRef(file.ref) })),
      })),
  }
}

function createDownloadLocationService(
  events: BackendEvents,
  fetcher: BackendFetch,
): PublicDownloadService {
  return {
    setPublic: (open) =>
      requestBackend<{
        success: boolean
        downloadPublic: boolean
        moved: number
        displayPath: string
        cleanupPending: boolean
        cleanupMessage?: string
      }>(fetcher, 'setDownloadPublic', { open }),
    getPublic: () =>
      requestBackend<{
        downloadPublic: boolean
        displayPath: string
        cleanupPending: boolean
        cleanupMessage?: string
      }>(fetcher, 'getDownloadPublic', {}),
    requestStoragePermission: async () => ({
      granted: true,
      permissionType: 'not_required',
      apiLevel: 0,
    }),
    onRelocationProgress: (handler) => events.onRelocationProgress(handler),
  }
}

function createLocalFileService(events: BackendEvents, fetcher: BackendFetch): LocalFileService {
  return {
    exportBatch: ({ tasks }: { tasks: ExportTask[] }) =>
      requestBackend<{ tasks: RawExportSubmissionTaskResult[] }>(fetcher, 'exportBatch', {
        tasks: tasks.map(({ target, ...task }) => ({
          ...task,
          target: { folder: String(target.folder), relativePath: target.relativePath },
        })),
      }).then(
        (result): ExportBatchResult => ({
          tasks: result.tasks.map(toExportSubmissionTaskResult),
        }),
      ),
    scanImportableFiles: (folder, formats) =>
      requestBackend<{ files: FileResponse[] }>(fetcher, 'scanImportableFiles', {
        folder: String(folder),
        formats,
      }).then((result) => ({
        files: result.files.map((file) => ({ ...file, ref: asFileRef(file.ref) })),
      })),
    importLocalFiles: (items: ImportLocalFileItem[]) =>
      requestBackend<RawImportLocalFilesResult>(fetcher, 'importLocalFiles', {
        items: items.map(({ fileRef, ...item }) => ({ ...item, fileRef: String(fileRef) })),
      }).then((result) => ({
        ...result,
        ...(result.results
          ? {
              results: result.results.map((item) => ({
                result: item.result,
                ...(item.fileRef && item.displayPath
                  ? {
                      file: toFileDescriptor(item.fileRef, item.displayPath, item.fileName),
                    }
                  : {}),
                ...(item.id !== undefined ? { id: item.id } : {}),
              })),
            }
          : {}),
      })),
    getImportedLocalFiles: () =>
      requestBackend<{ files: RawLocalFileRecord[] }>(fetcher, 'getImportedLocalFiles', {}).then(
        (result) => ({
          files: result.files.map(toLocalFileRecord),
        }),
      ),
    getLocalFiles: (options) =>
      requestBackend<{ files: RawLocalFileRecord[]; nextCursor?: string }>(
        fetcher,
        'getLocalFiles',
        options,
      ).then((result) => ({
        files: result.files.map(toLocalFileRecord),
        nextCursor: result.nextCursor,
      })),
    refreshLocalFileAvailability: (ids) =>
      requestBackend<{ files: RawLocalFileRecord[] }>(fetcher, 'refreshLocalFileAvailability', {
        ids,
      }).then((result) => ({ files: result.files.map(toLocalFileRecord) })),
    inspectLocalFileForDeletion: (id) =>
      requestBackend<RawLocalFileRecord>(fetcher, 'inspectLocalFileForDeletion', { id }).then(
        toLocalFileRecord,
      ),
    verifyLocalFile: (id) =>
      requestBackend<RawLocalFileRecord>(fetcher, 'verifyLocalFile', { id }).then(
        toLocalFileRecord,
      ),
    removeLocalFileFromLibrary: (id) =>
      requestBackend<{ success: boolean }>(fetcher, 'removeLocalFileFromLibrary', { id }),
    deleteLocalFile: (id) =>
      requestBackend<RawLocalFileStorageDeleteResult>(fetcher, 'deleteLocalFile', { id }).then(
        ({ fileRef, displayPath, fileName, ...result }) => ({
          ...result,
          file: toFileDescriptor(fileRef, displayPath, fileName),
        }),
      ),
    getLocalFileManagementState: () =>
      requestBackend<LocalFileManagementState>(fetcher, 'getLocalFileManagementState', {}),
    acknowledgeLocalFileDatabaseReset: () =>
      requestBackend<{ acknowledged: boolean }>(fetcher, 'acknowledgeLocalFileDatabaseReset', {}),
    getExportTasks: (options) =>
      requestBackend<{ tasks: RawExportTaskRecord[]; nextCursor?: string }>(
        fetcher,
        'getExportTasks',
        options,
      ).then((result) => ({
        tasks: result.tasks.map(toExportTaskRecord),
        nextCursor: result.nextCursor,
      })),
    getExportTask: (exportId) =>
      requestBackend<RawExportTaskRecord>(fetcher, 'getExportTask', { exportId }).then(
        toExportTaskRecord,
      ),
    cancelExport: (exportId) =>
      requestBackend<RawExportTaskRecord>(fetcher, 'cancelExport', { exportId }).then(
        toExportTaskRecord,
      ),
    retryExport: (exportId, allowOverwrite = false) =>
      requestBackend<RawExportTaskRecord>(fetcher, 'retryExport', {
        exportId,
        allowOverwrite,
      }).then(toExportTaskRecord),
    deleteExportTask: (exportId) =>
      requestBackend<{ success: boolean }>(fetcher, 'deleteExportTask', { exportId }),
    deleteImportedLocalFile: (id) =>
      requestBackend<{ success: boolean }>(fetcher, 'deleteImportedLocalFile', { id }),
    updateLocalEpisodeType: (albumId, isSingleEpisode) =>
      requestBackend<{ success: boolean; updatedDownloads: number; updatedLocalFiles: number }>(
        fetcher,
        'updateLocalEpisodeType',
        { albumId, isSingleEpisode },
      ),
    openLocalFile: (file) =>
      requestBackend<{ success: boolean }>(fetcher, 'openLocalFile', { fileRef: String(file) }),
    openLocalFileFolder: (file) =>
      requestBackend<{ success: boolean }>(fetcher, 'openLocalFileFolder', {
        fileRef: String(file),
      }),
    getPdfInfo: (file) =>
      requestBackend<{ pageCount: number }>(fetcher, 'getPdfInfo', { fileRef: String(file) }),
    onProgress: (handler) => events.onExportProgress(handler),
  }
}

/** 提供明确的当前平台能力状态。 */
export function createPlatformServices(
  events: BackendEvents,
  fetcher: BackendFetch,
): PlatformServices {
  const appInfo: AppInfo = {
    name: 'JQ Viewer',
    version: packageInfo.version,
  }

  const platformServices: PlatformServices = {
    app: { getInfo: async () => appInfo },
    notifications: { kind: 'host-managed' },
    files: createFileService(fetcher),
    localFiles: createLocalFileService(events, fetcher),
    exportPreferences: createDesktopExportPreferencesStore(fetcher),
    storage: { available: true, api: createDownloadLocationService(events, fetcher) },
    reader: createDesktopReaderServices(),
    updater: { available: true, api: createDesktopUpdater(events, fetcher) },
    ocr: { available: true, api: createOcrService(fetcher) },
    diagnostics: { available: true, api: createDiagnosticsService(fetcher) },
    launchRoutes: {
      available: true,
      api: {
        consume: () => requestBackend<{ route?: string }>(fetcher, 'consumeLaunchRoute', {}),
        onRoute: (handler) => events.onLaunchRoute(handler),
      },
    },
    events,
  }

  return platformServices
}
