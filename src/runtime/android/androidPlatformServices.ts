import { App } from '@capacitor/app'
import type {
  AndroidImportPdfsResult,
  AndroidImportedPdf,
  AndroidPdfExportBatchResult,
  AndroidPdfExportSubmissionTaskResult,
  AndroidPdfExportTaskRecord,
  AndroidPdfScanItem,
  AndroidPdfStorageDeleteResult,
  JmcomicClient,
} from '@/services/jmcomic/JmcomicClient'
import type {
  ImportedPdf,
  ImportPdfItem,
  PdfExportSubmissionTaskResult,
  PdfExportTask,
  PdfExportTaskRecord,
  PdfStorageDeleteResult,
  RelocationProgress,
} from '@/services/JmcomicTypes'
import type { BackendEvents } from '../BackendEvents'
import {
  asFileRef,
  asFolderRef,
  type FileDescriptor,
  type FileRef,
} from '../FileReferences'
import { RuntimeError, withRuntimeError } from '../errors'
import type {
  AppInfo,
  FileService,
  NotificationPermissionPort,
  PdfService,
  PlatformServices,
  PublicDownloadService,
} from '../PlatformServices'
import { createAndroidUpdater } from './androidUpdater'

/** 校验原生返回的成功标志，失败时抛出带中文提示的错误。 */
function ensureSuccess(result: { success: boolean }, message: string): void {
  if (!result.success) throw new Error(message)
}

/** Android 文件服务：只在边界把 opaque ref 包装为公共 branded 类型。 */
function createFileService(native: JmcomicClient): FileService {
  return {
    pickFolder: async () => {
      const result = await withRuntimeError(() => native.pickFolder())
      if (result.cancelled || !result.folderRef) return null
      return {
        ref: asFolderRef(result.folderRef),
        displayPath: result.displayPath,
      }
    },
    getDefaultFolder: async () => {
      const result = await withRuntimeError(() => native.getExternalStoragePath())
      return { ref: asFolderRef(result.folderRef), displayPath: result.displayPath }
    },
    checkFilesExist: async (files: FileRef[]) => {
      const result = await withRuntimeError(() =>
        native.checkFilesExist({ fileRefs: files.map((file) => String(file)) }),
      )
      return { existing: result.existingFileRefs.map(asFileRef) }
    },
    openFile: async (file) => {
      const result = await withRuntimeError(() => native.openPdf({ fileRef: String(file) }))
      ensureSuccess(result, '无法打开 PDF 文件')
    },
    openContainingFolder: async (file) => {
      const result = await withRuntimeError(() => native.openPdfFolder({ fileRef: String(file) }))
      ensureSuccess(result, '无法打开 PDF 所在文件夹')
    },
    scanPdfFiles: async (folder) => {
      const result = await withRuntimeError(() =>
        native.scanPdfFiles({ folderRef: String(folder) }),
      )
      return {
        files: result.files.map((file) => ({
          ref: asFileRef(file.fileRef),
          fileName: file.fileName,
          displayPath: file.displayPath,
        })),
      }
    },
  }
}

/** 通知权限端口：直接透传 Android 原生权限方法。 */
function createNotificationPort(native: JmcomicClient): NotificationPermissionPort {
  return {
    check: () => withRuntimeError(() => native.checkNotificationPermission()),
    request: () => withRuntimeError(() => native.requestNotificationPermission()),
    openSettings: () => withRuntimeError(() => native.openNotificationSettings()),
  }
}

/** 组装 Android 公开下载能力，迁移进度监听复用统一事件端口。 */
function createPublicDownloadService(
  native: JmcomicClient,
  events: BackendEvents,
): PublicDownloadService {
  return {
    setPublic: (open) => withRuntimeError(() => native.setDownloadPublic({ open })),
    getPublic: () => withRuntimeError(() => native.getDownloadPublic()),
    requestStoragePermission: () => withRuntimeError(() => native.requestManageStorage()),
    onRelocationProgress: (handler: (event: RelocationProgress) => void) =>
      events.onRelocationProgress(handler),
  }
}

function toFileDescriptor(
  fileRef: string,
  displayPath: string,
  fileName?: string,
): FileDescriptor {
  return { ref: asFileRef(fileRef), fileName: fileName || displayPath, displayPath }
}

/** 导入操作只接受平台文件引用，displayPath 不得作为缺失引用时的隐式目标。 */
function requireImportFileRef(fileRef: unknown): FileRef {
  if (typeof fileRef !== 'string' || fileRef.trim().length === 0) {
    throw new RuntimeError('not-found', 'PDF 文件引用无效')
  }
  return asFileRef(fileRef)
}

/** 把 Android 原生 ImportedPdf 转换为公共 ImportedPdf。 */
function toImportedPdf(file: AndroidImportedPdf): ImportedPdf {
  const { fileRef, displayPath, ...rest } = file
  return { ...rest, fileRef: asFileRef(fileRef), displayPath }
}

/** 把 Android 原生导出任务记录转换为公共导出任务记录。 */
function toPdfExportTaskRecord(task: AndroidPdfExportTaskRecord): PdfExportTaskRecord {
  const { outputFileRef, displayPath, ...rest } = task
  return {
    ...rest,
    ...(outputFileRef && displayPath
      ? { outputFile: toFileDescriptor(outputFileRef, displayPath) }
      : {}),
    displayPath,
  }
}

/** 把 Android 原生导出提交结果转换为公共提交结果。 */
function toPdfExportSubmissionTaskResult(
  task: AndroidPdfExportSubmissionTaskResult,
): PdfExportSubmissionTaskResult {
  const { outputFileRef, displayPath, ...rest } = task
  return {
    ...rest,
    ...(outputFileRef && displayPath
      ? { outputFile: toFileDescriptor(outputFileRef, displayPath) }
      : {}),
    displayPath,
  }
}

/** 把 Android 原生导入结果转换为公共结果。 */
function toImportPdfsResult(result: AndroidImportPdfsResult) {
  return {
    ...result,
    ...(result.results
      ? {
          results: result.results.map((item) => ({
            result: item.result,
            ...(item.fileRef && item.displayPath
              ? { file: toFileDescriptor(item.fileRef, item.displayPath, item.fileName) }
              : {}),
            ...(item.id !== undefined ? { id: item.id } : {}),
          })),
        }
      : {}),
  }
}

/** 把 Android 原生删除结果转换为公共结果。 */
function toPdfStorageDeleteResult(result: AndroidPdfStorageDeleteResult): PdfStorageDeleteResult {
  const { fileRef, displayPath, fileName, ...rest } = result
  return { ...rest, file: toFileDescriptor(fileRef, displayPath, fileName) }
}

/**
 * Android 原生 PDF 方法清单。文件服务与 ResourceResolver 分别承接不同职责，
 * 该常量仅保留清单用途，方便与原生契约对照审计。
 */
const ANDROID_PDF_METHODS = [
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
] as const

/**
 * 构造 Android PDF 平台服务。公共层使用 FileRef/FolderRef 表达文件位置，
 * 本服务负责把 branded ref 映射为原生 DTO，并把原生 DTO 折叠为平台中立的文件描述。
 * 逐页渲染属于 ResourceResolver，不在 PDF 文件服务中重复暴露。
 */
function createPdfService(native: JmcomicClient, events: BackendEvents): PdfService {
  void ANDROID_PDF_METHODS
  return {
    exportPdfBatch: ({ tasks }: { tasks: PdfExportTask[] }) =>
      withRuntimeError(async () => {
        const result: AndroidPdfExportBatchResult = await native.exportPdfBatch({
          tasks: tasks.map(({ target, displayPath, ...task }) => ({
            ...task,
            targetFolderRef: String(target.folder),
            targetName: target.relativePath,
            displayPath,
          })),
        })
        return { tasks: result.tasks.map(toPdfExportSubmissionTaskResult) }
      }),
    scanPdfFiles: (folder) =>
      withRuntimeError(async () => {
        const result: { files: AndroidPdfScanItem[] } = await native.scanPdfFiles({
          folderRef: String(folder),
        })
        return {
          files: result.files.map((file) => ({
            ref: asFileRef(file.fileRef),
            fileName: file.fileName,
            displayPath: file.displayPath,
          })),
        }
      }),
    importPdfs: (items: ImportPdfItem[]) =>
      withRuntimeError(() =>
        native
          .importPdfs({
            items: items.map(({ fileRef, displayPath: _displayPath, ...item }) => ({
              ...item,
              fileRef: String(requireImportFileRef(fileRef)),
              displayPath: _displayPath,
            })),
          })
          .then(toImportPdfsResult),
      ),
    getImportedPdfs: () =>
      withRuntimeError(async () => {
        const result = await native.getImportedPdfs()
        return { pdfs: result.pdfs.map(toImportedPdf) }
      }),
    getPdfFiles: (options) =>
      withRuntimeError(async () => {
        const result = await native.getPdfFiles(options)
        return { files: result.files.map(toImportedPdf), nextCursor: result.nextCursor }
      }),
    refreshPdfFileAvailability: (ids) =>
      withRuntimeError(async () => {
        const result = await native.refreshPdfFileAvailability({ ids })
        return { files: result.files.map(toImportedPdf) }
      }),
    inspectPdfFileForDeletion: (id) =>
      withRuntimeError(async () => toImportedPdf(await native.inspectPdfFileForDeletion({ id }))),
    verifyPdfFile: (id) =>
      withRuntimeError(async () => toImportedPdf(await native.verifyPdfFile({ id }))),
    removePdfFromLibrary: (id) =>
      withRuntimeError(() => native.removePdfFromLibrary({ id })),
    deletePdfFile: (id) =>
      withRuntimeError(async () => toPdfStorageDeleteResult(await native.deletePdfFile({ id }))),
    getPdfManagementState: () => withRuntimeError(() => native.getPdfManagementState()),
    acknowledgePdfDatabaseReset: () =>
      withRuntimeError(() => native.acknowledgePdfDatabaseReset()),
    getPdfExportTasks: (options) =>
      withRuntimeError(async () => {
        const result = await native.getPdfExportTasks(options)
        return { tasks: result.tasks.map(toPdfExportTaskRecord), nextCursor: result.nextCursor }
      }),
    getPdfExportTask: (exportId) =>
      withRuntimeError(async () => toPdfExportTaskRecord(await native.getPdfExportTask({ exportId }))),
    cancelPdfExport: (exportId) =>
      withRuntimeError(async () => toPdfExportTaskRecord(await native.cancelPdfExport({ exportId }))),
    retryPdfExport: (exportId, allowOverwrite = false) =>
      withRuntimeError(async () =>
        toPdfExportTaskRecord(await native.retryPdfExport({ exportId, allowOverwrite })),
      ),
    deletePdfExportTask: (exportId) =>
      withRuntimeError(() => native.deletePdfExportTask({ exportId })),
    deleteImportedPdf: (id) => withRuntimeError(() => native.deleteImportedPdf({ id })),
    updateLocalEpisodeType: (albumId, isSingleEpisode) =>
      withRuntimeError(() => native.updateLocalEpisodeType({ albumId, isSingleEpisode })),
    openPdf: (file) => withRuntimeError(() => native.openPdf({ fileRef: String(file) })),
    openPdfFolder: (file) =>
      withRuntimeError(() => native.openPdfFolder({ fileRef: String(file) })),
    getPdfInfo: (file) => withRuntimeError(() => native.getPdfInfo({ fileRef: String(file) })),
    onProgress: (handler) => events.onPdfExportProgress(handler),
  }
}

/**
 * 装配 Android 全部平台服务，把原生能力转换为 capability 表达：
 * Android 拥有这些真实能力，因此均标记 available；永久缺失的能力则由其他平台
 * 以 unavailable 表达。
 */
export function createAndroidPlatformServices(
  native: JmcomicClient,
  events: BackendEvents,
): PlatformServices {
  const notifications = createNotificationPort(native)
  const updater = createAndroidUpdater(native)
  const publicDownload = createPublicDownloadService(native, events)

  return {
    app: {
      getInfo: async (): Promise<AppInfo> => {
        const info = await withRuntimeError(() => App.getInfo())
        return { name: info.name, version: info.version, build: info.build }
      },
    },
    notifications: { kind: 'runtime-permission', permissions: notifications },
    files: createFileService(native),
    storage: { available: true, api: publicDownload },
    reader: {
      orientation: {
        available: true,
        api: {
          set: (orientation) =>
            withRuntimeError(() => native.setReaderScreenOrientation({ orientation })),
        },
      },
      brightness: {
        available: true,
        api: {
          set: (brightness) => withRuntimeError(() => native.setReaderBrightness({ brightness })),
        },
      },
      keepAwake: {
        available: true,
        api: {
          set: (enabled) => withRuntimeError(() => native.setReaderKeepScreenOn({ enabled })),
        },
      },
      fullscreen: {
        available: true,
        api: {
          set: (enabled) => withRuntimeError(() => native.setReaderFullscreen({ enabled })),
        },
      },
      volumeKeys: {
        available: true,
        api: {
          setEnabled: (enabled) =>
            withRuntimeError(() => native.setReaderVolumeNavigation({ enabled })),
          onKey: (handler) => events.onVolumeKey((event) => handler(event.direction)),
        },
      },
      hostState: {
        available: true,
        api: {
          setState: (isActive, isVertical) =>
            withRuntimeError(() => native.setReaderState({ isActive, isVertical })),
        },
      },
    },
    updater: { available: true, api: updater },
    ocr: {
      available: true,
      api: {
        setEnabled: (enabled) => withRuntimeError(() => native.setOcrEnabled({ enabled })),
        pickImageAndOcr: () => withRuntimeError(() => native.pickImageAndOcr()),
      },
    },
    launchRoutes: {
      available: true,
      api: {
        consume: () => withRuntimeError(() => native.consumeLaunchRoute()),
        onRoute: (handler) => events.onLaunchRoute(handler),
      },
    },
    pdf: createPdfService(native, events),
    events,
  }
}
