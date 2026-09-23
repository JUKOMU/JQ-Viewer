/* eslint-disable vue/one-component-per-file -- test-only component stubs */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  resetExportFormat: vi.fn(),
  showToast: vi.fn(),
  routerPush: vi.fn(),
  runtimePlatform: 'android' as 'android' | 'windows',
  getDownloadPublic: vi.fn(),
  setDownloadPublic: vi.fn(),
  requestManageStorage: vi.fn(),
  readerCapabilities: {
    orientation: { available: true, api: {} },
    brightness: { available: true, api: {} },
    keepAwake: { available: true, api: {} },
    fullscreen: { available: true, api: {} },
    volumeKeys: { available: true, api: {} },
    hostState: { available: true, api: {} },
  } as Record<string, { available: boolean; api?: object; reason?: string }>,
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.routerPush }),
}))

vi.mock('@capacitor/app', () => ({
  App: { getInfo: vi.fn().mockResolvedValue({ version: '1.2.0' }) },
}))

vi.mock('@ionic/vue', () => {
  const withSlot = (name: string, tag = 'div') =>
    defineComponent({
      name,
      setup(_, { slots }) {
        return () => h(tag, slots.default?.())
      },
    })

  return {
    alertController: { create: vi.fn() },
    IonContent: withSlot('IonContent', 'main'),
    IonHeader: withSlot('IonHeader', 'header'),
    IonIcon: withSlot('IonIcon', 'span'),
    IonPage: withSlot('IonPage'),
    IonRange: withSlot('IonRange'),
    IonToggle: withSlot('IonToggle'),
    IonToolbar: withSlot('IonToolbar'),
  }
})

vi.mock('ionicons/icons', () => ({ chevronForwardOutline: 'chevron-forward' }))

vi.mock('@/components/common/MenuToggleButton.vue', () => ({
  default: defineComponent({ name: 'MenuToggleButton', render: () => h('button') }),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getCacheCapacityInfo: vi.fn().mockResolvedValue({ capacityMb: 512, usedMb: 0 }),
    getExternalStoragePath: vi.fn().mockResolvedValue({ path: '/storage/emulated/0/' }),
    getDownloadPublic: mocks.getDownloadPublic,
    setDownloadPublic: mocks.setDownloadPublic,
    requestManageStorage: mocks.requestManageStorage,
    addRelocationProgressListener: vi.fn().mockResolvedValue({ remove: vi.fn() }),
  },
  sanitizeError: vi.fn((_: unknown, fallback: string) => fallback),
  showToast: mocks.showToast,
}))

vi.mock('@/runtime/runtimeContext', () => ({
  getRuntime: () => ({
    platform: mocks.runtimePlatform,
    services: {
      app: { getInfo: vi.fn().mockResolvedValue({ version: '1.2.0' }) },
      storage: { available: true, api: {} },
      reader: mocks.readerCapabilities,
    },
  }),
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

vi.mock('@/services/ExportService', () => ({
  EXPORT_SAMPLE_DATA: {},
  ExportService: {
    getExportPath: vi.fn(() => '/exports/'),
    getDirTemplate: vi.fn(() => '{id}'),
    getNameTemplate: vi.fn(() => '{title}'),
    previewPath: vi.fn(() => '/exports/'),
    renderTemplate: vi.fn(() => ''),
    ensureAbsolutePath: vi.fn(),
  },
}))

vi.mock('@/composables/useAuth', () => ({
  useAuth: () => ({ userInfo: null }),
}))

import SettingPage from '@/views/SettingPage.vue'

beforeEach(() => {
  vi.clearAllMocks()
  mocks.runtimePlatform = 'android'
  Object.assign(mocks.readerCapabilities, {
    orientation: { available: true, api: {} },
    brightness: { available: true, api: {} },
    keepAwake: { available: true, api: {} },
    fullscreen: { available: true, api: {} },
    volumeKeys: { available: true, api: {} },
    hostState: { available: true, api: {} },
  })
  mocks.showToast.mockResolvedValue(undefined)
  mocks.getDownloadPublic.mockResolvedValue({ downloadPublic: false })
  mocks.setDownloadPublic.mockResolvedValue({
    success: true,
    downloadPublic: true,
    moved: 0,
  })
  mocks.requestManageStorage.mockResolvedValue({
    granted: true,
    permissionType: 'MANAGE_EXTERNAL_STORAGE',
    apiLevel: 35,
  })
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

  test('Desktop 使用下载位置语义且不请求 Android 存储权限', async () => {
    mocks.runtimePlatform = 'windows'
    mocks.getDownloadPublic.mockResolvedValue({
      downloadPublic: false,
      displayPath: 'C:\\Users\\Alice\\Downloads',
    })
    mocks.setDownloadPublic.mockResolvedValue({
      success: true,
      downloadPublic: true,
      moved: 2,
      displayPath: 'D:\\Comics',
    })

    const wrapper = mount(SettingPage)
    await flushPromises()

    expect(wrapper.text()).toContain('自定义下载位置')
    expect(wrapper.text()).toContain('开启后选择上级文件夹')

    const row = wrapper
      .findAll('.row')
      .find((candidate) => candidate.text().includes('自定义下载位置'))
    expect(row).toBeDefined()
    row?.findComponent({ name: 'IonToggle' }).vm.$emit('ion-change', {
      detail: { checked: true },
    })
    await flushPromises()

    expect(mocks.requestManageStorage).not.toHaveBeenCalled()
    expect(mocks.setDownloadPublic).toHaveBeenCalledWith(true)
    expect(mocks.showToast).toHaveBeenCalledWith(
      '已迁移 2 个文件，下载位置：D:\\Comics',
      'success',
    )
    wrapper.unmount()
  })

  test('Desktop 下载位置已切换但旧目录待清理时显示恢复提示', async () => {
    mocks.runtimePlatform = 'windows'
    mocks.setDownloadPublic.mockResolvedValue({
      success: true,
      downloadPublic: true,
      moved: 2,
      displayPath: 'D:\\Comics',
      cleanupPending: true,
      cleanupMessage: '下载位置已切换，旧目录将在下次启动时重试清理',
    })

    const wrapper = mount(SettingPage)
    await flushPromises()
    const row = wrapper
      .findAll('.row')
      .find((candidate) => candidate.text().includes('自定义下载位置'))
    row?.findComponent({ name: 'IonToggle' }).vm.$emit('ion-change', {
      detail: { checked: true },
    })
    await flushPromises()

    expect(mocks.showToast).toHaveBeenCalledWith(
      '下载位置已切换，旧目录将在下次启动时重试清理',
      'medium',
    )
    wrapper.unmount()
  })

  test('Desktop 明确展示并禁用没有合理宿主语义的阅读器设置', async () => {
    mocks.runtimePlatform = 'windows'
    Object.assign(mocks.readerCapabilities, {
      orientation: { available: false, reason: '桌面显示器不支持应用级屏幕方向控制' },
      brightness: { available: false, reason: '桌面浏览器无法调整系统屏幕亮度' },
      volumeKeys: { available: false, reason: '桌面浏览器无法可靠拦截系统音量键' },
    })

    const wrapper = mount(SettingPage)
    await flushPromises()

    expect(wrapper.text()).toContain('桌面显示器不支持应用级屏幕方向控制')
    expect(wrapper.text()).toContain('桌面浏览器无法调整系统屏幕亮度')
    expect(wrapper.text()).toContain('桌面浏览器无法可靠拦截系统音量键')
    const orientationRow = wrapper
      .findAll('.row')
      .find((candidate) => candidate.text().includes('屏幕方向'))
    expect(
      orientationRow
        ?.findAll('button')
        .every((button) => button.attributes('disabled') !== undefined),
    ).toBe(true)
    wrapper.unmount()
  })
})
