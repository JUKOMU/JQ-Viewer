import { afterEach, describe, expect, test, vi } from 'vitest'
import { createFacadeClient } from '@/runtime/facadeClient'
import { RuntimeError } from '@/runtime/errors'
import type { FrontendRuntime } from '@/runtime/FrontendRuntime'
import type { NetworkProbeEvent } from '@/services/JmcomicTypes'

describe('facadeClient network retry', () => {
  afterEach(() => vi.useRealTimers())

  test('等待线路恢复事件后仅重试一次安全读取', async () => {
    const search = vi.fn()
      .mockRejectedValueOnce(new RuntimeError('network', 'offline'))
      .mockResolvedValueOnce({ items: [] })
    const remove = vi.fn().mockResolvedValue(undefined)
    const runtime = {
      backend: { search },
      events: {
        onNetworkProbe: vi.fn(async (handler: (event: NetworkProbeEvent) => void) => {
          handler({
            phase: 'network_restored',
            message: 'restored',
            timestamp: Date.now(),
          })
          return { remove }
        }),
      },
      services: { reader: {}, localFiles: {} },
    } as unknown as FrontendRuntime

    const client = createFacadeClient(runtime)
    await expect(client.search({ query: {} } as never)).resolves.toEqual({ items: [] })

    expect(search).toHaveBeenCalledTimes(2)
    expect(remove).toHaveBeenCalledOnce()
  })

  test('不重放写操作', async () => {
    const toggleAlbumFavorite = vi.fn()
      .mockRejectedValue(new RuntimeError('network', 'offline'))
    const runtime = {
      backend: { toggleAlbumFavorite },
      events: { onNetworkProbe: vi.fn() },
      services: { reader: {}, localFiles: {} },
    } as unknown as FrontendRuntime

    await expect(createFacadeClient(runtime).toggleAlbumFavorite({ id: '1', folderId: '0' }))
      .rejects.toThrow('offline')
    expect(toggleAlbumFavorite).toHaveBeenCalledOnce()
  })

  test('不重放需认证读取', async () => {
    const getFavorites = vi.fn().mockRejectedValue(new RuntimeError('network', 'offline'))
    const runtime = {
      backend: { getFavorites },
      events: { onNetworkProbe: vi.fn() },
      services: { reader: {}, localFiles: {} },
    } as unknown as FrontendRuntime

    await expect(createFacadeClient(runtime).getFavorites({ query: {} } as never))
      .rejects.toThrow('offline')
    expect(getFavorites).toHaveBeenCalledOnce()
  })

  test('不重放可能已生效的图片重试、下载和替换型预载', async () => {
    const retryImage = vi.fn().mockRejectedValue(new RuntimeError('network', 'offline'))
    const downloadChapter = vi.fn().mockRejectedValue(new RuntimeError('network', 'offline'))
    const preloadImages = vi.fn().mockRejectedValue(new RuntimeError('network', 'offline'))
    const runtime = {
      backend: { retryImage, downloadChapter, preloadImages },
      events: { onNetworkProbe: vi.fn() },
      services: { reader: {}, localFiles: {} },
    } as unknown as FrontendRuntime
    const client = createFacadeClient(runtime)

    await expect(client.retryImage({ photoId: 'p1', image: {} } as never)).rejects.toThrow('offline')
    await expect(client.downloadChapter({ albumId: 'a1', chapterId: 'c1' } as never))
      .rejects.toThrow('offline')
    await expect(client.preloadImages({ photoId: 'p1', images: [], type: 'image', replacePending: true }))
      .rejects.toThrow('offline')

    expect(retryImage).toHaveBeenCalledOnce()
    expect(downloadChapter).toHaveBeenCalledOnce()
    expect(preloadImages).toHaveBeenCalledOnce()
  })

  test('安全读取最多等待五秒且超时后保留原错误', async () => {
    vi.useFakeTimers()
    const search = vi.fn().mockRejectedValue(new RuntimeError('network', 'offline'))
    const runtime = {
      backend: { search, getDomainStates: vi.fn().mockRejectedValue(new Error('offline')) },
      events: { onNetworkProbe: vi.fn(() => new Promise(() => undefined)) },
      services: { reader: {}, localFiles: {} },
    } as unknown as FrontendRuntime

    const result = createFacadeClient(runtime).search({ query: {} } as never).catch((error) => error)
    await vi.advanceTimersByTimeAsync(5_000)

    await expect(result).resolves.toMatchObject({ code: 'network', message: 'offline' })
    expect(search).toHaveBeenCalledOnce()
  })
})
