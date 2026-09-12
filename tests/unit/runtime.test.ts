import { afterEach, describe, expect, test, vi } from 'vitest'
import { createBackendClient } from '@/runtime/desktop/backendClient'
import { createRuntime } from '@/runtime/desktop/createRuntime'
import { RuntimeError } from '@/runtime/errors'

function response(payload: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: async () => payload,
  } as Response
}

class FakeEventSource {
  static instances: FakeEventSource[] = []
  readonly url: string
  readonly listeners = new Map<string, Set<EventListener>>()
  readonly close = vi.fn()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(name: string, listener: EventListener) {
    const listeners = this.listeners.get(name) ?? new Set<EventListener>()
    listeners.add(listener)
    this.listeners.set(name, listeners)
  }

  removeEventListener(name: string, listener: EventListener) {
    this.listeners.get(name)?.delete(listener)
  }

  emit(name: string, data: unknown) {
    const event = { data: JSON.stringify(data) } as MessageEvent<string>
    for (const listener of this.listeners.get(name) ?? []) listener(event)
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  FakeEventSource.instances = []
})

describe('runtime', () => {
  test('绑定在线核心方法并发送页面使用的 JSON 参数', async () => {
    const fetcher = vi.fn().mockImplementation((_input, init: RequestInit) => {
      const method = String(init.body).includes('keyword') ? 'search' : 'getInitStatus'
      return Promise.resolve(response(method === 'search' ? { content: [] } : { complete: true }))
    })
    const backend = createBackendClient(fetcher)

    await expect(backend.getInitStatus()).resolves.toEqual({ complete: true })
    await backend.search({
      query: { keyword: 'keyword', orderBy: 'mr', time: 'a', searchMainTag: 0, page: 2 },
    })
    expect(Object.keys(backend)).toEqual([
      'search',
      'categories',
      'getAlbum',
      'getPhoto',
      'getComments',
      'preloadImages',
      'retryImage',
      'login',
      'logout',
      'checkLoginState',
      'getUserProfile',
      'getAllSettings',
      'setPreloadConcurrency',
      'setDownloadConcurrency',
      'setReaderPreloadPages',
      'setReaderDisplayMode',
      'setReaderAutoShowToolbarAtEnd',
      'getBrowseHistory',
      'getBrowseHistoryOverview',
      'recordBrowse',
      'clearBrowseHistory',
      'deleteBrowseItem',
      'getInitStatus',
    ])
    expect(fetcher).toHaveBeenCalledWith('/api/getInitStatus', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
    expect(fetcher).toHaveBeenLastCalledWith('/api/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        keyword: 'keyword',
        orderBy: 'mr',
        time: 'a',
        searchMainTag: 0,
        page: 2,
      }),
    })
  })

  test('归一化传输和结构化后端错误，不添加回退方法', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('connection refused'))
    const backend = createBackendClient(fetcher)

    await expect(backend.getInitStatus()).rejects.toMatchObject({
      code: 'network',
      message: 'connection refused',
    })

    const malformed = createBackendClient(vi.fn().mockResolvedValue(response({ complete: 'yes' })))
    await expect(malformed.getInitStatus()).rejects.toMatchObject({
      code: 'internal',
    })
    expect((backend as unknown as { downloadChapter?: unknown }).downloadChapter).toBeUndefined()
  })

  test('构造指定平台的同源运行时并保留未提供的平台能力状态', () => {
    const runtime = createRuntime(
      'windows',
      vi.fn().mockResolvedValue(response({ complete: true })),
    )

    expect(runtime.platform).toBe('windows')
    expect(runtime.resources.imageUrl({ photoId: 'chapter/1', sortOrder: 2, type: 'thumb' })).toBe(
      '/thumb/chapter%2F1/2',
    )
    expect(runtime.resources.pdfDocumentUrl('file:path:/books/book.pdf' as never)).toBe(
      '/pdf/file%3Apath%3A%2Fbooks%2Fbook.pdf',
    )
    expect(runtime.services.storage.available).toBe(false)
    expect(runtime.services.updater.available).toBe(false)
    expect(runtime.services.reader.fullscreen.available).toBe(false)
    expect(runtime.services.notifications.kind).toBe('host-managed')
    expect('files' in runtime.services).toBe(false)
    expect('pdf' in runtime.services).toBe(false)
    expect(runtime.events.onNetworkProbe).toBeTypeOf('function')
  })

  test('共享一个 SSE 连接并将具名 JSON 事件分发到订阅者', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const events = createRuntime('linux').events
    const first = vi.fn(() => {
      throw new Error('listener failed')
    })
    const second = vi.fn()
    const firstHandle = await events.onImageReady(first)
    const secondHandle = await events.onImageReady(second)

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0].url).toBe('/events')
    FakeEventSource.instances[0].emit('imageReady', { photoId: 'p1', sortOrder: 2, type: 'image' })
    expect(first).toHaveBeenCalledWith({ photoId: 'p1', sortOrder: 2, type: 'image' })
    expect(second).toHaveBeenCalledWith({ photoId: 'p1', sortOrder: 2, type: 'image' })

    await firstHandle.remove()
    await firstHandle.remove()
    await secondHandle.remove()
    expect(FakeEventSource.instances[0].close).toHaveBeenCalledOnce()
  })

  test('preserves structured backend error responses', async () => {
    const backend = createBackendClient(
      vi
        .fn()
        .mockResolvedValue(response({ code: 'conflict', message: 'stale request' }, false, 409)),
    )

    await expect(backend.getInitStatus()).rejects.toEqual(
      new RuntimeError('conflict', 'stale request'),
    )
  })
})
