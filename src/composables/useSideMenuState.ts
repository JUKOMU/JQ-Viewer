import { ref } from 'vue'

/** 左侧主菜单是否打开 */
export const leftMenuOpen = ref(false)

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

export function openLeftMenu() {
  rightMenuOpen.value = false
  leftMenuOpen.value = true
}

export function closeLeftMenu() {
  leftMenuOpen.value = false
}

export function setLeftMenuGestureEnabled(enabled: boolean) {
  leftMenuGestureEnabled.value = enabled
}

export function useSideMenuState() {
  return {
    leftMenuOpen,
    leftMenuGestureEnabled,
    rightMenuOpen,
    rightDragProgress,
    isDraggingRight,
    isSnappingClosed,
    isMenuNavigation,
    openLeftMenu,
    closeLeftMenu,
    setLeftMenuGestureEnabled,
  }
}
