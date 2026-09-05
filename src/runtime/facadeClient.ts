import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import { asFileRef } from './FileReferences'
import type { FrontendRuntime } from './FrontendRuntime'
import { RuntimeError } from './errors'
import type { Capability } from './PlatformServices'

function requireCapability<T>(capability: Capability<T>, name: string): T {
  if (!capability.available) throw new RuntimeError('unavailable', capability.reason || `${name}不可用`)
  return capability.api
}

function createListener(runtime: FrontendRuntime, event: string, handler: unknown) {
  switch (event) {
    case 'imageReady':
      return runtime.events.onImageReady(handler as Parameters<typeof runtime.events.onImageReady>[0])
    case 'imageFailed':
      return runtime.events.onImageFailed(handler as Parameters<typeof runtime.events.onImageFailed>[0])
    case 'downloadProgress':
      return runtime.events.onDownloadProgress(
        handler as Parameters<typeof runtime.events.onDownloadProgress>[0],
      )
    case 'relocationProgress':
      return runtime.events.onRelocationProgress(
        handler as Parameters<typeof runtime.events.onRelocationProgress>[0],
      )
    case 'networkProbe':
      return runtime.events.onNetworkProbe(handler as Parameters<typeof runtime.events.onNetworkProbe>[0])
    case 'launchRoute':
      return runtime.events.onLaunchRoute(handler as Parameters<typeof runtime.events.onLaunchRoute>[0])
    case 'updateProgress':
      return runtime.events.onUpdateProgress(
        handler as Parameters<typeof runtime.events.onUpdateProgress>[0],
      )
    case 'pdfExportProgress':
      return runtime.events.onPdfExportProgress(
        handler as Parameters<typeof runtime.events.onPdfExportProgress>[0],
      )
    case 'volumeKey':
      return runtime.events.onVolumeKey(handler as Parameters<typeof runtime.events.onVolumeKey>[0])
    default:
      throw new RuntimeError('unavailable', `未知事件：${event}`)
  }
}

/**
 * Internal compatibility shape for the existing business facade. It is
 * assembled from the runtime ports at the application boundary; no adapter
 * or transport is exposed to pages.
 */
export function createFacadeClient(runtime: FrontendRuntime): JmcomicClient {
  const storage = () => requireCapability(runtime.services.storage, '公开下载')
  const updater = () => requireCapability(runtime.services.updater, '应用更新')
  const notifications = () => {
    const policy = runtime.services.notifications
    if (policy.kind === 'runtime-permission') return policy.permissions
    if (policy.kind === 'unavailable') {
      throw new RuntimeError('unavailable', policy.reason || '通知权限不可用')
    }
    throw new RuntimeError('unavailable', '通知由宿主管理')
  }
  const reader = runtime.services.reader
  const ocr = () => requireCapability(runtime.services.ocr, 'OCR')
  const launchRoutes = () => requireCapability(runtime.services.launchRoutes, '启动路由')

  const client = {
    ...runtime.backend,
    ...runtime.services.pdf,

    setDownloadPublic: (options: Parameters<JmcomicClient['setDownloadPublic']>[0]) =>
      storage().setPublic(options.open),
    getDownloadPublic: () => storage().getPublic(),
    requestManageStorage: () => storage().requestStoragePermission(),
    setOcrEnabled: (options: Parameters<JmcomicClient['setOcrEnabled']>[0]) =>
      ocr().setEnabled(options.enabled),
    pickImageAndOcr: () => ocr().pickImageAndOcr(),

    pickFolder: async () => {
      const folder = await runtime.services.files.pickFolder('pdf-root')
      if (!folder) return { path: '', cancelled: true }
      const ref = String(folder.ref)
      return {
        path: folder.displayPath,
        ...(ref.startsWith('content://') ? { treeUri: ref } : {}),
        cancelled: false,
      }
    },
    checkFilesExist: async (options: Parameters<JmcomicClient['checkFilesExist']>[0]) => {
      const result = await runtime.services.files.checkFilesExist(options.paths.map(asFileRef))
      return { existing: result.existing.map(String) }
    },
    getExternalStoragePath: async () => {
      const folder = await runtime.services.files.getDefaultFolder('download')
      return { path: folder.displayPath }
    },
    openPdf: (options: Parameters<JmcomicClient['openPdf']>[0]) =>
      runtime.services.files.openFile(asFileRef(options.filePath)).then(() => ({ success: true })),
    openPdfFolder: (options: Parameters<JmcomicClient['openPdfFolder']>[0]) =>
      runtime.services.files
        .openContainingFolder(asFileRef(options.filePath))
        .then(() => ({ success: true })),

    checkNotificationPermission: () => notifications().check(),
    requestNotificationPermission: () => notifications().request(),
    openNotificationSettings: () => notifications().openSettings(),

    checkUpdate: () => updater().check(),
    startUpdate: () => updater().start(),
    cancelUpdate: () => updater().cancel(),
    getUpdateState: () => updater().getState(),
    installUpdate: () => updater().install(),
    requestInstallPermission: async () => {
      const state = await updater().getState()
      if (!state.requiredUserAction) return { requested: false }
      await updater().performUserAction(state.requiredUserAction)
      return { requested: true }
    },
    consumeLaunchRoute: () => launchRoutes().consume(),

    setReaderScreenOrientation: (options: Parameters<JmcomicClient['setReaderScreenOrientation']>[0]) =>
      requireCapability(reader.orientation, '屏幕方向').set(options.orientation),
    setReaderBrightness: (options: Parameters<JmcomicClient['setReaderBrightness']>[0]) =>
      requireCapability(reader.brightness, '屏幕亮度').set(options.brightness),
    setReaderKeepScreenOn: (options: Parameters<JmcomicClient['setReaderKeepScreenOn']>[0]) =>
      requireCapability(reader.keepAwake, '防止熄屏').set(options.enabled),
    setReaderFullscreen: (options: Parameters<JmcomicClient['setReaderFullscreen']>[0]) =>
      requireCapability(reader.fullscreen, '全屏').set(options.enabled),
    setReaderVolumeNavigation: (
      options: Parameters<JmcomicClient['setReaderVolumeNavigation']>[0],
    ) => requireCapability(reader.volumeKeys, '音量键翻页').setEnabled(options.enabled),
    setReaderState: (options: Parameters<JmcomicClient['setReaderState']>[0]) =>
      requireCapability(reader.hostState, '阅读器宿主状态').setState(
        options.isActive,
        options.isVertical,
      ),

    addListener: (event: string, handler: never) =>
      createListener(runtime, event, handler),
  } as unknown as JmcomicClient

  return client
}

export function createActiveFacadeClient(getRuntime: () => FrontendRuntime): JmcomicClient {
  return new Proxy({} as JmcomicClient, {
    get(_target, property: string | symbol) {
      if (typeof property !== 'string') return undefined
      const client = createFacadeClient(getRuntime())
      return client[property as keyof JmcomicClient]
    },
  })
}
