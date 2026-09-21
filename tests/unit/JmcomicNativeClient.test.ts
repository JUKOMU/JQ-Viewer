import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  online: {
    addListener: vi.fn(),
    search: vi.fn(),
  },
  local: {
    addListener: vi.fn(),
    getDownloadedPhoto: vi.fn(),
  },
  registerPlugin: vi.fn(),
}))

vi.mock('@capacitor/core', () => ({
  registerPlugin: mocks.registerPlugin,
}))

describe('JmcomicNativeClient', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocks.registerPlugin.mockImplementation((name: string) =>
      name === 'Jmcomic' ? mocks.online : mocks.local,
    )
  })

  test('将在线 API 和客户端状态事件路由到 Jmcomic 插件', async () => {
    const { jmcomicNativeClient } = await import('@/services/jmcomic/JmcomicNativeClient')
    const query = { query: { keyword: 'keyword' } }
    const listener = vi.fn()

    await jmcomicNativeClient.search(query)
    await jmcomicNativeClient.addListener('clientStateChanged', listener)

    expect(mocks.online.search).toHaveBeenCalledWith(query)
    expect(mocks.online.addListener).toHaveBeenCalledWith('clientStateChanged', listener)
    expect(mocks.local.addListener).not.toHaveBeenCalled()
  })

  test('将已下载内容和本地事件路由到 JqViewer 插件', async () => {
    const { jmcomicNativeClient } = await import('@/services/jmcomic/JmcomicNativeClient')
    const options = { albumId: 'album', chapterId: 'chapter' }
    const listener = vi.fn()

    await jmcomicNativeClient.getDownloadedPhoto(options)
    await jmcomicNativeClient.addListener('downloadProgress', listener)

    expect(mocks.local.getDownloadedPhoto).toHaveBeenCalledWith(options)
    expect(mocks.local.addListener).toHaveBeenCalledWith('downloadProgress', listener)
    expect(mocks.online.addListener).not.toHaveBeenCalled()
  })
})
