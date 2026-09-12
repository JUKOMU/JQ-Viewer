import type { BackendEvents, ListenerHandle } from '../BackendEvents'
import { createIdempotentListenerHandle } from '../BackendEvents'
import { RuntimeError } from '../errors'

type EventHandler = (event: unknown) => void

/** 通过一个共享 SSE 连接订阅具名 JSON 事件。 */
export function createBackendEvents(): BackendEvents {
  const handlers = new Map<string, Set<EventHandler>>()
  const sourceListeners = new Map<string, EventListener>()
  let source: EventSource | null = null

  function ensureSource(): EventSource {
    if (source) return source
    if (typeof globalThis.EventSource === 'undefined') {
      throw new RuntimeError('unavailable', '事件流不可用')
    }
    source = new globalThis.EventSource('/events')
    return source
  }

  function dispatch(name: string, event: Event) {
    const message = event as MessageEvent<string>
    let payload: unknown
    try {
      payload = JSON.parse(message.data)
    } catch {
      return
    }
    for (const handler of handlers.get(name) ?? []) {
      try {
        handler(payload)
      } catch {
        // 单个订阅者失败不能中断同一事件的其他订阅者。
      }
    }
  }

  function closeSourceIfUnused() {
    if (handlers.size !== 0) return
    source?.close()
    source = null
    sourceListeners.clear()
  }

  async function subscribe<T>(name: string, handler: (event: T) => void): Promise<ListenerHandle> {
    const eventSource = ensureSource()
    let eventHandlers = handlers.get(name)
    if (!eventHandlers) {
      eventHandlers = new Set()
      handlers.set(name, eventHandlers)
    }
    const callback = handler as EventHandler
    eventHandlers.add(callback)

    if (!sourceListeners.has(name)) {
      const listener: EventListener = (event) => dispatch(name, event)
      sourceListeners.set(name, listener)
      eventSource.addEventListener(name, listener)
    }

    return createIdempotentListenerHandle(async () => {
      const currentHandlers = handlers.get(name)
      currentHandlers?.delete(callback)
      if (currentHandlers?.size === 0) {
        handlers.delete(name)
        const listener = sourceListeners.get(name)
        if (listener) eventSource.removeEventListener(name, listener)
        sourceListeners.delete(name)
      }
      closeSourceIfUnused()
    })
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

export type { ListenerHandle }
