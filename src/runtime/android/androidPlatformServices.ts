import { App } from '@capacitor/app'
import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import type { RelocationProgress } from '@/services/JmcomicTypes'
import type { BackendEvents } from '../BackendEvents'
import { asFileRef, asFolderRef, type FileRef, type FolderRef } from '../FileReferences'
import { withRuntimeError } from '../errors'
import {
  PDF_PLATFORM_METHODS,
  type AppInfo,
  type FileService,
  type NotificationPermissionPort,
  type PdfService,
  type PlatformServices,
  type PublicDownloadService,
} from '../PlatformServices'
import { createAndroidUpdater } from './androidUpdater'

function ensureSuccess(result: { success: boolean }, message: string): void {
  if (!result.success) throw new Error(message)
}

function folderRefValue(path: string, treeUri?: string): FolderRef {
  return asFolderRef(treeUri || path)
}

function createFileService(native: JmcomicClient): FileService {
  return {
    pickFolder: async () => {
      const result = await withRuntimeError(() => native.pickFolder())
      if (result.cancelled || (!result.path && !result.treeUri)) return null
      return {
        ref: folderRefValue(result.path, result.treeUri),
        displayPath: result.path || result.treeUri || '',
      }
    },
    getDefaultFolder: async () => {
      const result = await withRuntimeError(() => native.getExternalStoragePath())
      return { ref: asFolderRef(result.path), displayPath: result.path }
    },
    checkFilesExist: async (files: FileRef[]) => {
      const result = await withRuntimeError(() =>
        native.checkFilesExist({ paths: files.map((file) => String(file)) }),
      )
      return { existing: result.existing.map(asFileRef) }
    },
    openFile: async (file) => {
      const result = await withRuntimeError(() => native.openPdf({ filePath: String(file) }))
      ensureSuccess(result, '无法打开 PDF 文件')
    },
    openContainingFolder: async (file) => {
      const result = await withRuntimeError(() => native.openPdfFolder({ filePath: String(file) }))
      ensureSuccess(result, '无法打开 PDF 所在文件夹')
    },
    scanPdfFiles: async (folder) => {
      const value = String(folder)
      const result = await withRuntimeError(() =>
        native.scanPdfFiles({
          path: value.startsWith('content://') ? '' : value,
          ...(value.startsWith('content://') ? { treeUri: value } : {}),
        }),
      )
      return {
        files: result.files.map((file) => ({
          ref: asFileRef(file.filePath),
          fileName: file.fileName,
          displayPath: file.filePath,
        })),
      }
    },
  }
}

function createNotificationPort(native: JmcomicClient): NotificationPermissionPort {
  return {
    check: () => withRuntimeError(() => native.checkNotificationPermission()),
    request: () => withRuntimeError(() => native.requestNotificationPermission()),
    openSettings: () => withRuntimeError(() => native.openNotificationSettings()),
  }
}

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

function createPdfService(native: JmcomicClient): PdfService {
  const service = {} as PdfService
  for (const method of PDF_PLATFORM_METHODS) {
    ;(service as Record<string, unknown>)[method] = native[method].bind(native)
  }
  return service
}

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
    pdf: createPdfService(native),
    events,
  }
}
