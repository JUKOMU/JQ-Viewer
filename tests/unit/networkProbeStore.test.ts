import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  addClientStateListener: vi.fn(),
  addNetworkProbeListener: vi.fn(),
  addStateInvalidatedListener: vi.fn(),
  getClientState: vi.fn(),
  getDomainStates: vi.fn(),
  removeClientState: vi.fn(),
  removeProbe: vi.fn(),
  removeInvalidation: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    addClientStateListener: mocks.addClientStateListener,
    addNetworkProbeListener: mocks.addNetworkProbeListener,
    addStateInvalidatedListener: mocks.addStateInvalidatedListener,
    getClientState: mocks.getClientState,
    getDomainStates: mocks.getDomainStates,
  },
}))

beforeEach(() => {
  mocks.addClientStateListener.mockResolvedValue({ remove: mocks.removeClientState })
  mocks.getClientState.mockResolvedValue({ state: 'ready', timestamp: 1 })
  mocks.removeClientState.mockResolvedValue(undefined)
  mocks.removeProbe.mockResolvedValue(undefined)
  mocks.removeInvalidation.mockResolvedValue(undefined)
})

afterEach(async () => {
  const { disposeNetworkProbeStore } = await import('@/composables/networkProbeStore')
  await disposeNetworkProbeStore()
  vi.useRealTimers()
  vi.resetAllMocks()
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

  test('快照失败可观察，释放时移除全部共享订阅', async () => {
    mocks.addNetworkProbeListener.mockResolvedValue({ remove: mocks.removeProbe })
    mocks.addStateInvalidatedListener.mockResolvedValue({ remove: mocks.removeInvalidation })
    mocks.getDomainStates.mockRejectedValue(new Error('backend unavailable'))
    const { disposeNetworkProbeStore, initNetworkProbeStore, useNetworkProbeStore } =
      await import('@/composables/networkProbeStore')

    expect(() => initNetworkProbeStore()).not.toThrow()
    const store = useNetworkProbeStore()
    await vi.waitFor(() => expect(store.errorMessage.value).toBe('backend unavailable'))

    await disposeNetworkProbeStore()
    expect(mocks.removeClientState).toHaveBeenCalledOnce()
    expect(mocks.removeProbe).toHaveBeenCalledOnce()
    expect(mocks.removeInvalidation).toHaveBeenCalledOnce()
  })

  test('客户端不可用时保留页面可用状态且不读取在线域名', async () => {
    mocks.addNetworkProbeListener.mockResolvedValue({ remove: mocks.removeProbe })
    mocks.addStateInvalidatedListener.mockResolvedValue({ remove: mocks.removeInvalidation })
    mocks.getClientState.mockResolvedValue({
      state: 'unavailable',
      reason: 'initialization_failed',
      timestamp: 1,
    })
    const { initNetworkProbeStore, useNetworkProbeStore } =
      await import('@/composables/networkProbeStore')

    expect(() => initNetworkProbeStore()).not.toThrow()
    const store = useNetworkProbeStore()
    await vi.waitFor(() => expect(store.clientState.value.state).toBe('unavailable'))

    expect(store.loading.value).toBe(false)
    expect(store.domains.value).toEqual([])
    expect(store.errorMessage.value).toBe('')
    expect(mocks.getDomainStates).not.toHaveBeenCalled()
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

  test.each(['probe', 'invalidation'] as const)(
    '%s 监听首次注册失败时清理另一句柄并自动恢复',
    async (failedRegistration) => {
      vi.useFakeTimers()
      let probeListener: ((event: Record<string, unknown>) => void) | undefined
      mocks.addNetworkProbeListener.mockImplementation(async (listener) => {
        if (
          failedRegistration === 'probe' &&
          mocks.addNetworkProbeListener.mock.calls.length === 1
        ) {
          throw new Error('probe listener unavailable')
        }
        probeListener = listener
        return { remove: mocks.removeProbe }
      })
      mocks.addStateInvalidatedListener.mockImplementation(async () => {
        if (
          failedRegistration === 'invalidation' &&
          mocks.addStateInvalidatedListener.mock.calls.length === 1
        ) {
          throw new Error('invalidation listener unavailable')
        }
        return { remove: mocks.removeInvalidation }
      })
      mocks.getDomainStates.mockResolvedValue({
        domains: [{ domain: 'https://snapshot.invalid', reachable: true }],
        alive: 1,
        total: 1,
        allDeadFallback: false,
      })
      const { initNetworkProbeStore, useNetworkProbeStore } =
        await import('@/composables/networkProbeStore')

      initNetworkProbeStore()
      await vi.advanceTimersByTimeAsync(0)
      const store = useNetworkProbeStore()
      expect(store.errorMessage.value).toContain('listener unavailable')
      expect(
        failedRegistration === 'probe' ? mocks.removeInvalidation : mocks.removeProbe,
      ).toHaveBeenCalledOnce()

      await vi.advanceTimersByTimeAsync(1_000)
      expect(mocks.addNetworkProbeListener).toHaveBeenCalledTimes(2)
      expect(mocks.addStateInvalidatedListener).toHaveBeenCalledTimes(2)
      expect(store.errorMessage.value).toBe('')

      probeListener?.({
        phase: 'result',
        message: '探活完成',
        timestamp: 3,
        domains: [{ domain: 'https://recovered.invalid', reachable: true }],
        alive: 1,
        total: 1,
        allDeadFallback: false,
      })
      expect(store.domains.value[0]?.domain).toBe('https://recovered.invalid')
    },
  )

  test('监听持续注册失败时只自动重试两次，之后仍可显式重新初始化', async () => {
    vi.useFakeTimers()
    mocks.addNetworkProbeListener.mockRejectedValue(new Error('listener unavailable'))
    mocks.addStateInvalidatedListener.mockResolvedValue({ remove: mocks.removeInvalidation })
    mocks.getDomainStates.mockResolvedValue({
      domains: [],
      alive: 0,
      total: 0,
      allDeadFallback: false,
    })
    const { initNetworkProbeStore, useNetworkProbeStore } =
      await import('@/composables/networkProbeStore')

    initNetworkProbeStore()
    await vi.advanceTimersByTimeAsync(2_100)

    const store = useNetworkProbeStore()
    expect(mocks.addNetworkProbeListener).toHaveBeenCalledTimes(3)
    expect(mocks.removeInvalidation).toHaveBeenCalledTimes(3)
    expect(vi.getTimerCount()).toBe(0)
    expect(store.errorMessage.value).toBe('listener unavailable')

    initNetworkProbeStore()
    await vi.advanceTimersByTimeAsync(0)
    expect(mocks.addNetworkProbeListener).toHaveBeenCalledTimes(4)
    expect(vi.getTimerCount()).toBe(1)
  })
})
