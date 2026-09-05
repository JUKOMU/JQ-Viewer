import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import {
  createIdempotentListenerHandle,
  type BackendEvents,
  type ListenerHandle,
} from '../BackendEvents'
import { withRuntimeError } from '../errors'

export function createAndroidBackendEvents(native: JmcomicClient): BackendEvents {
  const subscribe = async <T>(
    event: string,
    handler: (event: T) => void,
  ): Promise<ListenerHandle> => {
    const nativeHandle = await withRuntimeError(() =>
      native.addListener(event as never, handler as never),
    )
    return createIdempotentListenerHandle(() => nativeHandle.remove())
  }

  return {
    onImageReady: (handler) => subscribe('imageReady', handler),
    onImageFailed: (handler) => subscribe('imageFailed', handler),
    onDownloadProgress: (handler) => subscribe('downloadProgress', handler),
    onRelocationProgress: (handler) => subscribe('relocationProgress', handler),
    onNetworkProbe: (handler) => subscribe('networkProbe', handler),
    onLaunchRoute: (handler) => subscribe('launchRoute', handler),
    onUpdateProgress: (handler) => subscribe('updateProgress', handler),
    onPdfExportProgress: (handler) => subscribe('pdfExportProgress', handler),
    onVolumeKey: (handler) => subscribe('volumeKey', handler),
  }
}
