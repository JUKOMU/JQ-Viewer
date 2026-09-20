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
