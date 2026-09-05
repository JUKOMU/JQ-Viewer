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

export type Capability<T> =
  | { available: true; api: T }
  | { available: false; reason: string }

export interface AppInfo {
  name: string
  version: string
  build?: string
}

export interface AppService {
  getInfo(): Promise<AppInfo>
}

export interface NotificationPermissionPort {
  check(): Promise<{ granted: boolean }>
  request(): Promise<{ granted: boolean }>
  openSettings(): Promise<{ opened: boolean }>
}

export type NotificationPolicy =
  | { kind: 'runtime-permission'; permissions: NotificationPermissionPort }
  | { kind: 'host-managed' }
  | { kind: 'unavailable'; reason: string }

export interface FileService {
  pickFolder(purpose: 'pdf-root' | 'pdf-export' | 'download'): Promise<FolderDescriptor | null>
  getDefaultFolder(purpose: 'pdf-root' | 'pdf-export' | 'download'): Promise<FolderDescriptor>
  checkFilesExist(files: FileRef[]): Promise<{ existing: FileRef[] }>
  openFile(file: FileRef): Promise<void>
  openContainingFolder(file: FileRef): Promise<void>
  scanPdfFiles(folder: FolderRef): Promise<{ files: FileDescriptor[] }>
}

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

export interface OcrService {
  setEnabled(enabled: boolean): Promise<{ success: boolean }>
  pickImageAndOcr(): Promise<{ text: string; error?: string }>
}

export interface LaunchRouteService {
  consume(): Promise<{ route?: string }>
  onRoute(handler: (event: { route: string }) => void): Promise<ListenerHandle>
}

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
