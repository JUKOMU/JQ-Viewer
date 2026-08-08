import { describe, expect, test } from 'vitest'
import type { ImageCacheEntry } from '@/services/JmcomicTypes'
import { buildImageCacheGroups } from '@/utils/imageCacheView'

const entry = (
  photoId: string,
  sortOrder: number,
  type: ImageCacheEntry['type'],
  sizeBytes: number,
): ImageCacheEntry => ({ photoId, sortOrder, type, sizeBytes, mimeType: 'image/jpeg' })

describe('buildImageCacheGroups', () => {
  test('按数字 ID 和页码升序分组，并在缺口处插入一个占位格', () => {
    const groups = buildImageCacheGroups([
      entry('20', 49, 'image', 30),
      entry('2', 2, 'image', 10),
      entry('20', 1, 'image', 20),
      entry('20', 50, 'thumb', 5),
    ])

    expect(groups.map((group) => group.photoId)).toEqual(['2', '20'])
    expect(groups[1].pages.map((page) => page.sortOrder)).toEqual([1, 49, 50])
    expect(groups[1].items.map((item) => item.gap)).toEqual([false, true, false, false])
    expect(groups[1].sizeBytes).toBe(55)
  })

  test('同一页合并原图和缩略图，并保留两份大小', () => {
    const [group] = buildImageCacheGroups([entry('20', 1, 'thumb', 5), entry('20', 1, 'image', 30)])

    expect(group.pages).toHaveLength(1)
    expect(group.pages[0].full?.type).toBe('image')
    expect(group.pages[0].small?.type).toBe('thumb')
    expect(group.sizeBytes).toBe(35)
  })
})
