import { App } from '@capacitor/app'
import type {
  AndroidImportLocalFilesResult,
  AndroidLocalFileRecord,
  AndroidExportBatchResult,
  AndroidExportSubmissionTaskResult,
  AndroidExportTaskRecord,
  AndroidLocalFileScanItem,
  AndroidLocalFileStorageDeleteResult,
  JmcomicClient,
} from '@/services/jmcomic/JmcomicClient'
import type {
  LocalFileRecord,
  ImportLocalFileItem,
  ExportSubmissionTaskResult,
  ExportTask,
  ExportTaskRecord,
  LocalFileStorageDeleteResult,
  RelocationProgress,
} from '@/services/JmcomicTypes'
import type { BackendEvents } from '../BackendEvents'
import {
  asFileRef,
  asFolderRef,
  fileNameFromDisplayPath,
  type FileDescriptor,
  type FileRef,
} from '../FileReferences'
import { RuntimeError, withRuntimeError } from '../errors'
import type {
  AppInfo,
  FileService,
  NotificationPermissionPort,
  LocalFileService,
  PlatformServices,
  PublicDownloadService,
} from '../PlatformServices'
import { createAndroidUpdater } from './androidUpdater'
import { createAndroidExportPreferencesStore } from './exportPreferences'

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
      const result = await withRuntimeError(() => native.openLocalFile({ fileRef: String(file) }))
      ensureSuccess(result, '无法打开 PDF 文件')
    },
    openContainingFolder: async (file) => {
      const result = await withRuntimeError(() => native.openLocalFileFolder({ fileRef: String(file) }))
      ensureSuccess(result, '无法打开 PDF 所在文件夹')
    },
    scanImportableFiles: async (folder, formats) => {
      const result = await withRuntimeError(() =>
        native.scanImportableFiles({ folderRef: String(folder), formats }),
      )
      return {
        files: result.files.map((file) => ({
          format: file.format,
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

/** 把 Android 原生 LocalFileRecord 转换为公共 LocalFileRecord。 */
function toLocalFileRecord(file: AndroidLocalFileRecord): LocalFileRecord {
  const { fileRef, displayPath, ...rest } = file
  return { ...rest, fileRef: asFileRef(fileRef), displayPath }
}

/** 把 Android 原生导出任务记录转换为公共导出任务记录。 */
function toExportTaskRecord(task: AndroidExportTaskRecord): ExportTaskRecord {
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

/** 把 Android 原生导出提交结果转换为公共提交结果。 */
function toExportSubmissionTaskResult(
  task: AndroidExportSubmissionTaskResult,
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

/** 把 Android 原生导入结果转换为公共结果。 */
function toImportLocalFilesResult(result: AndroidImportLocalFilesResult) {
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
function toLocalFileStorageDeleteResult(result: AndroidLocalFileStorageDeleteResult): LocalFileStorageDeleteResult {
  const { fileRef, displayPath, fileName, ...rest } = result
  return { ...rest, file: toFileDescriptor(fileRef, displayPath, fileName) }
}

/**
 * Android 原生 PDF 方法清单。文件服务与 ResourceResolver 分别承接不同职责，
 * 该常量仅保留清单用途，方便与原生契约对照审计。
 */
const ANDROID_PDF_METHODS = [
  'exportBatch',
  'scanImportableFiles',
  'importLocalFiles',
  'getImportedLocalFiles',
  'getLocalFiles',
  'refreshLocalFileAvailability',
  'inspectLocalFileForDeletion',
  'verifyLocalFile',
  'removeLocalFileFromLibrary',
  'deleteLocalFile',
  'getLocalFileManagementState',
  'acknowledgeLocalFileDatabaseReset',
  'getExportTasks',
  'getExportTask',
  'cancelExport',
  'retryExport',
  'deleteExportTask',
  'deleteImportedLocalFile',
  'openLocalFile',
  'openLocalFileFolder',
  'getPdfInfo',
  'renderPdfPage',
] as const

/**
 * 构造 Android PDF 平台服务。公共层使用 FileRef/FolderRef 表达文件位置，
 * 本服务负责把 branded ref 映射为原生 DTO，并把原生 DTO 折叠为平台中立的文件描述。
 * 逐页渲染属于 ResourceResolver，不在 PDF 文件服务中重复暴露。
 */
function createLocalFileService(native: JmcomicClient, events: BackendEvents): LocalFileService {
  void ANDROID_PDF_METHODS
  return {
    exportBatch: ({ tasks }: { tasks: ExportTask[] }) =>
      withRuntimeError(async () => {
        const result: AndroidExportBatchResult = await native.exportBatch({
          tasks: tasks.map(({ target, displayPath, ...task }) => ({
            ...task,
            targetFolderRef: String(target.folder),
            targetName: target.relativePath,
            displayPath,
          })),
        })
        return { tasks: result.tasks.map(toExportSubmissionTaskResult) }
      }),
    scanImportableFiles: (folder, formats) =>
      withRuntimeError(async () => {
        const result: { files: AndroidLocalFileScanItem[] } = await native.scanImportableFiles({
          folderRef: String(folder),
          formats,
        })
        return {
          files: result.files.map((file) => ({
            format: file.format,
            ref: asFileRef(file.fileRef),
            fileName: file.fileName,
            displayPath: file.displayPath,
          })),
        }
      }),
    importLocalFiles: (items: ImportLocalFileItem[]) =>
      withRuntimeError(() =>
        native
          .importLocalFiles({
            items: items.map(({ fileRef, displayPath: _displayPath, ...item }) => ({
              ...item,
              fileRef: String(requireImportFileRef(fileRef)),
              displayPath: _displayPath,
            })),
          })
          .then(toImportLocalFilesResult),
      ),
    getImportedLocalFiles: () =>
      withRuntimeError(async () => {
        const result = await native.getImportedLocalFiles()
        return { files: result.files.map(toLocalFileRecord) }
      }),
    getLocalFiles: (options) =>
      withRuntimeError(async () => {
        const result = await native.getLocalFiles(options)
        return { files: result.files.map(toLocalFileRecord), nextCursor: result.nextCursor }
      }),
    refreshLocalFileAvailability: (ids) =>
      withRuntimeError(async () => {
        const result = await native.refreshLocalFileAvailability({ ids })
        return { files: result.files.map(toLocalFileRecord) }
      }),
    inspectLocalFileForDeletion: (id) =>
      withRuntimeError(async () => toLocalFileRecord(await native.inspectLocalFileForDeletion({ id }))),
    verifyLocalFile: (id) =>
      withRuntimeError(async () => toLocalFileRecord(await native.verifyLocalFile({ id }))),
    removeLocalFileFromLibrary: (id) =>
      withRuntimeError(() => native.removeLocalFileFromLibrary({ id })),
    deleteLocalFile: (id) =>
      withRuntimeError(async () => toLocalFileStorageDeleteResult(await native.deleteLocalFile({ id }))),
    getLocalFileManagementState: () => withRuntimeError(() => native.getLocalFileManagementState()),
    acknowledgeLocalFileDatabaseReset: () =>
      withRuntimeError(() => native.acknowledgeLocalFileDatabaseReset()),
    getExportTasks: (options) =>
      withRuntimeError(async () => {
        const result = await native.getExportTasks(options)
        return { tasks: result.tasks.map(toExportTaskRecord), nextCursor: result.nextCursor }
      }),
    getExportTask: (exportId) =>
      withRuntimeError(async () => toExportTaskRecord(await native.getExportTask({ exportId }))),
    cancelExport: (exportId) =>
      withRuntimeError(async () => toExportTaskRecord(await native.cancelExport({ exportId }))),
    retryExport: (exportId, allowOverwrite = false) =>
      withRuntimeError(async () =>
        toExportTaskRecord(await native.retryExport({ exportId, allowOverwrite })),
      ),
    deleteExportTask: (exportId) =>
      withRuntimeError(() => native.deleteExportTask({ exportId })),
    deleteImportedLocalFile: (id) => withRuntimeError(() => native.deleteImportedLocalFile({ id })),
    updateLocalEpisodeType: (albumId, isSingleEpisode) =>
      withRuntimeError(() => native.updateLocalEpisodeType({ albumId, isSingleEpisode })),
    openLocalFile: (file) => withRuntimeError(() => native.openLocalFile({ fileRef: String(file) })),
    openLocalFileFolder: (file) =>
      withRuntimeError(() => native.openLocalFileFolder({ fileRef: String(file) })),
    getPdfInfo: (file) => withRuntimeError(() => native.getPdfInfo({ fileRef: String(file) })),
    onProgress: (handler) => events.onExportProgress(handler),
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
    exportPreferences: createAndroidExportPreferencesStore(),
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
    diagnostics: { available: false, reason: 'Android 由系统工具提供应用诊断信息' },
    launchRoutes: {
      available: true,
      api: {
        consume: () => withRuntimeError(() => native.consumeLaunchRoute()),
        onRoute: (handler) => events.onLaunchRoute(handler),
      },
    },
    localFiles: createLocalFileService(native, events),
    events,
  }
}
