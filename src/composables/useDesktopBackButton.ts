import { onMounted, onUnmounted } from 'vue'

/** 将桌面端 ESC 接入 Ionic 的系统返回分发链。 */
export function useDesktopBackButton(enabled = import.meta.env.MODE === 'desktop') {
  const onKeyDown = (event: KeyboardEvent) => {
    if (!enabled || event.key !== 'Escape' || event.defaultPrevented) return

    event.preventDefault()
    event.stopImmediatePropagation()
    document.dispatchEvent(new Event('backbutton'))
  }

  onMounted(() => {
    if (enabled) window.addEventListener('keydown', onKeyDown, true)
  })

  onUnmounted(() => {
    if (enabled) window.removeEventListener('keydown', onKeyDown, true)
  })
}
