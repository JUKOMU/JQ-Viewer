import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  runtime: { platform: 'android' as 'android' | 'linux' },
  addListener: vi.fn(),
  getDomainStates: vi.fn(),
  preloadImages: vi.fn(),
  retryImage: vi.fn(),
  getBrowseHistory: vi.fn(),
  getBrowseHistoryOverview: vi.fn(),
  getAlbum: vi.fn(),
  toggleAlbumFavorite: vi.fn(),
  manageFavoriteFolder: vi.fn(),
  downloadChapter: vi.fn(),
  ensureDownloadPermission: vi.fn(),
}))

vi.mock('@/runtime/facadeClient', () => ({
  createActiveFacadeClient: () => ({
    addListener: mocks.addListener,
    getDomainStates: mocks.getDomainStates,
    preloadImages: mocks.preloadImages,
    retryImage: mocks.retryImage,
    getBrowseHistory: mocks.getBrowseHistory,
    getBrowseHistoryOverview: mocks.getBrowseHistoryOverview,
    getAlbum: mocks.getAlbum,
    toggleAlbumFavorite: mocks.toggleAlbumFavorite,
    manageFavoriteFolder: mocks.manageFavoriteFolder,
    downloadChapter: mocks.downloadChapter,
  }),
}))

vi.mock('@/runtime/runtimeContext', () => ({
  getRuntime: () => mocks.runtime,
}))

vi.mock('@/services/NotificationPermissionService', () => ({
  NotificationPermissionService: {
    ensureDownloadPermission: mocks.ensureDownloadPermission,
  },
}))

import type { ImageReadyEvent } from '@/services/jmcomic/JmcomicClient'
import { JmcomicService } from '@/services/jmcomic/JmcomicServiceFacade'

beforeEach(() => {
  mocks.runtime.platform = 'android'
  mocks.preloadImages.mockReset()
})

describe('Desktop 直接图片资源路径', () => {
  test('不安装 Android 预加载方法也能返回待展示图片和空事件句柄', async () => {
    mocks.runtime.platform = 'linux'
    const images = [
      {
        photoId: 'chapter-1',
        scrambleId: '0',
        filename: '1.jpg',
        url: 'https://latest.example.com/1.jpg',
        queryParams: '',
        sortOrder: 1,
      },
      {
        photoId: 'chapter-1',
        scrambleId: '0',
        filename: '2.jpg',
        url: 'https://latest.example.com/2.jpg',
        queryParams: '',
        sortOrder: 2,
      },
    ]

    await expect(JmcomicService.preloadImages('chapter-1', images)).resolves.toEqual({
      cached: [1, 2],
      pending: [],
    })
    await expect(JmcomicService.retryImage('chapter-1', images[0])).resolves.toEqual({
      success: true,
    })
    const listener = await JmcomicService.addImageReadyListener('chapter-1', vi.fn())
    await listener.remove()

    expect(mocks.preloadImages).not.toHaveBeenCalled()
    expect(mocks.retryImage).not.toHaveBeenCalled()
    expect(mocks.addListener).not.toHaveBeenCalled()
  })

  test('将 backend 的同步异常转换为可捕获的 Promise rejection', async () => {
    mocks.getDomainStates.mockImplementation(() => {
      throw new Error('domain state unavailable')
    })

    await expect(JmcomicService.getDomainStates()).rejects.toThrow('domain state unavailable')
  })
})

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
    mocks.getAlbum.mockReset()
    mocks.toggleAlbumFavorite.mockReset()
    mocks.manageFavoriteFolder.mockReset()
    mocks.getAlbum.mockResolvedValue({ isFavorite: false })
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

  test('已收藏时只移动而不再次切换', async () => {
    mocks.getAlbum.mockResolvedValue({ isFavorite: true })

    await JmcomicService.favoriteToFolder('album-1', 'folder-1')

    expect(mocks.toggleAlbumFavorite).not.toHaveBeenCalled()
    expect(mocks.manageFavoriteFolder).toHaveBeenCalledWith({
      type: 'move',
      folderId: 'folder-1',
      albumId: 'album-1',
    })
  })

  test('移动接口返回失败状态时拒绝成功结果', async () => {
    mocks.manageFavoriteFolder.mockResolvedValue({ status: 'fail', msg: '移动失败' })

    await expect(JmcomicService.favoriteToFolder('album-1', 'folder-1')).rejects.toThrow('移动失败')
  })

  test('移动失败但对账确认已收藏时只重试移动', async () => {
    mocks.manageFavoriteFolder
      .mockResolvedValueOnce({ status: 'fail', msg: '暂时失败' })
      .mockResolvedValueOnce({ status: 'ok', msg: '' })
    mocks.getAlbum
      .mockResolvedValueOnce({ isFavorite: false })
      .mockResolvedValueOnce({ isFavorite: true })

    await expect(JmcomicService.favoriteToFolder('album-1', 'folder-1')).resolves.toEqual({
      success: true,
    })

    expect(mocks.toggleAlbumFavorite).toHaveBeenCalledTimes(1)
    expect(mocks.manageFavoriteFolder).toHaveBeenCalledTimes(2)
  })
})

describe('JmcomicService.downloadChapter', () => {
  beforeEach(() => {
    mocks.ensureDownloadPermission.mockReset()
    mocks.downloadChapter.mockReset()
    mocks.ensureDownloadPermission.mockResolvedValue(undefined)
    mocks.downloadChapter.mockResolvedValue({ taskId: 'album-1_chapter-1' })
  })

  test('先完成通知权限工作流，再提交章节下载', async () => {
    await JmcomicService.downloadChapter(
      'album-1',
      'chapter-1',
      '测试漫画',
      '第一话',
      'https://example.test/cover.jpg',
    )

    expect(mocks.ensureDownloadPermission).toHaveBeenCalledOnce()
    expect(mocks.downloadChapter).toHaveBeenCalledWith({
      albumId: 'album-1',
      chapterId: 'chapter-1',
      albumTitle: '测试漫画',
      chapterTitle: '第一话',
      coverUrl: 'https://example.test/cover.jpg',
    })
    expect(mocks.ensureDownloadPermission.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.downloadChapter.mock.invocationCallOrder[0],
    )
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
