import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  addListener: vi.fn(),
  retryImage: vi.fn(),
  getBrowseHistory: vi.fn(),
  getBrowseHistoryOverview: vi.fn(),
  toggleAlbumFavorite: vi.fn(),
  manageFavoriteFolder: vi.fn(),
}))

vi.mock('@/services/jmcomic/JmcomicNativeClient', () => ({
  jmcomicNativeClient: {
    addListener: mocks.addListener,
    retryImage: mocks.retryImage,
    getBrowseHistory: mocks.getBrowseHistory,
    getBrowseHistoryOverview: mocks.getBrowseHistoryOverview,
    toggleAlbumFavorite: mocks.toggleAlbumFavorite,
    manageFavoriteFolder: mocks.manageFavoriteFolder,
  },
}))

import type { ImageReadyEvent } from '@/services/jmcomic/JmcomicClient'
import { JmcomicService } from '@/services/jmcomic/JmcomicServiceFacade'

describe('JmcomicService.addImageReadyListener', () => {
  let imageReadyHandler: ((event: ImageReadyEvent) => void) | null

  beforeEach(() => {
    imageReadyHandler = null
    mocks.addListener.mockReset()
    mocks.addListener.mockImplementation(
      (_event: string, handler: (event: ImageReadyEvent) => void) => {
        imageReadyHandler = handler
        return Promise.resolve({ remove: vi.fn(() => Promise.resolve()) })
      },
    )
  })

  test('可按图片类型过滤事件', async () => {
    const handler = vi.fn()
    await JmcomicService.addImageReadyListener('chapter-1', handler, { type: 'image' })

    imageReadyHandler?.({ photoId: 'chapter-1', sortOrder: 1, type: 'thumb' })
    imageReadyHandler?.({ photoId: 'chapter-2', sortOrder: 2, type: 'image' })
    imageReadyHandler?.({ photoId: 'chapter-1', sortOrder: 3, type: 'image' })

    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledWith(3)
  })

  test('未传类型时保持原有兼容行为', async () => {
    const handler = vi.fn()
    await JmcomicService.addImageReadyListener('chapter-1', handler)

    imageReadyHandler?.({ photoId: 'chapter-1', sortOrder: 1, type: 'thumb' })
    imageReadyHandler?.({ photoId: 'chapter-1', sortOrder: 2, type: 'image' })

    expect(handler.mock.calls).toEqual([[1], [2]])
  })
})

describe('JmcomicService.addImageFailedListener', () => {
  test('只转发当前章节指定类型的失败事件', async () => {
    let imageFailedHandler: ((event: ImageReadyEvent) => void) | null = null
    mocks.addListener.mockReset()
    mocks.addListener.mockImplementation(
      (_event: string, handler: (event: ImageReadyEvent) => void) => {
        imageFailedHandler = handler
        return Promise.resolve({ remove: vi.fn(() => Promise.resolve()) })
      },
    )
    const handler = vi.fn()

    await JmcomicService.addImageFailedListener('chapter-1', handler, { type: 'image' })

    imageFailedHandler?.({ photoId: 'chapter-1', sortOrder: 1, type: 'thumb' })
    imageFailedHandler?.({ photoId: 'chapter-2', sortOrder: 2, type: 'image' })
    imageFailedHandler?.({ photoId: 'chapter-1', sortOrder: 3, type: 'image' })

    expect(mocks.addListener).toHaveBeenCalledWith('imageFailed', expect.any(Function))
    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler).toHaveBeenCalledWith(3)
  })
})

describe('JmcomicService.retryImage', () => {
  test('转发最新图片元数据', async () => {
    const image = {
      photoId: 'chapter-1',
      scrambleId: '0',
      filename: '1.jpg',
      url: 'https://latest.example.com/1.jpg',
      queryParams: '',
      sortOrder: 1,
    }
    mocks.retryImage.mockResolvedValueOnce({ success: true })

    await expect(JmcomicService.retryImage('chapter-1', image)).resolves.toEqual({ success: true })
    expect(mocks.retryImage).toHaveBeenCalledWith({ photoId: 'chapter-1', image })
  })
})

describe('JmcomicService.favoriteToFolder', () => {
  beforeEach(() => {
    mocks.toggleAlbumFavorite.mockReset()
    mocks.manageFavoriteFolder.mockReset()
    mocks.toggleAlbumFavorite.mockResolvedValue({ success: true })
    mocks.manageFavoriteFolder.mockResolvedValue({ status: 'ok', msg: '' })
  })

  test('选择具体收藏夹时先收藏再移动', async () => {
    await expect(JmcomicService.favoriteToFolder('album-1', 'folder-1')).resolves.toEqual({
      success: true,
    })

    expect(mocks.toggleAlbumFavorite).toHaveBeenCalledWith({ id: 'album-1', folderId: '0' })
    expect(mocks.manageFavoriteFolder).toHaveBeenCalledWith({
      type: 'move',
      folderId: 'folder-1',
      albumId: 'album-1',
    })
    expect(mocks.toggleAlbumFavorite.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.manageFavoriteFolder.mock.invocationCallOrder[0],
    )
  })

  test('选择全部时只执行收藏', async () => {
    await JmcomicService.favoriteToFolder('album-1', '0')

    expect(mocks.toggleAlbumFavorite).toHaveBeenCalledWith({ id: 'album-1', folderId: '0' })
    expect(mocks.manageFavoriteFolder).not.toHaveBeenCalled()
  })

  test('移动接口返回失败状态时拒绝成功结果', async () => {
    mocks.manageFavoriteFolder.mockResolvedValue({ status: 'fail', msg: '移动失败' })

    await expect(JmcomicService.favoriteToFolder('album-1', 'folder-1')).rejects.toThrow('移动失败')
  })
})

describe('JmcomicService 浏览历史契约', () => {
  test('透传可选时间范围，并保留无范围分页参数', async () => {
    const range = {
      key: 'today' as const,
      startInclusive: 100,
      endExclusive: null,
    }
    mocks.getBrowseHistory.mockResolvedValue({ items: [], totalCount: 0 })

    await JmcomicService.getBrowseHistory(50, 0, range)
    expect(mocks.getBrowseHistory).toHaveBeenCalledWith({
      limit: 50,
      offset: 0,
      startInclusive: 100,
      endExclusive: null,
    })

    await JmcomicService.getBrowseHistory(50, 50)
    expect(mocks.getBrowseHistory).toHaveBeenLastCalledWith({ limit: 50, offset: 50 })
  })

  test('概览调用透传八组范围数组', async () => {
    const ranges = [{ key: 'today' as const, startInclusive: 100, endExclusive: null }]
    mocks.getBrowseHistoryOverview.mockResolvedValue({
      totalCount: 1,
      groupCounts: { today: 1 },
    })

    await JmcomicService.getBrowseHistoryOverview(ranges)

    expect(mocks.getBrowseHistoryOverview).toHaveBeenCalledWith({ ranges })
  })
})
