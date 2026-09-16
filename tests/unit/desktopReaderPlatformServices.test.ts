import { describe, expect, test, vi } from 'vitest'
import type { RuntimeError } from '@/runtime/errors'
import type { BackendEvents } from '@/runtime/BackendEvents'
import {
  createDesktopReaderServices,
  createPlatformServices,
} from '@/runtime/desktop/platformServices'

class FakeDocument extends EventTarget {
  visibilityState: DocumentVisibilityState = 'visible'
  fullscreenEnabled = true
  fullscreenElement: Element | null = null
  readonly documentElement = {
    dataset: {} as DOMStringMap,
    requestFullscreen: undefined as undefined | ((options?: FullscreenOptions) => Promise<void>),
  }
  exitFullscreen: undefined | (() => Promise<void>)
}

class FakeWakeLockSentinel extends EventTarget {
  readonly release = vi.fn(async () => undefined)
}

const flushAsyncEvents = async () => {
  await Promise.resolve()
  await Promise.resolve()
}

const pageTransitionEvent = (type: 'pagehide' | 'pageshow', persisted: boolean) => {
  const event = new Event(type)
  Object.defineProperty(event, 'persisted', { value: persisted })
  return event
}

describe('Desktop 阅读器宿主能力', () => {
  test('没有可靠 Desktop 语义或浏览器 API 时明确标记 unavailable', () => {
    const document = new FakeDocument()
    document.fullscreenEnabled = false
    document.documentElement.requestFullscreen = vi.fn(async () => undefined)
    document.exitFullscreen = vi.fn(async () => undefined)
    const services = createDesktopReaderServices({
      document: document as unknown as Document,
      navigator: {},
      window: new EventTarget() as unknown as Window,
    })

    expect(services.orientation).toEqual({
      available: false,
      reason: '桌面显示器不支持应用级屏幕方向控制',
    })
    expect(services.brightness).toEqual({
      available: false,
      reason: '桌面浏览器无法调整系统屏幕亮度',
    })
    expect(services.volumeKeys).toEqual({
      available: false,
      reason: '桌面浏览器无法可靠拦截系统音量键',
    })
    expect(services.keepAwake.available).toBe(false)
    expect(services.fullscreen.available).toBe(false)
    expect(services.hostState.available).toBe(true)
  })

  test('阅读器激活后获取 Wake Lock，隐藏、恢复和离页时正确释放或重获', async () => {
    const document = new FakeDocument()
    const firstLock = new FakeWakeLockSentinel()
    const secondLock = new FakeWakeLockSentinel()
    const request = vi.fn().mockResolvedValueOnce(firstLock).mockResolvedValueOnce(secondLock)
    const services = createDesktopReaderServices({
      document: document as unknown as Document,
      navigator: { wakeLock: { request } },
      window: new EventTarget() as unknown as Window,
    })
    if (!services.keepAwake.available || !services.hostState.available) {
      throw new Error('expected reader host capabilities')
    }

    await services.keepAwake.api.set(true)
    expect(request).not.toHaveBeenCalled()

    await services.hostState.api.setState(true, false)
    expect(request).toHaveBeenCalledWith('screen')
    expect(document.documentElement.dataset.jqReaderActive).toBe('true')
    expect(document.documentElement.dataset.jqReaderMode).toBe('horizontal')

    document.visibilityState = 'hidden'
    document.dispatchEvent(new Event('visibilitychange'))
    await flushAsyncEvents()
    expect(firstLock.release).toHaveBeenCalledTimes(1)

    document.visibilityState = 'visible'
    document.dispatchEvent(new Event('visibilitychange'))
    await flushAsyncEvents()
    expect(request).toHaveBeenCalledTimes(2)

    await services.hostState.api.setState(false, false)
    expect(secondLock.release).toHaveBeenCalledTimes(1)
    expect(document.documentElement.dataset.jqReaderActive).toBeUndefined()
    expect(document.documentElement.dataset.jqReaderMode).toBeUndefined()
  })

  test('全屏使用浏览器 API，并把用户手势限制映射为 permission-denied', async () => {
    const document = new FakeDocument()
    const root = document.documentElement as unknown as Element
    document.documentElement.requestFullscreen = vi.fn(async () => {
      document.fullscreenElement = root
    })
    document.exitFullscreen = vi.fn(async () => {
      document.fullscreenElement = null
    })
    const services = createDesktopReaderServices({
      document: document as unknown as Document,
      navigator: {},
      window: new EventTarget() as unknown as Window,
    })
    if (!services.fullscreen.available || !services.hostState.available) {
      throw new Error('expected fullscreen capability')
    }

    await services.hostState.api.setState(true, true)
    await expect(services.fullscreen.api.set(true)).resolves.toEqual({ success: true })
    expect(document.documentElement.requestFullscreen).toHaveBeenCalledWith({
      navigationUI: 'hide',
    })
    await services.fullscreen.api.set(false)
    expect(document.exitFullscreen).toHaveBeenCalledTimes(1)

    document.documentElement.requestFullscreen = vi.fn(async () => {
      throw new DOMException('需要用户操作', 'NotAllowedError')
    })
    await expect(services.fullscreen.api.set(true)).rejects.toMatchObject<RuntimeError>({
      code: 'permission-denied',
      message: '需要用户操作',
    })
  })

  test('窗口 pagehide 恢复宿主状态并拒绝后续操作', async () => {
    const document = new FakeDocument()
    const window = new EventTarget()
    const wakeLock = new FakeWakeLockSentinel()
    document.documentElement.requestFullscreen = vi.fn(async () => {
      document.fullscreenElement = document.documentElement as unknown as Element
    })
    document.exitFullscreen = vi.fn(async () => {
      document.fullscreenElement = null
    })
    const services = createDesktopReaderServices({
      document: document as unknown as Document,
      navigator: { wakeLock: { request: vi.fn().mockResolvedValue(wakeLock) } },
      window: window as unknown as Window,
    })
    if (
      !services.keepAwake.available ||
      !services.fullscreen.available ||
      !services.hostState.available
    ) {
      throw new Error('expected reader host capabilities')
    }

    await services.hostState.api.setState(true, true)
    await services.keepAwake.api.set(true)
    await services.fullscreen.api.set(true)
    window.dispatchEvent(new Event('pagehide'))
    await flushAsyncEvents()

    expect(wakeLock.release).toHaveBeenCalledTimes(1)
    expect(document.exitFullscreen).toHaveBeenCalledTimes(1)
    expect(document.documentElement.dataset.jqReaderActive).toBeUndefined()
    await expect(services.hostState.api.setState(true, true)).rejects.toMatchObject({
      code: 'unavailable',
    })
  })

  test('进入 back/forward cache 时释放临时资源并在恢复后继续可用', async () => {
    const document = new FakeDocument()
    const window = new EventTarget()
    const firstLock = new FakeWakeLockSentinel()
    const secondLock = new FakeWakeLockSentinel()
    const request = vi.fn().mockResolvedValueOnce(firstLock).mockResolvedValueOnce(secondLock)
    document.documentElement.requestFullscreen = vi.fn(async () => {
      document.fullscreenElement = document.documentElement as unknown as Element
    })
    document.exitFullscreen = vi.fn(async () => {
      document.fullscreenElement = null
    })
    const services = createDesktopReaderServices({
      document: document as unknown as Document,
      navigator: { wakeLock: { request } },
      window: window as unknown as Window,
    })
    if (
      !services.keepAwake.available ||
      !services.fullscreen.available ||
      !services.hostState.available
    ) {
      throw new Error('expected reader host capabilities')
    }

    await services.hostState.api.setState(true, true)
    await services.keepAwake.api.set(true)
    await services.fullscreen.api.set(true)
    window.dispatchEvent(pageTransitionEvent('pagehide', true))
    await flushAsyncEvents()

    expect(firstLock.release).toHaveBeenCalledTimes(1)
    expect(document.exitFullscreen).toHaveBeenCalledTimes(1)
    expect(document.documentElement.dataset.jqReaderActive).toBe('true')
    await expect(services.hostState.api.setState(true, false)).resolves.toEqual({ success: true })
    expect(request).toHaveBeenCalledTimes(1)

    window.dispatchEvent(pageTransitionEvent('pageshow', true))
    await flushAsyncEvents()

    expect(request).toHaveBeenCalledTimes(2)
    expect(document.documentElement.dataset.jqReaderMode).toBe('horizontal')
    await services.hostState.api.setState(false, false)
    expect(secondLock.release).toHaveBeenCalledTimes(1)
  })
})

describe('Desktop 启动路由能力', () => {
  test('通过后端一次性消费路由并复用共享 launchRoute 事件', async () => {
    const remove = vi.fn(async () => undefined)
    const onLaunchRoute = vi.fn(async () => ({ remove }))
    const events = { onLaunchRoute } as unknown as BackendEvents
    const fetcher = vi.fn(
      async () =>
        new Response(JSON.stringify({ route: '/download' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    )
    const services = createPlatformServices(events, fetcher)
    if (!services.launchRoutes.available) throw new Error('expected launch route capability')

    await expect(services.launchRoutes.api.consume()).resolves.toEqual({ route: '/download' })
    const handler = vi.fn()
    const handle = await services.launchRoutes.api.onRoute(handler)
    await handle.remove()

    expect(fetcher).toHaveBeenCalledWith('/api/consumeLaunchRoute', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
    expect(onLaunchRoute).toHaveBeenCalledWith(handler)
    expect(remove).toHaveBeenCalledOnce()
  })
})

describe('Desktop 诊断能力', () => {
  test('通过统一后端方法读取诊断快照', async () => {
    const snapshot = {
      generatedAt: 1,
      paths: [],
      tasks: [],
      clearableResources: [],
    }
    const fetcher = vi.fn(
      async () =>
        new Response(JSON.stringify(snapshot), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
    )
    const services = createPlatformServices({} as BackendEvents, fetcher)
    if (!services.diagnostics.available) throw new Error('expected diagnostics capability')

    await expect(services.diagnostics.api.getSnapshot()).resolves.toEqual(snapshot)
    expect(fetcher).toHaveBeenCalledWith('/api/getDiagnostics', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
  })
})
