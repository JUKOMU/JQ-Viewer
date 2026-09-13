import packageInfo from '../../../package.json'
import type {
  AppInfo,
  Capability,
  FileService,
  PdfService,
  PlatformServices,
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
  ImportedPdf,
  ImportPdfItem,
  ImportPdfsResult,
  PdfExportBatchResult,
  PdfExportSubmissionTaskResult,
  PdfExportTask,
  PdfExportTaskRecord,
  PdfManagementState,
  PdfStorageDeleteResult,
} from '@/services/JmcomicTypes'
import { requestBackend, type BackendFetch } from './backendClient'
import { createDesktopPdfExportPreferencesStore } from './pdfExportPreferences'

function unavailableCapability<T>(reason: string): Capability<T> {
  return { available: false, reason }
}

function createUnavailableReaderServices(): ReaderPlatformServices {
  return {
    orientation: unavailableCapability('当前平台不支持屏幕方向控制'),
    brightness: unavailableCapability('当前平台不支持屏幕亮度控制'),
    keepAwake: unavailableCapability('当前平台不支持防止熄屏'),
    fullscreen: unavailableCapability('当前平台不支持全屏控制'),
    volumeKeys: unavailableCapability('当前平台不支持音量键翻页'),
    hostState: unavailableCapability('当前平台不支持阅读器宿主状态控制'),
  }
}

interface FolderResponse {
  ref: string
  displayPath: string
}

interface FileResponse {
  ref: string
  fileName: string
  displayPath: string
}

type RawImportedPdf = Omit<ImportedPdf, 'fileRef'> & { fileRef: string }

type RawImportPdfsResult = Omit<ImportPdfsResult, 'results'> & {
  results?: Array<{
    result: string
    fileRef?: string
    displayPath?: string
    fileName?: string
    id?: number
  }>
}

type RawPdfStorageDeleteResult = Omit<PdfStorageDeleteResult, 'file'> & {
  fileRef: string
  displayPath: string
  fileName: string
}

type RawPdfExportTaskRecord = Omit<PdfExportTaskRecord, 'outputFile' | 'displayPath'> & {
  targetFolderRef: string
  targetName: string
  outputFileRef?: string
  displayPath?: string
}

type RawPdfExportSubmissionTaskResult = Partial<RawPdfExportTaskRecord> &
  Pick<PdfExportSubmissionTaskResult, 'accepted' | 'errorCode' | 'errorMessage'>

function toImportedPdf(file: RawImportedPdf): ImportedPdf {
  return { ...file, fileRef: asFileRef(file.fileRef) }
}

function toFileDescriptor(fileRef: string, displayPath: string, fileName?: string): FileDescriptor {
  return { ref: asFileRef(fileRef), displayPath, fileName: fileName || displayPath }
}

function toPdfExportTaskRecord(task: RawPdfExportTaskRecord): PdfExportTaskRecord {
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

function toPdfExportSubmissionTaskResult(
  task: RawPdfExportSubmissionTaskResult,
): PdfExportSubmissionTaskResult {
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
    scanPdfFiles: (folder) =>
      requestBackend<{ files: FileResponse[] }>(fetcher, 'scanPdfFiles', {
        folder: String(folder),
      }).then((result) => ({
        files: result.files.map((file) => ({ ...file, ref: asFileRef(file.ref) })),
      })),
  }
}

function createPdfService(events: BackendEvents, fetcher: BackendFetch): PdfService {
  return {
    exportPdfBatch: ({ tasks }: { tasks: PdfExportTask[] }) =>
      requestBackend<{ tasks: RawPdfExportSubmissionTaskResult[] }>(fetcher, 'exportPdfBatch', {
        tasks: tasks.map(({ target, ...task }) => ({
          ...task,
          target: { folder: String(target.folder), relativePath: target.relativePath },
        })),
      }).then(
        (result): PdfExportBatchResult => ({
          tasks: result.tasks.map(toPdfExportSubmissionTaskResult),
        }),
      ),
    scanPdfFiles: (folder) =>
      requestBackend<{ files: FileResponse[] }>(fetcher, 'scanPdfFiles', {
        folder: String(folder),
      }).then((result) => ({
        files: result.files.map((file) => ({ ...file, ref: asFileRef(file.ref) })),
      })),
    importPdfs: (items: ImportPdfItem[]) =>
      requestBackend<RawImportPdfsResult>(fetcher, 'importPdfs', {
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
    getImportedPdfs: () =>
      requestBackend<{ pdfs: RawImportedPdf[] }>(fetcher, 'getImportedPdfs', {}).then((result) => ({
        pdfs: result.pdfs.map(toImportedPdf),
      })),
    getPdfFiles: (options) =>
      requestBackend<{ files: RawImportedPdf[]; nextCursor?: string }>(
        fetcher,
        'getPdfFiles',
        options,
      ).then((result) => ({
        files: result.files.map(toImportedPdf),
        nextCursor: result.nextCursor,
      })),
    refreshPdfFileAvailability: (ids) =>
      requestBackend<{ files: RawImportedPdf[] }>(fetcher, 'refreshPdfFileAvailability', {
        ids,
      }).then((result) => ({ files: result.files.map(toImportedPdf) })),
    inspectPdfFileForDeletion: (id) =>
      requestBackend<RawImportedPdf>(fetcher, 'inspectPdfFileForDeletion', { id }).then(
        toImportedPdf,
      ),
    verifyPdfFile: (id) =>
      requestBackend<RawImportedPdf>(fetcher, 'verifyPdfFile', { id }).then(toImportedPdf),
    removePdfFromLibrary: (id) =>
      requestBackend<{ success: boolean }>(fetcher, 'removePdfFromLibrary', { id }),
    deletePdfFile: (id) =>
      requestBackend<RawPdfStorageDeleteResult>(fetcher, 'deletePdfFile', { id }).then(
        ({ fileRef, displayPath, fileName, ...result }) => ({
          ...result,
          file: toFileDescriptor(fileRef, displayPath, fileName),
        }),
      ),
    getPdfManagementState: () =>
      requestBackend<PdfManagementState>(fetcher, 'getPdfManagementState', {}),
    acknowledgePdfDatabaseReset: () =>
      requestBackend<{ acknowledged: boolean }>(fetcher, 'acknowledgePdfDatabaseReset', {}),
    getPdfExportTasks: (options) =>
      requestBackend<{ tasks: RawPdfExportTaskRecord[]; nextCursor?: string }>(
        fetcher,
        'getPdfExportTasks',
        options,
      ).then((result) => ({
        tasks: result.tasks.map(toPdfExportTaskRecord),
        nextCursor: result.nextCursor,
      })),
    getPdfExportTask: (exportId) =>
      requestBackend<RawPdfExportTaskRecord>(fetcher, 'getPdfExportTask', { exportId }).then(
        toPdfExportTaskRecord,
      ),
    cancelPdfExport: (exportId) =>
      requestBackend<RawPdfExportTaskRecord>(fetcher, 'cancelPdfExport', { exportId }).then(
        toPdfExportTaskRecord,
      ),
    retryPdfExport: (exportId, allowOverwrite = false) =>
      requestBackend<RawPdfExportTaskRecord>(fetcher, 'retryPdfExport', {
        exportId,
        allowOverwrite,
      }).then(toPdfExportTaskRecord),
    deletePdfExportTask: (exportId) =>
      requestBackend<{ success: boolean }>(fetcher, 'deletePdfExportTask', { exportId }),
    deleteImportedPdf: (id) =>
      requestBackend<{ success: boolean }>(fetcher, 'deleteImportedPdf', { id }),
    updateLocalEpisodeType: (albumId, isSingleEpisode) =>
      requestBackend<{ success: boolean; updatedDownloads: number; updatedPdfs: number }>(
        fetcher,
        'updateLocalEpisodeType',
        { albumId, isSingleEpisode },
      ),
    openPdf: (file) =>
      requestBackend<{ success: boolean }>(fetcher, 'openPdf', { fileRef: String(file) }),
    openPdfFolder: (file) =>
      requestBackend<{ success: boolean }>(fetcher, 'openPdfFolder', {
        fileRef: String(file),
      }),
    getPdfInfo: (file) =>
      requestBackend<{ pageCount: number }>(fetcher, 'getPdfInfo', { fileRef: String(file) }),
    onProgress: (handler) => events.onPdfExportProgress(handler),
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
    pdf: createPdfService(events, fetcher),
    pdfExportPreferences: createDesktopPdfExportPreferencesStore(fetcher),
    storage: unavailableCapability('当前平台不支持公开下载'),
    reader: createUnavailableReaderServices(),
    updater: unavailableCapability('当前平台不支持应用更新'),
    ocr: unavailableCapability('当前平台不支持 OCR'),
    launchRoutes: unavailableCapability('当前平台不支持启动路由'),
    events,
  }

  return platformServices
}
