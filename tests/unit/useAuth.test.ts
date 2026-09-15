import { describe, expect, test, vi } from 'vitest'
import { useAuth } from '@/composables/useAuth'

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    login: vi.fn().mockResolvedValue({ uid: 'user-1', username: 'alice' }),
    logout: vi.fn().mockRejectedValue(new Error('remote logout failed')),
  },
}))

describe('useAuth', () => {
  test('isLoggedIn 初始为 false', () => {
    const { isLoggedIn } = useAuth()
    expect(isLoggedIn.value).toBe(false)
  })

  test('userInfo 初始为 null', () => {
    const { userInfo } = useAuth()
    expect(userInfo.value).toBeNull()
  })

  test('单例模式：多次调用返回相同的 ref', () => {
    const a = useAuth()
    const b = useAuth()
    expect(a.isLoggedIn).toBe(b.isLoggedIn)
    expect(a.userInfo).toBe(b.userInfo)
  })

  test('远端退出失败时仍清除前端登录状态', async () => {
    const auth = useAuth()
    await auth.login('alice', 'secret')

    await expect(auth.logout()).rejects.toThrow('remote logout failed')

    expect(auth.isLoggedIn.value).toBe(false)
    expect(auth.userInfo.value).toBeNull()
  })
})
