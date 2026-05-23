import { ref } from 'vue'

/** 左侧主菜单是否打开 */
export const leftMenuOpen = ref(false)

/** 右侧收藏夹菜单是否打开 */
export const rightMenuOpen = ref(false)

/** 右侧面板拖拽进度 0=关闭 1=完全打开 */
export const rightDragProgress = ref(0)

/** 是否正在手指拖拽右侧面板（拖拽中禁用 CSS transition） */
export const isDraggingRight = ref(false)

/** 是否正在执行关闭吸附动画（面板可见但 animate to 关闭位置） */
export const isSnappingClosed = ref(false)
