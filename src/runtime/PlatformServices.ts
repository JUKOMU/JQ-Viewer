import type { RelocationProgress } from '@/services/JmcomicTypes'
import type {
  ImportedPdf,
  ImportedPdfsResult,
  ImportPdfItem,
  ImportPdfsResult,
  PdfExportBatchResult,
  PdfExportProgressEvent,
  PdfExportStatus,
  PdfExportTask,
  PdfExportTaskRecord,
  PdfManagementState,
  PdfScanItem,
  PdfStorageDeleteResult,
} from '@/services/JmcomicTypes'
import type { BackendEvents, ListenerHandle } from './BackendEvents'
import type { FileDescriptor, FileRef, FolderDescriptor, FolderRef } from './FileReferences'
import type { UpdaterService } from './UpdateTypes'

/**
 * 面向某一平台可选能力的判别联合：能力与实现绑定。
 * 当 available 为 false 时调用方拿不到 API，从结构上杜绝「平台返回伪成功但什么都没做」。
 */
export type Capability<T> =
  | { available: true; api: T }
  | { available: false; reason: string }

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

/** 文件系统平台能力：目录选择、默认目录、存在性检查、打开文件/目录与 PDF 扫描。 */
export interface FileService {
  pickFolder(purpose: 'pdf-root' | 'pdf-export' | 'download'): Promise<FolderDescriptor | null>
  getDefaultFolder(purpose: 'pdf-root' | 'pdf-export' | 'download'): Promise<FolderDescriptor>
  checkFilesExist(files: FileRef[]): Promise<{ existing: FileRef[] }>
  openFile(file: FileRef): Promise<void>
  openContainingFolder(file: FileRef): Promise<void>
  scanPdfFiles(folder: FolderRef): Promise<{ files: FileDescriptor[] }>
}

/** Android 专属的「公开下载」能力：存储权限、公开目录切换与迁移进度监听。 */
export interface PublicDownloadService {
  setPublic(open: boolean): Promise<{ success: boolean; downloadPublic: boolean; moved: number }>
  getPublic(): Promise<{ downloadPublic: boolean }>
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

/** PDF 平台能力：导入、导出、列表、校验、删除、打开与逐页渲染等文件生命周期操作。 */
export interface PdfService {
  exportPdfBatch(options: { tasks: PdfExportTask[] }): Promise<PdfExportBatchResult>
  scanPdfFiles(folder: FolderRef): Promise<{ files: PdfScanItem[] }>
  importPdfs(items: ImportPdfItem[]): Promise<ImportPdfsResult>
  getImportedPdfs(): Promise<ImportedPdfsResult>
  getPdfFiles(options: {
    sourceType?: 'imported' | 'exported'
    availability?: ImportedPdf['availability'] | 'problem'
    folderId?: string
    query?: string
    cursor?: string
    limit: number
  }): Promise<{ files: ImportedPdf[]; nextCursor?: string }>
  refreshPdfFileAvailability(ids: number[]): Promise<{ files: ImportedPdf[] }>
  inspectPdfFileForDeletion(id: number): Promise<ImportedPdf>
  verifyPdfFile(id: number): Promise<ImportedPdf>
  removePdfFromLibrary(id: number): Promise<{ success: boolean }>
  deletePdfFile(id: number): Promise<PdfStorageDeleteResult>
  getPdfManagementState(): Promise<PdfManagementState>
  acknowledgePdfDatabaseReset(): Promise<{ acknowledged: boolean }>
  getPdfExportTasks(options: {
    status?: PdfExportStatus
    cursor?: string
    limit: number
  }): Promise<{ tasks: PdfExportTaskRecord[]; nextCursor?: string }>
  getPdfExportTask(exportId: string): Promise<PdfExportTaskRecord>
  cancelPdfExport(exportId: string): Promise<PdfExportTaskRecord>
  retryPdfExport(exportId: string, allowOverwrite?: boolean): Promise<PdfExportTaskRecord>
  deletePdfExportTask(exportId: string): Promise<{ success: boolean }>
  deleteImportedPdf(id: number): Promise<{ success: boolean }>
  updateLocalEpisodeType(
    albumId: string,
    isSingleEpisode: boolean,
  ): Promise<{ success: boolean; updatedDownloads: number; updatedPdfs: number }>
  openPdf(file: FileRef): Promise<{ success: boolean }>
  openPdfFolder(file: FileRef): Promise<{ success: boolean }>
  getPdfInfo(file: FileRef): Promise<{ pageCount: number }>
  renderPdfPage(file: FileRef, page: number, targetWidth: number): Promise<{ imageUrl: string }>
  onProgress(handler: (event: PdfExportProgressEvent) => void): Promise<ListenerHandle>
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

/** 平台服务聚合：应用信息、通知、文件、公开下载、阅读器、更新、OCR、启动路由与 PDF。 */
export interface PlatformServices {
  app: AppService
  notifications: NotificationPolicy
  files: FileService
  storage: Capability<PublicDownloadService>
  reader: ReaderPlatformServices
  updater: Capability<UpdaterService>
  ocr: Capability<OcrService>
  launchRoutes: Capability<LaunchRouteService>
  pdf: PdfService
  events: BackendEvents
}
