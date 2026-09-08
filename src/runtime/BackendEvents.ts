import type {
  DownloadProgressEvent,
  NetworkProbeEvent,
  PdfExportProgressEvent,
  RelocationProgress,
  UpdateProgressEvent,
} from '@/services/JmcomicTypes'
import type { ImageFailedEvent, ImageReadyEvent } from '@/services/jmcomic/JmcomicClient'

/** 平台无关的事件订阅句柄。页面用它移除监听，不依赖 Capacitor 的 PluginListenerHandle。 */
export interface ListenerHandle {
  remove(): Promise<void>
}

/**
 * 后端主动推送的具名 JSON 事件集。
 * 每个方法只负责订阅对应事件并返回可移除的句柄，不向下游暴露具体 transport。
 */
export interface BackendEvents {
  onImageReady(handler: (event: ImageReadyEvent) => void): Promise<ListenerHandle>
  onImageFailed(handler: (event: ImageFailedEvent) => void): Promise<ListenerHandle>
  onDownloadProgress(handler: (event: DownloadProgressEvent) => void): Promise<ListenerHandle>
  onRelocationProgress(handler: (event: RelocationProgress) => void): Promise<ListenerHandle>
  onNetworkProbe(handler: (event: NetworkProbeEvent) => void): Promise<ListenerHandle>
  onLaunchRoute(handler: (event: { route: string }) => void): Promise<ListenerHandle>
  onUpdateProgress(handler: (event: UpdateProgressEvent) => void): Promise<ListenerHandle>
  onPdfExportProgress(handler: (event: PdfExportProgressEvent) => void): Promise<ListenerHandle>
  onVolumeKey(handler: (event: { direction: 'up' | 'down' }) => void): Promise<ListenerHandle>
}

/**
 * 包装一个底层移除函数，生成幂等的 ListenerHandle：
 * 重复调用 remove 只会执行一次实际移除，并始终返回同一次移除的 Promise。
 */
export function createIdempotentListenerHandle(remove: () => Promise<void>): ListenerHandle {
  let removed = false
  let removal: Promise<void> | null = null
  return {
    remove() {
      if (removed) return removal ?? Promise.resolve()
      removed = true
      removal = remove()
      return removal
    },
  }
}
