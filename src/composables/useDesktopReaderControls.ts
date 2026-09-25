import { onMounted, onUnmounted, ref, type Ref } from 'vue'

type ReaderDirection = 'up' | 'down'

interface UseDesktopReaderControlsOptions {
  enabled: boolean
  isActive: () => boolean
  isVertical: Ref<boolean>
  currentIndex: Ref<number>
  totalCount: Ref<number>
  fullscreenAvailable: boolean
  onPreviousPage: () => void
  onNextPage: () => void
  onVolumeDirection: (direction: ReaderDirection) => boolean
  setFullscreen: (enabled: boolean) => Promise<unknown>
  onFullscreenError: (error: unknown) => void
}

function isReaderControlTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false
  if (target.isContentEditable) return true
  if (
    target instanceof HTMLInputElement ||
    target instanceof HTMLTextAreaElement ||
    target instanceof HTMLSelectElement ||
    target instanceof HTMLButtonElement ||
    target instanceof HTMLAnchorElement
  ) {
    return true
  }
  return Boolean(
    target.closest(
      '[role="dialog"], [role="slider"], [aria-modal="true"], .panel-overlay, [contenteditable="true"]',
    ),
  )
}

export function useDesktopReaderControls(options: UseDesktopReaderControlsOptions) {
  const isFullscreen = ref(false)

  const syncFullscreenState = () => {
    if (!options.enabled || typeof document === 'undefined') {
      isFullscreen.value = false
      return
    }
    isFullscreen.value = document.fullscreenElement === document.documentElement
  }

  const toggleFullscreen = () => {
    if (!options.enabled || !options.isActive() || !options.fullscreenAvailable) {
      return
    }

    const next = !isFullscreen.value
    isFullscreen.value = next
    void options
      .setFullscreen(next)
      .then(syncFullscreenState)
      .catch((error) => {
        syncFullscreenState()
        options.onFullscreenError(error)
      })
  }

  const onKeyDown = (event: KeyboardEvent) => {
    if (
      !options.enabled ||
      !options.isActive() ||
      event.defaultPrevented ||
      event.altKey ||
      event.ctrlKey ||
      event.metaKey ||
      event.shiftKey ||
      isReaderControlTarget(event.target)
    ) {
      return
    }

    let handled = false
    if (event.key === 'ArrowLeft' && options.currentIndex.value > 0) {
      options.onPreviousPage()
      handled = true
    } else if (
      event.key === 'ArrowRight' &&
      options.currentIndex.value < options.totalCount.value - 1
    ) {
      options.onNextPage()
      handled = true
    } else if (event.key === 'ArrowUp' || event.key === 'ArrowDown') {
      handled = options.isVertical.value
        ? options.onVolumeDirection(event.key === 'ArrowUp' ? 'up' : 'down')
        : event.key === 'ArrowUp'
          ? options.currentIndex.value > 0 && (options.onPreviousPage(), true)
          : options.currentIndex.value < options.totalCount.value - 1 &&
            (options.onNextPage(), true)
    }

    if (handled) event.preventDefault()
  }

  onMounted(() => {
    if (!options.enabled) return
    window.addEventListener('keydown', onKeyDown)
    document.addEventListener('fullscreenchange', syncFullscreenState)
    syncFullscreenState()
  })

  onUnmounted(() => {
    if (!options.enabled) return
    window.removeEventListener('keydown', onKeyDown)
    document.removeEventListener('fullscreenchange', syncFullscreenState)
  })

  return {
    isFullscreen,
    syncFullscreenState,
    toggleFullscreen,
  }
}
