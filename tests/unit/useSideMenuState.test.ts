import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  collapseWideMenu,
  expandWideMenu,
  leftMenuOpen,
  isWideMenu,
  openLeftMenu,
  startWideMenuTracking,
  stopWideMenuTracking,
  useSideMenuState,
  WIDE_MENU_MEDIA_QUERY,
  wideMenuCollapsed,
} from '@/composables/useSideMenuState'

afterEach(() => {
  stopWideMenuTracking()
  isWideMenu.value = false
  wideMenuCollapsed.value = false
  vi.unstubAllGlobals()
})

describe('useSideMenuState', () => {
  test('所有菜单状态初始为关闭', () => {
    const state = useSideMenuState()
    expect(state.leftMenuOpen.value).toBe(false)
    expect(state.isWideMenu.value).toBe(false)
    expect(state.wideMenuCollapsed.value).toBe(false)
    expect(state.leftMenuGestureEnabled.value).toBe(true)
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

  test('左侧菜单操作会关闭右侧菜单并可独立停用手势', () => {
    const state = useSideMenuState()

    state.rightMenuOpen.value = true
    state.openLeftMenu()
    expect(state.leftMenuOpen.value).toBe(true)
    expect(state.rightMenuOpen.value).toBe(false)

    state.setLeftMenuGestureEnabled(false)
    expect(state.leftMenuGestureEnabled.value).toBe(false)
    state.closeLeftMenu()
    state.setLeftMenuGestureEnabled(true)
  })

  test('宽屏断点在 992px 边界动态切换并关闭窄屏 overlay', () => {
    let changeListener: ((event: MediaQueryListEvent) => void) | undefined
    const mediaQuery = {
      matches: false,
      addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => {
        changeListener = listener
      }),
      removeEventListener: vi.fn(),
    } as unknown as MediaQueryList
    const matchMedia = vi.fn(() => mediaQuery)
    vi.stubGlobal('matchMedia', matchMedia)

    startWideMenuTracking()
    expect(matchMedia).toHaveBeenCalledWith(WIDE_MENU_MEDIA_QUERY)
    expect(isWideMenu.value).toBe(false)

    leftMenuOpen.value = true
    changeListener?.({ matches: true } as MediaQueryListEvent)
    expect(isWideMenu.value).toBe(true)
    expect(leftMenuOpen.value).toBe(false)

    changeListener?.({ matches: false } as MediaQueryListEvent)
    expect(isWideMenu.value).toBe(false)
    expect(leftMenuOpen.value).toBe(false)
    stopWideMenuTracking()
    expect(mediaQuery.removeEventListener).toHaveBeenCalledWith('change', changeListener)
  })

  test('宽屏打开操作展开 rail，收起状态在运行期间保留', () => {
    isWideMenu.value = true
    collapseWideMenu()
    expect(wideMenuCollapsed.value).toBe(true)
    expect(leftMenuOpen.value).toBe(false)

    openLeftMenu()
    expect(wideMenuCollapsed.value).toBe(false)
    expect(leftMenuOpen.value).toBe(false)

    collapseWideMenu()
    isWideMenu.value = false
    isWideMenu.value = true
    expect(wideMenuCollapsed.value).toBe(true)
    expandWideMenu()
    expect(wideMenuCollapsed.value).toBe(false)
  })
})
