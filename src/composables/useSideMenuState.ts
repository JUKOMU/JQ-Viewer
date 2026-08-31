import { ref } from 'vue'

export const WIDE_MENU_MEDIA_QUERY = '(min-width: 992px)'

/** 左侧主菜单是否打开 */
export const leftMenuOpen = ref(false)

/** 当前窗口是否满足宽屏侧栏断点 */
export const isWideMenu = ref(false)

/** 宽屏侧栏是否收起为紧凑 rail；仅在当前运行期间保留 */
export const wideMenuCollapsed = ref(false)

/** 详情页等局部横滑手势生效时，暂时禁用全局左侧栏开启手势 */
export const leftMenuGestureEnabled = ref(true)

/** 右侧收藏夹菜单是否打开 */
export const rightMenuOpen = ref(false)

/** 右侧面板拖拽进度 0=关闭 1=完全打开 */
export const rightDragProgress = ref(0)

/** 是否正在手指拖拽右侧面板（拖拽中禁用 CSS transition） */
export const isDraggingRight = ref(false)

/** 是否正在执行关闭吸附动画（面板可见但 animate to 关闭位置） */
export const isSnappingClosed = ref(false)

/** 当前导航是否由侧边栏菜单触发 */
export const isMenuNavigation = ref(false)

let wideMenuMediaQuery: MediaQueryList | null = null
let wideMenuChangeListener: ((event: MediaQueryListEvent) => void) | null = null

const syncWideMenu = (matches: boolean) => {
  isWideMenu.value = matches
  // overlay 只属于窄屏，跨断点时立即收起，避免旧状态覆盖正文。
  leftMenuOpen.value = false
}

/** 开始监听宽屏断点。调用方应在所属组件卸载时调用 stopWideMenuTracking。 */
export function startWideMenuTracking() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    syncWideMenu(false)
    return
  }
  if (wideMenuMediaQuery) return

  wideMenuMediaQuery = window.matchMedia(WIDE_MENU_MEDIA_QUERY)
  syncWideMenu(wideMenuMediaQuery.matches)
  wideMenuChangeListener = (event) => syncWideMenu(event.matches)
  wideMenuMediaQuery.addEventListener('change', wideMenuChangeListener)
}

/** 停止监听宽屏断点并清理组件所属的运行期状态。 */
export function stopWideMenuTracking() {
  if (wideMenuMediaQuery && wideMenuChangeListener) {
    wideMenuMediaQuery.removeEventListener('change', wideMenuChangeListener)
  }
  wideMenuMediaQuery = null
  wideMenuChangeListener = null
  syncWideMenu(false)
}

export function openLeftMenu() {
  rightMenuOpen.value = false
  if (isWideMenu.value) {
    wideMenuCollapsed.value = false
    return
  }
  leftMenuOpen.value = true
}

export function closeLeftMenu() {
  leftMenuOpen.value = false
}

export function collapseWideMenu() {
  rightMenuOpen.value = false
  wideMenuCollapsed.value = true
  leftMenuOpen.value = false
}

export function expandWideMenu() {
  rightMenuOpen.value = false
  wideMenuCollapsed.value = false
}

export function setLeftMenuGestureEnabled(enabled: boolean) {
  leftMenuGestureEnabled.value = enabled
}

export function useSideMenuState() {
  return {
    leftMenuOpen,
    isWideMenu,
    wideMenuCollapsed,
    leftMenuGestureEnabled,
    rightMenuOpen,
    rightDragProgress,
    isDraggingRight,
    isSnappingClosed,
    isMenuNavigation,
    openLeftMenu,
    closeLeftMenu,
    collapseWideMenu,
    expandWideMenu,
    setLeftMenuGestureEnabled,
    startWideMenuTracking,
    stopWideMenuTracking,
  }
}
