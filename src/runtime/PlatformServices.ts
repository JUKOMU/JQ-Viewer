import type { RelocationProgress } from '@/services/JmcomicTypes'
import type {
  LocalFileRecord,
  ImportedLocalFilesResult,
  ImportLocalFileItem,
  ImportLocalFilesResult,
  ExportBatchResult,
  ExportFormat,
  ExportProgressEvent,
  ExportStatus,
  ExportTask,
  ExportTaskRecord,
  LocalFileManagementState,
  LocalFileScanItem,
  LocalFileStorageDeleteResult,
  CbzDocumentInfo,
} from '@/services/JmcomicTypes'
import type { BackendEvents, ListenerHandle } from './BackendEvents'
import type { FileRef, FolderDescriptor, FolderRef } from './FileReferences'
import type { ExportPreferencesStore } from './ExportPreferences'
import type { UpdaterService } from './UpdateTypes'

/**
 * 面向某一平台可选能力的判别联合：能力与实现绑定。
 * 当 available 为 false 时调用方拿不到 API，从结构上杜绝「平台返回伪成功但什么都没做」。
 */
export type Capability<T> = { available: true; api: T } | { available: false; reason: string }

export interface AppInfo {
  name: string
  version: string
  build?: string
}

/** 应用元信息（名称、版本等），替代页面直接调用 @capacitor/app 的 App.getInfo()。 */
export interface AppService {
  getInfo(): Promise<AppInfo>
}

/** 通知权限能力：查询、申请与打开系统通知设置。 */
export interface NotificationPermissionPort {
  check(): Promise<{ granted: boolean }>
  request(): Promise<{ granted: boolean }>
  openSettings(): Promise<{ opened: boolean }>
}

/**
 * 通知策略：
 * - runtime-permission 表示该平台需要向用户请求运行时通知权限（如 Android）；
 * - host-managed 表示通知由宿主/后端直接负责，前端无需处理权限；
 * - unavailable 表示该平台不支持通知。
 */
export type NotificationPolicy =
  | { kind: 'runtime-permission'; permissions: NotificationPermissionPort }
  | { kind: 'host-managed' }
  | { kind: 'unavailable'; reason: string }

/** 文件系统平台能力：目录选择、默认目录、存在性检查、打开文件/目录与可导入文件扫描。 */
export interface FileService {
  pickFolder(purpose: 'local-file-root' | 'export' | 'download'): Promise<FolderDescriptor | null>
  getDefaultFolder(purpose: 'local-file-root' | 'export' | 'download'): Promise<FolderDescriptor>
  checkFilesExist(files: FileRef[]): Promise<{ existing: FileRef[] }>
  openFile(file: FileRef): Promise<void>
  openContainingFolder(file: FileRef): Promise<void>
  scanImportableFiles(
    folder: FolderRef,
    formats: ExportFormat[],
  ): Promise<{ files: LocalFileScanItem[] }>
}

/** 下载位置能力：Android 切换公开目录，Desktop 选择本地目录并迁移。 */
export interface PublicDownloadService {
  setPublic(open: boolean): Promise<{
    success: boolean
    downloadPublic: boolean
    moved: number
    displayPath?: string
    cleanupPending?: boolean
    cleanupMessage?: string
  }>
  getPublic(): Promise<{
    downloadPublic: boolean
    displayPath?: string
    cleanupPending?: boolean
    cleanupMessage?: string
  }>
  requestStoragePermission(): Promise<{
    granted: boolean
    permissionType: string
    apiLevel: number
  }>
  onRelocationProgress(handler: (event: RelocationProgress) => void): Promise<ListenerHandle>
}

/** OCR 能力：开关与「选图并识别」入口，作为可选 capability 暴露。 */
export interface OcrService {
  setEnabled(enabled: boolean): Promise<{ success: boolean }>
  pickImageAndOcr(): Promise<{ text: string; error?: string }>
}

/** 启动路由能力：一次性消费待处理路由，或订阅后续路由事件（如通知点击唤醒）。 */
export interface LaunchRouteService {
  consume(): Promise<{ route?: string }>
  onRoute(handler: (event: { route: string }) => void): Promise<ListenerHandle>
}

export interface DiagnosticPathEntry {
  kind: string
  label: string
  displayPath: string
}

export interface DiagnosticTaskFailure {
  id: string
  title: string
  status: string
  reason: string
  updatedAt: number
}

export interface DiagnosticTaskSummary {
  kind: string
  label: string
  total: number
  active: number
  failed: number
  recentFailures: DiagnosticTaskFailure[]
}

export interface DiagnosticClearableResource {
  kind: string
  label: string
  entryCount: number
  sizeBytes: number
}

export interface DiagnosticSnapshot {
  generatedAt: number
  paths: DiagnosticPathEntry[]
  tasks: DiagnosticTaskSummary[]
  clearableResources: DiagnosticClearableResource[]
}

/** 宿主诊断能力：只读取既有路径、任务和缓存状态，不创建独立遥测数据。 */
export interface DiagnosticsService {
  getSnapshot(): Promise<DiagnosticSnapshot>
}

/** 本地文件平台能力：导入、导出、列表、校验、删除与打开等文件生命周期操作。 */
export interface LocalFileService {
  exportBatch(options: { tasks: ExportTask[] }): Promise<ExportBatchResult>
  scanImportableFiles(
    folder: FolderRef,
    formats: ExportFormat[],
  ): Promise<{ files: LocalFileScanItem[] }>
  importLocalFiles(items: ImportLocalFileItem[]): Promise<ImportLocalFilesResult>
  getImportedLocalFiles(): Promise<ImportedLocalFilesResult>
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
  }): Promise<{ files: LocalFileRecord[]; nextCursor?: string }>
  refreshLocalFileAvailability(ids: number[]): Promise<{ files: LocalFileRecord[] }>
  inspectLocalFileForDeletion(id: number): Promise<LocalFileRecord>
  verifyLocalFile(id: number): Promise<LocalFileRecord>
  removeLocalFileFromLibrary(id: number): Promise<{ success: boolean }>
  deleteLocalFile(id: number): Promise<LocalFileStorageDeleteResult>
  getLocalFileManagementState(): Promise<LocalFileManagementState>
  acknowledgeLocalFileDatabaseReset(): Promise<{ acknowledged: boolean }>
  getExportTasks(options: {
    format?: ExportFormat
    status?: ExportStatus
    cursor?: string
    limit: number
  }): Promise<{ tasks: ExportTaskRecord[]; nextCursor?: string }>
  getExportTask(exportId: string): Promise<ExportTaskRecord>
  cancelExport(exportId: string): Promise<ExportTaskRecord>
  retryExport(exportId: string, allowOverwrite?: boolean): Promise<ExportTaskRecord>
  deleteExportTask(exportId: string): Promise<{ success: boolean }>
  deleteImportedLocalFile(id: number): Promise<{ success: boolean }>
  updateLocalEpisodeType(
    albumId: string,
    isSingleEpisode: boolean,
  ): Promise<{ success: boolean; updatedDownloads: number; updatedLocalFiles: number }>
  openLocalFile(file: FileRef): Promise<{ success: boolean }>
  openLocalFileFolder(file: FileRef): Promise<{ success: boolean }>
  getPdfInfo(file: FileRef): Promise<{ pageCount: number }>
  getCbzInfo(file: FileRef): Promise<CbzDocumentInfo>
  onProgress(handler: (event: ExportProgressEvent) => void): Promise<ListenerHandle>
}

/**
 * 阅读器相关的平台能力。每一项都是可选 capability：
 * 仅当平台真正拥有且语义相同时才 available，否则页面应隐藏对应设置或入口。
 */
export interface ReaderPlatformServices {
  orientation: Capability<{ set(orientation: string): Promise<{ success: boolean }> }>
  brightness: Capability<{ set(brightness: number): Promise<{ success: boolean }> }>
  keepAwake: Capability<{ set(enabled: boolean): Promise<{ success: boolean }> }>
  fullscreen: Capability<{ set(enabled: boolean): Promise<{ success: boolean }> }>
  volumeKeys: Capability<{
    setEnabled(enabled: boolean): Promise<{ success: boolean }>
    onKey(handler: (direction: 'up' | 'down') => void): Promise<ListenerHandle>
  }>
  hostState: Capability<{
    setState(isActive: boolean, isVertical: boolean): Promise<{ success: boolean }>
  }>
}

/** 平台服务聚合：应用信息、通知、文件、导出设置、本地文件、阅读器及宿主能力。 */
export interface PlatformServices {
  app: AppService
  notifications: NotificationPolicy
  files: FileService
  exportPreferences: ExportPreferencesStore
  storage: Capability<PublicDownloadService>
  reader: ReaderPlatformServices
  updater: Capability<UpdaterService>
  ocr: Capability<OcrService>
  diagnostics: Capability<DiagnosticsService>
  launchRoutes: Capability<LaunchRouteService>
  localFiles: LocalFileService
  events: BackendEvents
}
