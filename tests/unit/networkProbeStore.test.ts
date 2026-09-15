import { afterEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  addNetworkProbeListener: vi.fn(),
  addStateInvalidatedListener: vi.fn(),
  getDomainStates: vi.fn(),
  removeProbe: vi.fn(),
  removeInvalidation: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    addNetworkProbeListener: mocks.addNetworkProbeListener,
    addStateInvalidatedListener: mocks.addStateInvalidatedListener,
    getDomainStates: mocks.getDomainStates,
  },
}))

afterEach(async () => {
  const { disposeNetworkProbeStore } = await import('@/composables/networkProbeStore')
  await disposeNetworkProbeStore()
  vi.clearAllMocks()
})

describe('networkProbeStore', () => {
  test('初始化读取快照、消费事件并在重连后恢复权威状态', async () => {
    let probeListener: ((event: Record<string, unknown>) => void) | undefined
    let invalidationListener: (() => void) | undefined
    mocks.addNetworkProbeListener.mockImplementation(async (listener) => {
      probeListener = listener
      return { remove: mocks.removeProbe }
    })
    mocks.addStateInvalidatedListener.mockImplementation(async (listener) => {
      invalidationListener = listener
      return { remove: mocks.removeInvalidation }
    })
    mocks.getDomainStates
      .mockResolvedValueOnce({
        domains: [{ domain: 'https://first.invalid', reachable: true }],
        alive: 1,
        total: 1,
        allDeadFallback: false,
      })
      .mockResolvedValueOnce({
        domains: [{ domain: 'https://restored.invalid', reachable: false }],
        alive: 0,
        total: 1,
        allDeadFallback: true,
      })
    const { initNetworkProbeStore, useNetworkProbeStore } =
      await import('@/composables/networkProbeStore')

    initNetworkProbeStore()
    const store = useNetworkProbeStore()
    await vi.waitFor(() => expect(store.domains.value[0]?.domain).toBe('https://first.invalid'))

    probeListener?.({
      phase: 'error',
      message: '探活异常 · offline',
      timestamp: 1,
    })
    expect(store.errorMessage.value).toBe('探活异常 · offline')
    expect(store.events.value).toHaveLength(1)

    invalidationListener?.()
    await vi.waitFor(() => expect(store.domains.value[0]?.domain).toBe('https://restored.invalid'))
    expect(store.allDeadFallback.value).toBe(true)
    expect(store.errorMessage.value).toBe('')
  })

  test('快照失败可观察，释放时移除两个共享 SSE 订阅', async () => {
    mocks.addNetworkProbeListener.mockResolvedValue({ remove: mocks.removeProbe })
    mocks.addStateInvalidatedListener.mockResolvedValue({ remove: mocks.removeInvalidation })
    mocks.getDomainStates.mockRejectedValue(new Error('backend unavailable'))
    const { disposeNetworkProbeStore, initNetworkProbeStore, useNetworkProbeStore } =
      await import('@/composables/networkProbeStore')

    expect(() => initNetworkProbeStore()).not.toThrow()
    const store = useNetworkProbeStore()
    await vi.waitFor(() => expect(store.errorMessage.value).toBe('backend unavailable'))

    await disposeNetworkProbeStore()
    expect(mocks.removeProbe).toHaveBeenCalledOnce()
    expect(mocks.removeInvalidation).toHaveBeenCalledOnce()
  })

  test('较新的探活事件不会被启动时的旧快照覆盖', async () => {
    let probeListener: ((event: Record<string, unknown>) => void) | undefined
    let resolveSnapshot: ((value: unknown) => void) | undefined
    mocks.addNetworkProbeListener.mockImplementation(async (listener) => {
      probeListener = listener
      return { remove: mocks.removeProbe }
    })
    mocks.addStateInvalidatedListener.mockResolvedValue(null)
    mocks.getDomainStates.mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveSnapshot = resolve
        }),
    )
    const { initNetworkProbeStore, useNetworkProbeStore } =
      await import('@/composables/networkProbeStore')

    initNetworkProbeStore()
    const store = useNetworkProbeStore()
    await vi.waitFor(() => expect(probeListener).toBeTypeOf('function'))
    probeListener?.({
      phase: 'result',
      message: '探活完成',
      timestamp: 2,
      domains: [{ domain: 'https://new.invalid', reachable: true }],
      alive: 1,
      total: 1,
      allDeadFallback: false,
    })
    resolveSnapshot?.({
      domains: [{ domain: 'https://stale.invalid', reachable: false }],
      alive: 0,
      total: 1,
      allDeadFallback: false,
    })
    await Promise.resolve()

    expect(store.domains.value[0]?.domain).toBe('https://new.invalid')
    expect(store.loading.value).toBe(false)
  })
})
