import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import {
  createIdempotentListenerHandle,
  type BackendEvents,
  type ListenerHandle,
} from '../BackendEvents'
import { withRuntimeError } from '../errors'

/**
 * 构造 Android 的 BackendEvents 实现，把 Capacitor 的 addListener 事件
 * 映射为具名订阅方法，并将原生句柄包装成幂等的 ListenerHandle。
 */
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
