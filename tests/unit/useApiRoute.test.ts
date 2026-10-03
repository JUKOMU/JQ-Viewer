import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  applyApiRoute: vi.fn(),
  setApiRoutePreference: vi.fn(),
  getUsedDomain: vi.fn(),
  getDomainStates: vi.fn(),
  reauthenticate: vi.fn(),
  mode: 'auto' as 'auto' | 'manual',
  domain: '',
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    applyApiRoute: mocks.applyApiRoute,
    setApiRoutePreference: mocks.setApiRoutePreference,
    getUsedDomain: mocks.getUsedDomain,
    getDomainStates: mocks.getDomainStates,
  },
}))

vi.mock('@/services/SettingsService', () => ({
  SettingsStore: {
    getApiRouteMode: () => mocks.mode,
    getApiRouteDomain: () => mocks.domain,
    setApiRoute: (mode: 'auto' | 'manual', domain: string) => {
      mocks.mode = mode
      mocks.domain = domain
    },
  },
}))

vi.mock('@/composables/useAuth', () => ({
  useAuth: () => ({ isLoggedIn: { value: true }, reauthenticate: mocks.reauthenticate }),
}))

describe('useApiRoute', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.clearAllMocks()
    mocks.mode = 'auto'
    mocks.domain = ''
    mocks.applyApiRoute.mockResolvedValue({ domain: 'api.example' })
    mocks.getUsedDomain.mockResolvedValue({ domain: 'api.example' })
    mocks.getDomainStates.mockResolvedValue({ domains: [], alive: 0, total: 0, allDeadFallback: false })
    mocks.setApiRoutePreference.mockResolvedValue({ success: true })
    mocks.reauthenticate.mockResolvedValue('authenticated')
  })

  test('切换线路时先应用库选择，再持久化偏好并重新认证', async () => {
    const { useApiRoute } = await import('@/composables/useApiRoute')
    const route = useApiRoute()

    await route.select('manual', 'api.example')

    expect(mocks.applyApiRoute).toHaveBeenCalledWith({ mode: 'manual', domain: 'api.example' })
    expect(mocks.setApiRoutePreference).toHaveBeenCalledWith({ mode: 'manual', domain: 'api.example' })
    expect(mocks.reauthenticate).toHaveBeenCalledOnce()
    expect(route.mode.value).toBe('manual')
    expect(route.selectedDomain.value).toBe('api.example')
  })

  test('手动线路应用失败时不保存偏好、不降级为自动线路', async () => {
    mocks.applyApiRoute.mockRejectedValueOnce(new Error('线路不可用'))
    const { useApiRoute } = await import('@/composables/useApiRoute')

    await expect(useApiRoute().select('manual', 'api.example')).rejects.toThrow('线路不可用')

    expect(mocks.setApiRoutePreference).not.toHaveBeenCalled()
    expect(mocks.applyApiRoute).toHaveBeenCalledTimes(1)
    expect(mocks.reauthenticate).not.toHaveBeenCalled()
  })

  test('自动模式先清理当前实际线路，再切回自动选择', async () => {
    const { useApiRoute } = await import('@/composables/useApiRoute')

    await useApiRoute().select('auto')

    expect(mocks.applyApiRoute).toHaveBeenNthCalledWith(1, {
      mode: 'manual',
      domain: 'api.example',
    })
    expect(mocks.applyApiRoute).toHaveBeenNthCalledWith(2, { mode: 'auto' })
  })
})
