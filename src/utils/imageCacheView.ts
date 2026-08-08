import type { ImageCacheEntry } from '@/services/JmcomicTypes'

export interface CachePageView {
  photoId: string
  sortOrder: number
  full?: ImageCacheEntry
  small?: ImageCacheEntry
}

export interface CacheGridItem {
  key: string
  gap: boolean
  page?: CachePageView
}

export interface CacheGroupView {
  photoId: string
  pages: CachePageView[]
  items: CacheGridItem[]
  sizeBytes: number
}

function compareIds(left: string, right: string): number {
  const leftNumber = Number(left)
  const rightNumber = Number(right)
  if (Number.isFinite(leftNumber) && Number.isFinite(rightNumber)) {
    return leftNumber - rightNumber
  }
  return left.localeCompare(right)
}

export function buildImageCacheGroups(entries: ImageCacheEntry[]): CacheGroupView[] {
  const groups = new Map<string, Map<number, CachePageView>>()

  for (const entry of entries) {
    let pages = groups.get(entry.photoId)
    if (!pages) {
      pages = new Map()
      groups.set(entry.photoId, pages)
    }

    let page = pages.get(entry.sortOrder)
    if (!page) {
      page = { photoId: entry.photoId, sortOrder: entry.sortOrder }
      pages.set(entry.sortOrder, page)
    }

    if (entry.type === 'thumb') {
      page.small = entry
    } else {
      page.full = entry
    }
  }

  return [...groups.entries()]
    .sort(([left], [right]) => compareIds(left, right))
    .map(([photoId, pageMap]) => {
      const pages = [...pageMap.values()].sort((left, right) => left.sortOrder - right.sortOrder)
      const items: CacheGridItem[] = []
      let previousSortOrder: number | null = null

      for (const page of pages) {
        if (previousSortOrder !== null && page.sortOrder > previousSortOrder + 1) {
          items.push({
            key: `${photoId}:gap:${previousSortOrder}:${page.sortOrder}`,
            gap: true,
          })
        }
        items.push({ key: `${photoId}:${page.sortOrder}`, gap: false, page })
        previousSortOrder = page.sortOrder
      }

      return {
        photoId,
        pages,
        items,
        sizeBytes: pages.reduce(
          (total, page) => total + (page.full?.sizeBytes ?? 0) + (page.small?.sizeBytes ?? 0),
          0,
        ),
      }
    })
}
