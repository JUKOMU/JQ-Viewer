import type { BackendEvents, ListenerHandle } from '../BackendEvents'
import { createIdempotentListenerHandle } from '../BackendEvents'
import { RuntimeError } from '../errors'

type EventHandler = (event: unknown) => void
const SOURCE_OPEN_TIMEOUT_MS = 5_000

/** 通过一个共享 SSE 连接订阅具名 JSON 事件。 */
export function createBackendEvents(): BackendEvents {
  const handlers = new Map<string, Set<EventHandler>>()
  const invalidationHandlers = new Set<() => void>()
  const sourceListeners = new Map<string, EventListener>()
  let source: EventSource | null = null
  let hasOpened = false
  let sourceReady: Promise<void> | null = null
  let resolveSourceReady: (() => void) | null = null
  let rejectSourceReady: ((error: RuntimeError) => void) | null = null
  let sourceReadyTimer: ReturnType<typeof setTimeout> | null = null

  function ensureSource(): EventSource {
    if (source) return source
    if (typeof globalThis.EventSource === 'undefined') {
      throw new RuntimeError('unavailable', '事件流不可用')
    }
    const ready = new Promise<void>((resolve, reject) => {
      resolveSourceReady = resolve
      rejectSourceReady = reject
    })
    sourceReady = ready
    void ready.catch(() => undefined)
    source = new globalThis.EventSource('/events')
    source.addEventListener('open', handleOpen)
    sourceReadyTimer = setTimeout(() => {
      if (hasOpened) return
      const error = new RuntimeError('unavailable', '事件流连接超时')
      rejectSourceReady?.(error)
      resolveSourceReady = null
      rejectSourceReady = null
      sourceReady = null
      sourceReadyTimer = null
      source?.removeEventListener('open', handleOpen)
      source?.close()
      source = null
      sourceListeners.clear()
      handlers.clear()
      invalidationHandlers.clear()
    }, SOURCE_OPEN_TIMEOUT_MS)
    return source
  }

  function handleOpen() {
    if (!hasOpened) {
      hasOpened = true
      if (sourceReadyTimer) clearTimeout(sourceReadyTimer)
      sourceReadyTimer = null
      resolveSourceReady?.()
      resolveSourceReady = null
      rejectSourceReady = null
      return
    }
    for (const handler of invalidationHandlers) {
      try {
        handler()
      } catch {
        // 单个订阅者失败不能中断其他页面的重连恢复。
      }
    }
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
    if (handlers.size !== 0 || invalidationHandlers.size !== 0) return
    source?.removeEventListener('open', handleOpen)
    source?.close()
    source = null
    hasOpened = false
    if (sourceReadyTimer) clearTimeout(sourceReadyTimer)
    sourceReadyTimer = null
    sourceReady = null
    resolveSourceReady = null
    rejectSourceReady = null
    sourceListeners.clear()
  }

  function waitForSourceOpen() {
    return hasOpened ? Promise.resolve() : sourceReady
  }

  async function subscribeInvalidation(handler: () => void): Promise<ListenerHandle> {
    ensureSource()
    invalidationHandlers.add(handler)
    await waitForSourceOpen()
    return createIdempotentListenerHandle(async () => {
      invalidationHandlers.delete(handler)
      closeSourceIfUnused()
    })
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

    await waitForSourceOpen()
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
    onStateInvalidated: subscribeInvalidation,
    onImageReady: (handler) => subscribe('imageReady', handler),
    onImageFailed: (handler) => subscribe('imageFailed', handler),
    onDownloadProgress: (handler) => subscribe('downloadProgress', handler),
    onRelocationProgress: (handler) => subscribe('relocationProgress', handler),
    onNetworkProbe: (handler) => subscribe('networkProbe', handler),
    onClientStateChanged: (handler) => subscribe('clientStateChanged', handler),
    onLaunchRoute: (handler) => subscribe('launchRoute', handler),
    onUpdateProgress: (handler) => subscribe('updateProgress', handler),
    onExportProgress: (handler) => subscribe('exportProgress', handler),
    onVolumeKey: (handler) => subscribe('volumeKey', handler),
  }
}

export type { ListenerHandle }
