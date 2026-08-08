/* eslint-disable vue/one-component-per-file -- test-only component stubs */
import {flushPromises, mount} from '@vue/test-utils'
import {defineComponent, h} from 'vue'
import {afterEach, beforeEach, describe, expect, test, vi} from 'vitest'

const mocks = vi.hoisted(() => ({
  resetExportFormat: vi.fn(),
  showToast: vi.fn(),
  routerPush: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({push: mocks.routerPush}),
}))

vi.mock('@capacitor/app', () => ({
  App: {getInfo: vi.fn().mockResolvedValue({version: '1.2.0'})},
}))

vi.mock('@ionic/vue', () => {
  const withSlot = (name: string, tag = 'div') =>
    defineComponent({
      name,
      setup(_, {slots}) {
        return () => h(tag, slots.default?.())
      },
    })

  return {
    alertController: {create: vi.fn()},
    IonContent: withSlot('IonContent', 'main'),
    IonHeader: withSlot('IonHeader', 'header'),
    IonIcon: withSlot('IonIcon', 'span'),
    IonPage: withSlot('IonPage'),
    IonRange: withSlot('IonRange'),
    IonToggle: withSlot('IonToggle'),
    IonToolbar: withSlot('IonToolbar'),
  }
})

vi.mock('ionicons/icons', () => ({chevronForwardOutline: 'chevron-forward'}))

vi.mock('@/components/common/MenuToggleButton.vue', () => ({
  default: defineComponent({name: 'MenuToggleButton', render: () => h('button')}),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getCacheCapacityInfo: vi.fn().mockResolvedValue({capacityMb: 512, usedMb: 0}),
    getExternalStoragePath: vi.fn().mockResolvedValue({path: '/storage/emulated/0/'}),
  },
  sanitizeError: vi.fn((_: unknown, fallback: string) => fallback),
  showToast: mocks.showToast,
}))

vi.mock('@/services/SettingsService', () => ({
  initSettings: vi.fn().mockResolvedValue(undefined),
  persistDownloadConcurrency: vi.fn().mockResolvedValue(undefined),
  persistPreloadConcurrency: vi.fn().mockResolvedValue(undefined),
  SettingsStore: {
    getCacheCapacityMb: vi.fn(() => 512),
    getDownloadConcurrency: vi.fn(() => 4),
    getDownloadPublic: vi.fn(() => false),
    getOcrEnabled: vi.fn(() => false),
    getPreloadConcurrency: vi.fn(() => 4),
    getReaderAutoShowToolbarAtEnd: vi.fn(() => true),
    getReaderBrightness: vi.fn(() => -1),
    getReaderDisplayMode: vi.fn(() => 'vertical'),
    getReaderKeepScreenOn: vi.fn(() => false),
    getReaderPreloadPages: vi.fn(() => 10),
    getReaderScreenOrientation: vi.fn(() => 'auto'),
    getReaderVolumeNavigation: vi.fn(() => false),
    setCacheCapacityMb: vi.fn(),
    setDownloadPublic: vi.fn(),
    setOcrEnabled: vi.fn(),
    setReaderAutoShowToolbarAtEnd: vi.fn(),
    setReaderBrightness: vi.fn(),
    setReaderDisplayMode: vi.fn(),
    setReaderKeepScreenOn: vi.fn(),
    setReaderPreloadPages: vi.fn(),
    setReaderScreenOrientation: vi.fn(),
    setReaderVolumeNavigation: vi.fn(),
  },
}))

vi.mock('@/services/ExportFormatService', () => ({
  ExportFormatService: {
    getExportFormat: vi.fn(() => '{id}{title}'),
    previewExportFormat: vi.fn(() => 'JM{id}{title}'),
    resetExportFormat: mocks.resetExportFormat,
  },
}))

vi.mock('@/services/PdfExportService', () => ({
  PDF_SAMPLE_DATA: {},
  PdfExportService: {
    getExportPath: vi.fn(() => '/exports/'),
    getDirTemplate: vi.fn(() => '{id}'),
    getNameTemplate: vi.fn(() => '{title}'),
    previewPath: vi.fn(() => '/exports/'),
    renderTemplate: vi.fn(() => ''),
    ensureAbsolutePath: vi.fn(),
  },
}))

vi.mock('@/composables/useAuth', () => ({
  useAuth: () => ({userInfo: null}),
}))

import SettingPage from '@/views/SettingPage.vue'

beforeEach(() => {
  vi.clearAllMocks()
  mocks.showToast.mockResolvedValue(undefined)
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('SettingPage 导出格式重置', () => {
  test('使用可键盘访问的 button 并触发重置', async () => {
    const wrapper = mount(SettingPage)
    await flushPromises()

    const resetButton = wrapper
      .findAll('button')
      .find((button) => button.text().includes('重置为默认'))
    expect(resetButton).toBeDefined()
    expect(resetButton?.attributes('type')).toBe('button')

    await resetButton?.trigger('click')

    expect(mocks.resetExportFormat).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
