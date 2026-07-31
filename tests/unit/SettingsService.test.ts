import {describe, expect, test, vi} from 'vitest'
import type {AllSettings} from '@/services/JmcomicTypes'

const getAllSettings = vi.hoisted(() => vi.fn())

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {getAllSettings},
}))

const legacySettings: AllSettings = {
  readerPreloadPages: 15,
  preloadConcurrency: 6,
  downloadConcurrency: 6,
  downloadPublic: false,
  cacheCapacityMb: 256,
  ocrEnabled: true,
  readerDisplayMode: 'vertical',
  readerScreenOrientation: 'auto',
  readerBrightness: -1,
  readerKeepScreenOn: true,
  readerVolumeNavigation: false,
}

describe('SettingsService', () => {
  test('旧版设置缺少阅读结束工具栏字段时默认开启', async () => {
    getAllSettings.mockResolvedValue(legacySettings)

    const {initSettings, SettingsStore} = await import('@/services/SettingsService')
    await initSettings()

    expect(SettingsStore.getReaderAutoShowToolbarAtEnd()).toBe(true)
    SettingsStore.setReaderAutoShowToolbarAtEnd(false)
    expect(SettingsStore.getReaderAutoShowToolbarAtEnd()).toBe(false)
  })
})
