import { describe, expect, test } from 'vitest'
import { useAuth } from '@/composables/useAuth'

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
})
