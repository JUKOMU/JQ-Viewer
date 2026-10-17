import { defineComponent, h, ref, type Ref } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useDesktopReaderControls } from '@/composables/useDesktopReaderControls'

type ReaderFixture = {
  enabled: boolean
  vertical: Ref<boolean>
  current: Ref<number>
  total: Ref<number>
  previous: ReturnType<typeof vi.fn>
  next: ReturnType<typeof vi.fn>
  volume: ReturnType<typeof vi.fn>
  setFullscreen: ReturnType<typeof vi.fn>
}

function mountControls(enabled = true) {
  const fixture: ReaderFixture = {
    enabled,
    vertical: ref(false),
    current: ref(1),
    total: ref(3),
    previous: vi.fn(),
    next: vi.fn(),
    volume: vi.fn(() => true),
    setFullscreen: vi.fn(() => Promise.resolve({ success: true })),
  }

  const component = defineComponent({
    setup(_, { expose }) {
      const controls = useDesktopReaderControls({
        enabled: fixture.enabled,
        isActive: () => true,
        isVertical: fixture.vertical,
        currentIndex: fixture.current,
        totalCount: fixture.total,
        fullscreenAvailable: true,
        onPreviousPage: fixture.previous,
        onNextPage: fixture.next,
        onVolumeDirection: fixture.volume,
        setFullscreen: fixture.setFullscreen,
        onFullscreenError: vi.fn(),
      })
      expose(controls)
      return () => h('div', [h('input')])
    },
  })

  return { fixture, wrapper: mount(component) }
}

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useDesktopReaderControls', () => {
  test('桌面端方向键复用阅读动作并阻止默认滚动', () => {
    const { fixture, wrapper } = mountControls()

    const left = new KeyboardEvent('keydown', { key: 'ArrowLeft', cancelable: true })
    window.dispatchEvent(left)
    expect(fixture.previous).toHaveBeenCalledTimes(1)
    expect(left.defaultPrevented).toBe(true)

    const up = new KeyboardEvent('keydown', { key: 'ArrowUp', cancelable: true })
    window.dispatchEvent(up)
    expect(fixture.previous).toHaveBeenCalledTimes(2)

    fixture.vertical.value = true
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', cancelable: true }))
    expect(fixture.volume).toHaveBeenCalledWith('down')

    wrapper.unmount()
  })

  test('输入控件获得焦点时不劫持方向键', () => {
    const { fixture, wrapper } = mountControls()
    const input = wrapper.get('input').element
    input.focus()
    const event = new KeyboardEvent('keydown', {
      key: 'ArrowRight',
      bubbles: true,
      cancelable: true,
    })
    input.dispatchEvent(event)

    expect(fixture.next).not.toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(false)
    wrapper.unmount()
  })

  test('移动端关闭桌面方向键监听', () => {
    const { fixture, wrapper } = mountControls(false)
    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', cancelable: true }))
    expect(fixture.next).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('全屏按钮调用平台能力并同步 fullscreenchange', async () => {
    const { fixture, wrapper } = mountControls()
    const controls = wrapper.vm as unknown as {
      toggleFullscreen: () => void
      isFullscreen: boolean
    }

    controls.toggleFullscreen()
    await Promise.resolve()
    expect(fixture.setFullscreen).toHaveBeenCalledWith(true)

    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      value: document.documentElement,
    })
    document.dispatchEvent(new Event('fullscreenchange'))
    expect(controls.isFullscreen).toBe(true)

    wrapper.unmount()
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      value: null,
    })
  })
})
