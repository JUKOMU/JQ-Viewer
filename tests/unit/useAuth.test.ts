import { beforeEach, describe, expect, test, vi } from 'vitest'
import { RuntimeError } from '@/runtime/errors'

const mocks = vi.hoisted(() => ({
  checkLoginState: vi.fn(),
  autoLogin: vi.fn(),
  login: vi.fn(),
  logout: vi.fn(),
  clearFavoriteFolderStore: vi.fn(),
  clearFavoritePageCache: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    checkLoginState: mocks.checkLoginState,
    autoLogin: mocks.autoLogin,
    login: mocks.login,
    logout: mocks.logout,
  },
}))

vi.mock('@/composables/favoriteFolderStore', () => ({
  clearFavoriteFolderStore: mocks.clearFavoriteFolderStore,
}))

vi.mock('@/composables/favoritePageCache', () => ({
  clearFavoritePageCache: mocks.clearFavoritePageCache,
}))

beforeEach(() => {
  vi.resetModules()
  vi.resetAllMocks()
  mocks.login.mockResolvedValue({ uid: 'user-1', username: 'alice' })
  mocks.logout.mockRejectedValue(new Error('remote logout failed'))
})

describe('useAuth', () => {
  test('isLoggedIn 初始为 false', async () => {
    const { useAuth } = await import('@/composables/useAuth')
    expect(useAuth().isLoggedIn.value).toBe(false)
  })

  test('userInfo 初始为 null', async () => {
    const { useAuth } = await import('@/composables/useAuth')
    expect(useAuth().userInfo.value).toBeNull()
  })

  test('单例模式：多次调用返回相同的 ref', async () => {
    const { useAuth } = await import('@/composables/useAuth')
    const a = useAuth()
    const b = useAuth()
    expect(a.isLoggedIn).toBe(b.isLoggedIn)
    expect(a.userInfo).toBe(b.userInfo)
  })

  test('远端退出失败时仍清除前端登录状态', async () => {
    const { useAuth } = await import('@/composables/useAuth')
    const auth = useAuth()
    await auth.login('alice', 'secret')

    await expect(auth.logout()).rejects.toThrow('remote logout failed')

    expect(auth.isLoggedIn.value).toBe(false)
    expect(auth.userInfo.value).toBeNull()
  })

  test('本地登录态读取失败时保留为可重试错误', async () => {
    mocks.checkLoginState.mockRejectedValue(new RuntimeError('network', 'offline'))
    const { useAuth } = await import('@/composables/useAuth')

    await expect(useAuth().initAuth()).resolves.toBe('retryable-error')
    expect(mocks.autoLogin).not.toHaveBeenCalled()
    expect(mocks.clearFavoriteFolderStore).not.toHaveBeenCalled()
  })

  test('自动登录网络失败允许后续 ready 事件重试', async () => {
    mocks.checkLoginState.mockResolvedValue({ loggedIn: false })
    mocks.autoLogin.mockRejectedValue(new RuntimeError('network', 'offline'))
    const { useAuth } = await import('@/composables/useAuth')

    await expect(useAuth().initAuth()).resolves.toBe('retryable-error')
    expect(mocks.clearFavoriteFolderStore).not.toHaveBeenCalled()
  })

  test.each(['not-found', 'permission-denied'] as const)(
    '%s 表示已确认未登录并清理用户缓存',
    async (code) => {
      mocks.checkLoginState.mockResolvedValue({ loggedIn: false })
      mocks.autoLogin.mockRejectedValue(new RuntimeError(code, 'no credentials'))
      const { useAuth } = await import('@/composables/useAuth')

      await expect(useAuth().initAuth()).resolves.toBe('unauthenticated')
      expect(mocks.clearFavoriteFolderStore).toHaveBeenCalledOnce()
      expect(mocks.clearFavoritePageCache).toHaveBeenCalledOnce()
    },
  )

  test('已有本地用户信息时直接完成认证', async () => {
    const userInfo = { uid: '1', username: 'alice' }
    mocks.checkLoginState.mockResolvedValue({ loggedIn: true, userInfo })
    const { useAuth } = await import('@/composables/useAuth')
    const auth = useAuth()

    await expect(auth.initAuth()).resolves.toBe('authenticated')
    expect(auth.userInfo.value).toEqual(userInfo)
    expect(mocks.autoLogin).not.toHaveBeenCalled()
  })

  test('后续认证确认凭据失效时清除已有用户状态', async () => {
    mocks.checkLoginState
      .mockResolvedValueOnce({ loggedIn: true, userInfo: { uid: '1', username: 'alice' } })
      .mockResolvedValueOnce({ loggedIn: false })
    mocks.autoLogin.mockRejectedValue(new RuntimeError('permission-denied', 'expired'))
    const { useAuth } = await import('@/composables/useAuth')
    const auth = useAuth()

    await expect(auth.initAuth()).resolves.toBe('authenticated')
    await expect(auth.initAuth()).resolves.toBe('unauthenticated')

    expect(auth.userInfo.value).toBeNull()
  })
})
