import { ref } from 'vue'

export function useTimedReveal(durationMs: number) {
  const visible = ref(false)
  let timer: ReturnType<typeof setTimeout> | null = null

  function reveal() {
    if (visible.value) return
    visible.value = true
    timer = setTimeout(() => {
      visible.value = false
      timer = null
    }, durationMs)
  }

  function dispose() {
    if (timer) clearTimeout(timer)
    timer = null
  }

  return { visible, reveal, dispose }
}
