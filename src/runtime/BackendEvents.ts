import type {
  DownloadProgressEvent,
  NetworkProbeEvent,
  PdfExportProgressEvent,
  RelocationProgress,
  UpdateProgressEvent,
} from '@/services/JmcomicTypes'
import type { ImageFailedEvent, ImageReadyEvent } from '@/services/jmcomic/JmcomicClient'

export interface ListenerHandle {
  remove(): Promise<void>
}

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
