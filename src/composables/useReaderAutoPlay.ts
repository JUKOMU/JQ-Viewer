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
  /** 位移由纵向视图在布局提交后落地，返回该方向能否继续推进。 */
  advanceScroll: (distance: number) => Promise<boolean>
  /** 撤销尚未落地的播放位移，保留视图自己的图片布局补偿。 */
  cancelScroll: () => void
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
  // 暂停、调速或离页后，旧提交的完成回调不能重新启动播放帧。
  let scrollVersion = 0

  function clearScheduledWork() {
    scrollVersion++
    options.cancelScroll()
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
    interactionActive = false
    if (!isPlaying.value) return
    isPlaying.value = false
    remainingMs = 0
    clearScheduledWork()
    syncNativeVolumeInterception(false)
  }

  async function scrollFrame(now: number) {
    if (!isPlaying.value || interactionActive || !options.isVertical.value) {
      animationFrame = null
      return
    }
    const container = options.getScrollContainer()
    if (!container) {
      animationFrame = null
      return
    }
    const direction = Math.sign(intervalSeconds.value)
    const elapsed = lastFrameAt === 0 ? 0 : now - lastFrameAt
    lastFrameAt = now
    const distance = (container.clientHeight * elapsed) / (Math.abs(intervalSeconds.value) * 1000)
    const version = scrollVersion
    // 提交期间保留帧句柄，geometry-change 不会再开启第二条播放循环。
    const canContinue = await options.advanceScroll(distance * direction)
    if (version !== scrollVersion) return
    animationFrame = null
    if (canContinue) animationFrame = requestAnimationFrame(scrollFrame)
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
      if (nextIndex < 0 || nextIndex >= options.totalCount.value) {
        stop()
        return
      }
      options.goToIndex(nextIndex)
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
      remainingMs = Math.max(0, remainingMs - (performance.now() - timerStartedAt))
    }
    clearScheduledWork()
    lastFrameAt = 0
  }

  function resumeAfterInteraction() {
    if (interactionActive) interactionActive = false
    if (isPlaying.value && animationFrame === null && timer === null) schedule()
  }

  function resumeAfterGeometryChange() {
    if (
      isPlaying.value &&
      options.isVertical.value &&
      !interactionActive &&
      animationFrame === null
    ) {
      schedule()
    }
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

  watch(
    () => options.totalCount.value,
    () => {
      if (
        isPlaying.value &&
        options.isVertical.value &&
        !interactionActive &&
        animationFrame === null
      ) {
        schedule()
      }
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
    resumeAfterGeometryChange,
    resetAfterHorizontalPageChange,
    updateInterval,
    handleVolumeDirection,
    handleDirectionKey,
  }
}
