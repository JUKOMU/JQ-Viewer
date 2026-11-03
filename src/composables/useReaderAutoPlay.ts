import { onDeactivated, onUnmounted, ref, watch, type Ref } from 'vue'
import { getRuntime } from '@/runtime/runtimeContext'
import { sanitizeError, showToast } from '@/services/JmcomicService'
import { persistReaderAutoPlayInterval, SettingsStore } from '@/services/SettingsService'

/** 在漫画、PDF 和 CBZ 阅读页共享自动翻页、连续滚动及按键调速行为。 */
export function useReaderAutoPlay(options: {
  isVertical: Ref<boolean>
  currentIndex: Ref<number>
  totalCount: Ref<number>
  getScrollContainer: () => HTMLElement | null
  goToIndex: (index: number) => void
}) {
  const isPlaying = ref(false)
  const intervalSeconds = ref(SettingsStore.getReaderAutoPlayInterval())
  const speedAdjustmentVersion = ref(0)
  const volumeKeys = getRuntime().services.reader.volumeKeys
  let interactionActive = false
  let timer: ReturnType<typeof setTimeout> | null = null
  let animationFrame: number | null = null
  let timerStartedAt = 0
  let remainingMs = 0
  let lastFrameAt = 0

  function clearScheduledWork() {
    if (timer) clearTimeout(timer)
    timer = null
    if (animationFrame !== null) cancelAnimationFrame(animationFrame)
    animationFrame = null
  }

  function syncNativeVolumeInterception(enabled: boolean) {
    if (!volumeKeys.available) return
    volumeKeys.api.setAutoPlayActive(enabled).catch((error) => {
      void showToast(sanitizeError(error, '切换自动播放音量键控制失败'), 'danger')
    })
  }

  function stop() {
    if (!isPlaying.value) return
    isPlaying.value = false
    interactionActive = false
    remainingMs = 0
    clearScheduledWork()
    syncNativeVolumeInterception(false)
  }

  function scrollFrame(now: number) {
    animationFrame = null
    if (!isPlaying.value || interactionActive || !options.isVertical.value) return
    const container = options.getScrollContainer()
    if (!container) return
    const direction = Math.sign(intervalSeconds.value)
    const elapsed = lastFrameAt === 0 ? 0 : now - lastFrameAt
    lastFrameAt = now
    const distance = (container.clientHeight * elapsed) / (Math.abs(intervalSeconds.value) * 1000)
    const maxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight)
    const nextScrollTop = Math.max(
      0,
      Math.min(maxScrollTop, container.scrollTop + distance * direction),
    )
    container.scrollTop = nextScrollTop
    animationFrame = requestAnimationFrame(scrollFrame)
  }

  function schedule() {
    if (!isPlaying.value || interactionActive || options.totalCount.value === 0) return
    if (options.isVertical.value) {
      lastFrameAt = 0
      animationFrame = requestAnimationFrame(scrollFrame)
      return
    }
    if (remainingMs <= 0) remainingMs = Math.abs(intervalSeconds.value) * 1000
    timerStartedAt = performance.now()
    timer = setTimeout(() => {
      timer = null
      const nextIndex = options.currentIndex.value + Math.sign(intervalSeconds.value)
      if (nextIndex >= 0 && nextIndex < options.totalCount.value) options.goToIndex(nextIndex)
      remainingMs = Math.abs(intervalSeconds.value) * 1000
      schedule()
    }, remainingMs)
  }

  function toggle() {
    if (isPlaying.value) {
      stop()
      return
    }
    if (options.totalCount.value === 0) return
    intervalSeconds.value = SettingsStore.getReaderAutoPlayInterval()
    isPlaying.value = true
    remainingMs = Math.abs(intervalSeconds.value) * 1000
    syncNativeVolumeInterception(true)
    schedule()
  }

  function pauseForInteraction() {
    if (interactionActive) return
    interactionActive = true
    if (timer) {
      clearTimeout(timer)
      timer = null
      remainingMs = Math.max(0, remainingMs - (performance.now() - timerStartedAt))
    }
    if (animationFrame !== null) cancelAnimationFrame(animationFrame)
    animationFrame = null
    lastFrameAt = 0
  }

  function resumeAfterInteraction() {
    if (!interactionActive) return
    interactionActive = false
    schedule()
  }

  function resetAfterHorizontalPageChange() {
    if (!isPlaying.value || options.isVertical.value) return
    clearScheduledWork()
    remainingMs = Math.abs(intervalSeconds.value) * 1000
    schedule()
  }

  function updateInterval(seconds: number) {
    intervalSeconds.value = seconds
    if (!isPlaying.value) return
    clearScheduledWork()
    remainingMs = Math.abs(seconds) * 1000
    schedule()
  }

  function handleVolumeDirection(direction: 'up' | 'down'): boolean {
    if (!isPlaying.value) return false
    const current = intervalSeconds.value
    const adjustment = Math.max(0.1, Math.abs(current) * 0.1)
    let next: number
    // 音量键始终推动间隔朝对应滚动方向变化，跨过 30 秒边界时切换方向。
    if (direction === 'up' && current < 0) {
      next = Math.min(-0.1, current + adjustment)
    } else if (direction === 'up') {
      const increased = current + adjustment
      next = increased > 30 ? -30 : increased
    } else if (current > 0) {
      next = Math.max(0.1, current - adjustment)
    } else {
      const decreased = current - adjustment
      next = decreased < -30 ? 30 : decreased
    }
    next = Math.round(next * 100) / 100
    updateInterval(next)
    speedAdjustmentVersion.value++
    void persistReaderAutoPlayInterval(next).catch((error) => {
      updateInterval(SettingsStore.getReaderAutoPlayInterval())
      void showToast(sanitizeError(error, '保存自动播放速度失败'), 'danger')
    })
    return true
  }

  function handleDirectionKey(direction: 'ArrowUp' | 'ArrowDown' | 'ArrowLeft' | 'ArrowRight') {
    if (!isPlaying.value) return false
    return handleVolumeDirection(
      direction === 'ArrowUp' || direction === 'ArrowLeft' ? 'up' : 'down',
    )
  }

  watch(
    () => options.isVertical.value,
    () => {
      if (!isPlaying.value || interactionActive) return
      clearScheduledWork()
      remainingMs = Math.abs(intervalSeconds.value) * 1000
      schedule()
    },
  )

  onDeactivated(stop)
  onUnmounted(stop)

  return {
    isPlaying,
    intervalSeconds,
    speedAdjustmentVersion,
    toggle,
    stop,
    pauseForInteraction,
    resumeAfterInteraction,
    resetAfterHorizontalPageChange,
    updateInterval,
    handleVolumeDirection,
    handleDirectionKey,
  }
}
