import type { BackendEvents, ListenerHandle } from '../BackendEvents'
import { RuntimeError } from '../errors'

function unavailable(event: string): Promise<never> {
  return Promise.reject(new RuntimeError('unavailable', `Desktop event is unavailable: ${event}`))
}

/** Desktop 当前不提供 SSE 事件传输。 */
export function createDesktopBackendEvents(): BackendEvents {
  return {
    onImageReady: () => unavailable('imageReady'),
    onImageFailed: () => unavailable('imageFailed'),
    onDownloadProgress: () => unavailable('downloadProgress'),
    onRelocationProgress: () => unavailable('relocationProgress'),
    onNetworkProbe: () => unavailable('networkProbe'),
    onLaunchRoute: () => unavailable('launchRoute'),
    onUpdateProgress: () => unavailable('updateProgress'),
    onPdfExportProgress: () => unavailable('pdfExportProgress'),
    onVolumeKey: () => unavailable('volumeKey'),
  } satisfies BackendEvents
}

export type { ListenerHandle }
