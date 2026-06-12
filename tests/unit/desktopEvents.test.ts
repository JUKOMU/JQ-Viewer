import {afterEach, beforeEach, describe, expect, test, vi} from 'vitest'

type Listener = (event: MessageEvent) => void

class FakeEventSource {
  static instances: FakeEventSource[] = []

  url: string
  onerror: (() => void) | null = null
  closed = false
  private readonly listeners = new Map<string, Listener[]>()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(eventName: string, listener: Listener) {
    const listeners = this.listeners.get(eventName) ?? []
    listeners.push(listener)
    this.listeners.set(eventName, listeners)
  }

  removeEventListener(eventName: string, listener: Listener) {
    const listeners = this.listeners.get(eventName) ?? []
    this.listeners.set(eventName, listeners.filter((item) => item !== listener))
  }

  close() {
    this.closed = true
  }

  emit(eventName: string, data: unknown) {
    const event = {data: JSON.stringify(data)} as MessageEvent
    for (const listener of this.listeners.get(eventName) ?? []) {
      listener(event)
    }
  }

  fail() {
    this.onerror?.()
  }
}

describe('desktop event capabilities', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.useFakeTimers()
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    localStorage.clear()
    FakeEventSource.instances = []
    vi.stubEnv('VITE_JQ_DESKTOP_TOKEN', 'event-token')
    vi.stubGlobal('EventSource', FakeEventSource)
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  test('reconnects desktop download progress events and cleans up on remove', async () => {
    const {desktopCapabilities} = await import('@/platform/desktopCapabilities')
    const handler = vi.fn()

    const handle = await desktopCapabilities.events.addDownloadProgressListener(handler)

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0].url).toBe('/events?token=event-token')

    FakeEventSource.instances[0].emit('downloadProgress', {
      taskId: 'pdf_album_chapter',
      albumId: 'album',
      chapterId: 'chapter',
      downloadedPages: 1,
      totalPages: 3,
      status: 'downloading',
      speed: 0,
    })
    expect(handler).toHaveBeenCalledWith(expect.objectContaining({
      taskId: 'pdf_album_chapter',
      status: 'downloading',
    }))

    FakeEventSource.instances[0].fail()
    expect(FakeEventSource.instances[0].closed).toBe(true)

    await vi.advanceTimersByTimeAsync(2999)
    expect(FakeEventSource.instances).toHaveLength(1)

    await vi.advanceTimersByTimeAsync(1)
    expect(FakeEventSource.instances).toHaveLength(2)
    expect(FakeEventSource.instances[1].url).toBe('/events?token=event-token')

    await handle.remove()
    FakeEventSource.instances[1].fail()
    await vi.advanceTimersByTimeAsync(3000)
    expect(FakeEventSource.instances).toHaveLength(2)
  })

  test('subscribes to desktop network probe events', async () => {
    const {desktopCapabilities} = await import('@/platform/desktopCapabilities')
    const handler = vi.fn()

    const handle = await desktopCapabilities.events.addNetworkProbeListener(handler)

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0].url).toBe('/events?token=event-token')

    FakeEventSource.instances[0].emit('networkProbe', {
      phase: 'result',
      message: '探活完成 · 1/2 可达',
      timestamp: 1710000000000,
      domains: [
        {domain: 'example.test', reachable: true},
        {domain: 'dead.test', reachable: false},
      ],
      alive: 1,
      total: 2,
      allDeadFallback: false,
    })
    expect(handler).toHaveBeenCalledWith(expect.objectContaining({
      phase: 'result',
      alive: 1,
      total: 2,
    }))

    await handle.remove()
    expect(FakeEventSource.instances[0].closed).toBe(true)
  })
})
