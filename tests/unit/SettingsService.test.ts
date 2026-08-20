import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { AllSettings } from '@/services/JmcomicTypes'

const getAllSettings = vi.hoisted(() => vi.fn())
const setPreloadConcurrency = vi.hoisted(() => vi.fn())
const setDownloadConcurrency = vi.hoisted(() => vi.fn())

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getAllSettings,
    setPreloadConcurrency,
    setDownloadConcurrency,
  },
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
  beforeEach(() => {
    vi.resetModules()
    getAllSettings.mockReset()
    setPreloadConcurrency.mockReset()
    setDownloadConcurrency.mockReset()
  })

  test('旧版设置缺少阅读结束工具栏字段时默认开启', async () => {
    getAllSettings.mockResolvedValue(legacySettings)

    const { initSettings, SettingsStore } = await import('@/services/SettingsService')
    await initSettings()

    expect(SettingsStore.getReaderAutoShowToolbarAtEnd()).toBe(true)
    expect(SettingsStore.getCacheCapacityMb()).toBe(256)
    SettingsStore.setReaderAutoShowToolbarAtEnd(false)
    expect(SettingsStore.getReaderAutoShowToolbarAtEnd()).toBe(false)
  })

  test('新原生返回 requested 和 effective 时缓存用户设置值', async () => {
    getAllSettings.mockResolvedValue({
      ...legacySettings,
      cacheCapacityMb: 332,
      cacheRequestedMb: 1024,
      cacheEffectiveMb: 332,
    })

    const { initSettings, SettingsStore } = await import('@/services/SettingsService')
    await initSettings()

    expect(SettingsStore.getCacheCapacityMb()).toBe(1024)
  })

  test('预加载并发持久化失败时回滚内存缓存', async () => {
    getAllSettings.mockResolvedValue(legacySettings)
    setPreloadConcurrency.mockRejectedValue(new Error('native failure'))

    const { initSettings, SettingsStore, persistPreloadConcurrency } =
      await import('@/services/SettingsService')
    await initSettings()

    await expect(persistPreloadConcurrency(9)).rejects.toThrow('native failure')
    expect(SettingsStore.getPreloadConcurrency()).toBe(6)
  })

  test('下载并发持久化失败时回滚内存缓存', async () => {
    getAllSettings.mockResolvedValue(legacySettings)
    setDownloadConcurrency.mockResolvedValue({ success: false })

    const { initSettings, SettingsStore, persistDownloadConcurrency } =
      await import('@/services/SettingsService')
    await initSettings()

    await expect(persistDownloadConcurrency(10)).rejects.toThrow('保存失败')
    expect(SettingsStore.getDownloadConcurrency()).toBe(6)
  })

  test('预加载并发连续保存均失败时回滚到最后确认值', async () => {
    getAllSettings.mockResolvedValue(legacySettings)
    const first = deferred<{ success: boolean }>()
    const second = deferred<{ success: boolean }>()
    setPreloadConcurrency.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    const { initSettings, SettingsStore, persistPreloadConcurrency } =
      await import('@/services/SettingsService')
    await initSettings()

    const firstSave = persistPreloadConcurrency(8)
    const secondSave = persistPreloadConcurrency(10)
    const firstFailure = expect(firstSave).rejects.toThrow('first failure')
    const secondFailure = expect(secondSave).rejects.toThrow('second failure')

    await vi.waitFor(() => expect(setPreloadConcurrency).toHaveBeenCalledTimes(1))
    first.reject(new Error('first failure'))
    await firstFailure
    await vi.waitFor(() => expect(setPreloadConcurrency).toHaveBeenCalledTimes(2))
    second.reject(new Error('second failure'))
    await secondFailure

    expect(setPreloadConcurrency.mock.calls).toEqual([[8], [10]])
    expect(SettingsStore.getPreloadConcurrency()).toBe(6)
  })

  test('预加载并发前一笔成功后一笔失败时回滚到前一笔', async () => {
    getAllSettings.mockResolvedValue(legacySettings)
    const first = deferred<{ success: boolean }>()
    const second = deferred<{ success: boolean }>()
    setPreloadConcurrency.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    const { initSettings, SettingsStore, persistPreloadConcurrency } =
      await import('@/services/SettingsService')
    await initSettings()

    const firstSave = persistPreloadConcurrency(8)
    const secondSave = persistPreloadConcurrency(10)
    const secondFailure = expect(secondSave).rejects.toThrow('保存失败')

    await vi.waitFor(() => expect(setPreloadConcurrency).toHaveBeenCalledTimes(1))
    first.resolve({ success: true })
    await firstSave
    await vi.waitFor(() => expect(setPreloadConcurrency).toHaveBeenCalledTimes(2))
    second.resolve({ success: false })
    await secondFailure

    expect(SettingsStore.getPreloadConcurrency()).toBe(8)
  })

  test('下载并发前一笔失败后一笔成功时保留后一笔', async () => {
    getAllSettings.mockResolvedValue(legacySettings)
    const first = deferred<{ success: boolean }>()
    const second = deferred<{ success: boolean }>()
    setDownloadConcurrency.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)

    const { initSettings, SettingsStore, persistDownloadConcurrency } =
      await import('@/services/SettingsService')
    await initSettings()

    const firstSave = persistDownloadConcurrency(8)
    const secondSave = persistDownloadConcurrency(10)
    const firstFailure = expect(firstSave).rejects.toThrow('first failure')

    await vi.waitFor(() => expect(setDownloadConcurrency).toHaveBeenCalledTimes(1))
    first.reject(new Error('first failure'))
    await firstFailure
    await vi.waitFor(() => expect(setDownloadConcurrency).toHaveBeenCalledTimes(2))
    second.resolve({ success: true })
    await secondSave

    expect(SettingsStore.getDownloadConcurrency()).toBe(10)
  })
})
