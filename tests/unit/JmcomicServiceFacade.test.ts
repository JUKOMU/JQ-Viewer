import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  addListener: vi.fn(),
}))

vi.mock('@/services/jmcomic/JmcomicNativeClient', () => ({
  jmcomicNativeClient: {
    addListener: mocks.addListener,
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
