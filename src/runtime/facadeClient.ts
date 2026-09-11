import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import { asFileRef } from './FileReferences'
import type { FrontendRuntime } from './FrontendRuntime'
import { RuntimeError } from './errors'
import type { Capability } from './PlatformServices'

/** 断言 capability 可用并返回其 API，不可用时抛出带中文名称的 unavailable 错误。 */
function requireCapability<T>(capability: Capability<T>, name: string): T {
  if (!capability.available) throw new RuntimeError('unavailable', capability.reason || `${name}不可用`)
  return capability.api
}

/** 把历史 addListener 的字符串事件名映射为运行时具名订阅方法。 */
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
 * 为既有业务 facade 构造兼容的 JmcomicClient 形状：
 * 在应用边界把 runtime 各端口拼装回原有接口，页面仍沿用旧调用方式，
 * 但不向页面暴露任何 adapter 或 transport。
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
      if (!folder) return { folderRef: '', displayPath: '', provider: 'path', cancelled: true }
      const ref = String(folder.ref)
      return {
        folderRef: ref,
        displayPath: folder.displayPath,
        provider: ref.startsWith('folder:saf:') ? 'saf' : 'path',
        cancelled: false,
      }
    },
    checkFilesExist: async (options: Parameters<JmcomicClient['checkFilesExist']>[0]) => {
      const result = await runtime.services.files.checkFilesExist(options.fileRefs.map(asFileRef))
      return { existingFileRefs: result.existing.map(String) }
    },
    getExternalStoragePath: async () => {
      const folder = await runtime.services.files.getDefaultFolder('download')
      const ref = String(folder.ref)
      return {
        folderRef: ref,
        displayPath: folder.displayPath,
        provider: ref.startsWith('folder:saf:') ? 'saf' : 'path',
      }
    },
    openPdf: (options: Parameters<JmcomicClient['openPdf']>[0]) =>
      runtime.services.files.openFile(asFileRef(options.fileRef)).then(() => ({ success: true })),
    openPdfFolder: (options: Parameters<JmcomicClient['openPdfFolder']>[0]) =>
      runtime.services.files
        .openContainingFolder(asFileRef(options.fileRef))
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

    setReaderScreenOrientation: async (
      options: Parameters<JmcomicClient['setReaderScreenOrientation']>[0],
    ) => requireCapability(reader.orientation, '屏幕方向').set(options.orientation),
    setReaderBrightness: async (options: Parameters<JmcomicClient['setReaderBrightness']>[0]) =>
      requireCapability(reader.brightness, '屏幕亮度').set(options.brightness),
    setReaderKeepScreenOn: async (options: Parameters<JmcomicClient['setReaderKeepScreenOn']>[0]) =>
      requireCapability(reader.keepAwake, '防止熄屏').set(options.enabled),
    setReaderFullscreen: async (options: Parameters<JmcomicClient['setReaderFullscreen']>[0]) =>
      requireCapability(reader.fullscreen, '全屏').set(options.enabled),
    setReaderVolumeNavigation: async (
      options: Parameters<JmcomicClient['setReaderVolumeNavigation']>[0],
    ) => requireCapability(reader.volumeKeys, '音量键翻页').setEnabled(options.enabled),
    setReaderState: async (options: Parameters<JmcomicClient['setReaderState']>[0]) =>
      requireCapability(reader.hostState, '阅读器宿主状态').setState(
        options.isActive,
        options.isVertical,
      ),

    addListener: (event: string, handler: never) =>
      createListener(runtime, event, handler),
  } as unknown as JmcomicClient

  return client
}

/**
 * 通过 Proxy 构造一个惰性 facade client：每次属性访问时都基于当前 runtime
 * 重新生成 client，避免在业务模块顶层把 runtime 一次性固化。
 */
export function createActiveFacadeClient(getRuntime: () => FrontendRuntime): JmcomicClient {
  return new Proxy({} as JmcomicClient, {
    get(_target, property: string | symbol) {
      if (typeof property !== 'string') return undefined
      const client = createFacadeClient(getRuntime())
      return client[property as keyof JmcomicClient]
    },
  })
}
