/* eslint-disable vue/one-component-per-file -- test-only component stubs */
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, test, vi } from 'vitest'

vi.mock('@ionic/vue', () => ({
  IonIcon: defineComponent({
    name: 'IonIcon',
    setup() {
      return () => h('span')
    },
  }),
  IonRange: defineComponent({
    name: 'IonRange',
    setup() {
      return () => h('div', { class: 'ion-range-stub' })
    },
  }),
  IonToggle: defineComponent({
    name: 'IonToggle',
    props: {
      checked: Boolean,
      disabled: Boolean,
    },
    setup(props) {
      return () =>
        h('button', {
          class: 'ion-toggle-stub',
          disabled: props.disabled,
        })
    },
  }),
}))

vi.mock('ionicons/icons', () => ({ closeOutline: 'close' }))

vi.mock('@/runtime/runtimeContext', () => ({
  getRuntime: () => ({
    services: {
      reader: {
        orientation: {
          available: false,
          reason: '桌面显示器不支持应用级屏幕方向控制',
        },
        brightness: { available: false, reason: '桌面浏览器无法调整系统屏幕亮度' },
        keepAwake: { available: false, reason: '当前浏览器不支持屏幕常亮' },
        fullscreen: { available: false, reason: '当前浏览器不支持页面全屏' },
        volumeKeys: { available: false, reason: '桌面浏览器无法可靠拦截系统音量键' },
        hostState: { available: true, api: {} },
      },
    },
  }),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    setReaderAutoShowToolbarAtEnd: vi.fn(),
    setReaderBrightness: vi.fn(),
    setReaderDisplayMode: vi.fn(),
    setReaderWidthPercent: vi.fn(() => Promise.resolve({ success: true })),
    setReaderKeepScreenOn: vi.fn(),
    setReaderScreenOrientation: vi.fn(),
    setReaderVolumeNavigation: vi.fn(),
  },
  sanitizeError: (_error: unknown, fallback: string) => fallback,
  showToast: vi.fn(),
}))

vi.mock('@/services/SettingsService', () => ({
  persistPreloadConcurrency: vi.fn(),
  SettingsStore: {
    getReaderWidthPercent: vi.fn(() => null),
    setReaderWidthPercent: vi.fn(),
    getPreloadConcurrency: vi.fn(() => 4),
    getReaderAutoShowToolbarAtEnd: vi.fn(() => true),
    getReaderBrightness: vi.fn(() => -1),
    getReaderKeepScreenOn: vi.fn(() => false),
    getReaderPreloadPages: vi.fn(() => 10),
    getReaderScreenOrientation: vi.fn(() => 'auto'),
    getReaderVolumeNavigation: vi.fn(() => false),
    setReaderAutoShowToolbarAtEnd: vi.fn(),
    setReaderBrightness: vi.fn(),
    setReaderDisplayMode: vi.fn(),
    setReaderKeepScreenOn: vi.fn(),
    setReaderPreloadPages: vi.fn(),
    setReaderScreenOrientation: vi.fn(),
    setReaderVolumeNavigation: vi.fn(),
  },
}))

import ReaderSettingsPanel from '@/components/reader/ReaderSettingsPanel.vue'

describe('ReaderSettingsPanel Desktop 宿主能力', () => {
  test('明确展示不可用原因并禁用对应设置', () => {
    const wrapper = mount(ReaderSettingsPanel, { props: { isVertical: true } })

    expect(wrapper.text()).toContain('桌面显示器不支持应用级屏幕方向控制')
    expect(wrapper.text()).toContain('桌面浏览器无法调整系统屏幕亮度')
    expect(wrapper.text()).toContain('当前浏览器不支持屏幕常亮')
    expect(wrapper.text()).toContain('当前浏览器不支持页面全屏')
    expect(wrapper.text()).toContain('桌面浏览器无法可靠拦截系统音量键')
    expect(wrapper.text()).toContain('离开阅读页时自动恢复宿主状态')

    const orientationRow = wrapper
      .findAll('.setting-row')
      .find((candidate) => candidate.text().includes('屏幕方向'))
    expect(
      orientationRow
        ?.findAll('button')
        .every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)

    const unavailableToggles = wrapper.findAll('.capability-unavailable .ion-toggle-stub')
    expect(unavailableToggles).toHaveLength(3)
    expect(unavailableToggles.every((toggle) => toggle.attributes('disabled') !== undefined)).toBe(
      true,
    )
  })
})

test.each([true, false])('桌面宽度实时变更和独立重置，纵向=%s', async (isVertical) => {
  const { SettingsStore } = await import('@/services/SettingsService')
  const wrapper = mount(ReaderSettingsPanel, {
    props: { isVertical, showWidthControl: true, widthPercent: null },
  })
  expect(wrapper.text()).toContain('自动适配')
  const slider = wrapper.get('input[aria-label="默认宽度"]')
  await slider.setValue('100')
  expect(wrapper.emitted('update:width-percent')?.at(-1)).toEqual([100])
  expect(SettingsStore.setReaderWidthPercent).toHaveBeenLastCalledWith(100)
  await slider.setValue('0')
  expect(wrapper.emitted('update:width-percent')?.at(-1)).toEqual([0])
  await wrapper.setProps({ widthPercent: 0 })
  expect(wrapper.text()).toContain('0%')
  const reset = wrapper.findAll('button').find((button) => button.text() === '重置为自动适配')!
  await reset.trigger('click')
  expect(wrapper.emitted('update:width-percent')?.at(-1)).toEqual([null])
  expect(SettingsStore.setReaderWidthPercent).toHaveBeenLastCalledWith(null)
  wrapper.unmount()
})

test('非桌面阅读设置隐藏宽度控件', () => {
  const wrapper = mount(ReaderSettingsPanel, { props: { isVertical: true } })
  expect(wrapper.find('input[type="range"]').exists()).toBe(false)
  wrapper.unmount()
})
