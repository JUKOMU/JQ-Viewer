import { describe, expect, test } from 'vitest'
import { useSideMenuState } from '@/composables/useSideMenuState'

describe('useSideMenuState', () => {
  test('所有菜单状态初始为关闭', () => {
    const state = useSideMenuState()
    expect(state.leftMenuOpen.value).toBe(false)
    expect(state.rightMenuOpen.value).toBe(false)
    expect(state.isDraggingRight.value).toBe(false)
    expect(state.isSnappingClosed.value).toBe(false)
    expect(state.isMenuNavigation.value).toBe(false)
  })

  test('rightDragProgress 初始为 0', () => {
    const { rightDragProgress } = useSideMenuState()
    expect(rightDragProgress.value).toBe(0)
  })

  test('单例模式：状态跨调用共享', () => {
    const a = useSideMenuState()
    const b = useSideMenuState()

    a.leftMenuOpen.value = true
    expect(b.leftMenuOpen.value).toBe(true)

    a.leftMenuOpen.value = false
    expect(b.leftMenuOpen.value).toBe(false)
  })

  test('isMenuNavigation 可独立设置', () => {
    const { isMenuNavigation } = useSideMenuState()
    isMenuNavigation.value = true
    expect(isMenuNavigation.value).toBe(true)
    isMenuNavigation.value = false
    expect(isMenuNavigation.value).toBe(false)
  })
})
