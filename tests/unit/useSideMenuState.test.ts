import { afterEach, describe, expect, test, vi } from 'vitest'
import {
  collapseWideMenu,
  EXPANDED_WIDE_MENU_MEDIA_QUERY,
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

  test('桌面侧栏在 960px 启用，中等宽度自动收起并在 1264px 展开', () => {
    let wideChangeListener: ((event: MediaQueryListEvent) => void) | undefined
    let expandedChangeListener: ((event: MediaQueryListEvent) => void) | undefined
    const makeMediaQuery = (
      matches: boolean,
      assign: (listener: (event: MediaQueryListEvent) => void) => void,
    ) =>
      ({
        matches,
        addEventListener: vi.fn((_type: string, listener: (event: MediaQueryListEvent) => void) => {
          assign(listener)
        }),
        removeEventListener: vi.fn(),
      }) as unknown as MediaQueryList
    const wideMediaQuery = makeMediaQuery(false, (listener) => {
      wideChangeListener = listener
    })
    const expandedMediaQuery = makeMediaQuery(false, (listener) => {
      expandedChangeListener = listener
    })
    const matchMedia = vi.fn((query: string) =>
      query === WIDE_MENU_MEDIA_QUERY ? wideMediaQuery : expandedMediaQuery,
    )
    vi.stubGlobal('matchMedia', matchMedia)

    startWideMenuTracking()
    expect(matchMedia).toHaveBeenCalledWith(WIDE_MENU_MEDIA_QUERY)
    expect(matchMedia).toHaveBeenCalledWith(EXPANDED_WIDE_MENU_MEDIA_QUERY)
    expect(isWideMenu.value).toBe(false)

    leftMenuOpen.value = true
    wideMediaQuery.matches = true
    wideChangeListener?.({ matches: true } as MediaQueryListEvent)
    expect(isWideMenu.value).toBe(true)
    expect(wideMenuCollapsed.value).toBe(true)
    expect(leftMenuOpen.value).toBe(false)

    expandedMediaQuery.matches = true
    expandedChangeListener?.({ matches: true } as MediaQueryListEvent)
    expect(wideMenuCollapsed.value).toBe(false)

    expandedMediaQuery.matches = false
    expandedChangeListener?.({ matches: false } as MediaQueryListEvent)
    expect(wideMenuCollapsed.value).toBe(true)

    wideMediaQuery.matches = false
    wideChangeListener?.({ matches: false } as MediaQueryListEvent)
    expect(isWideMenu.value).toBe(false)
    expect(wideMenuCollapsed.value).toBe(false)
    expect(leftMenuOpen.value).toBe(false)
    stopWideMenuTracking()
    expect(wideMediaQuery.removeEventListener).toHaveBeenCalledWith('change', wideChangeListener)
    expect(expandedMediaQuery.removeEventListener).toHaveBeenCalledWith(
      'change',
      expandedChangeListener,
    )
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
