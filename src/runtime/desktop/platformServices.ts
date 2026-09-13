import packageInfo from '../../../package.json'
import type {
  AppInfo,
  Capability,
  FileService,
  PlatformServices,
  ReaderPlatformServices,
} from '../PlatformServices'
import type { BackendEvents } from '../BackendEvents'
import { asFileRef, asFolderRef } from '../FileReferences'
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

/** 提供明确的当前平台能力状态。 */
export function createPlatformServices(
  events: BackendEvents,
  fetcher: BackendFetch,
): PlatformServices {
  const appInfo: AppInfo = {
    name: 'JQ Viewer',
    version: packageInfo.version,
  }

  const platformServices = {
    app: { getInfo: async () => appInfo },
    notifications: { kind: 'host-managed' },
    files: createFileService(fetcher),
    pdfExportPreferences: createDesktopPdfExportPreferencesStore(fetcher),
    storage: unavailableCapability('当前平台不支持公开下载'),
    reader: createUnavailableReaderServices(),
    updater: unavailableCapability('当前平台不支持应用更新'),
    ocr: unavailableCapability('当前平台不支持 OCR'),
    launchRoutes: unavailableCapability('当前平台不支持启动路由'),
    events,
  }

  // 尚未实现的 PDF 服务保持缺失，避免将能力伪装成可用接口。
  return platformServices as unknown as PlatformServices
}
