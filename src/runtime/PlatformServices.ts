import type { RelocationProgress } from '@/services/JmcomicTypes'
import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
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

/**
 * PDF command/query methods remain behind the platform adapter. The raw
 * Android path fields are temporary adapter inputs and are not used by the
 * common services that will consume FileRef in the next integration stage.
 */
export const PDF_PLATFORM_METHODS = [
  'exportPdfBatch',
  'scanPdfFiles',
  'importPdfs',
  'getImportedPdfs',
  'getPdfFiles',
  'refreshPdfFileAvailability',
  'inspectPdfFileForDeletion',
  'verifyPdfFile',
  'removePdfFromLibrary',
  'deletePdfFile',
  'getPdfManagementState',
  'acknowledgePdfDatabaseReset',
  'getPdfExportTasks',
  'getPdfExportTask',
  'cancelPdfExport',
  'retryPdfExport',
  'deletePdfExportTask',
  'deleteImportedPdf',
  'openPdf',
  'openPdfFolder',
  'getPdfInfo',
  'renderPdfPage',
] as const satisfies readonly (keyof JmcomicClient)[]

export type PdfService = Pick<JmcomicClient, (typeof PDF_PLATFORM_METHODS)[number]>

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
