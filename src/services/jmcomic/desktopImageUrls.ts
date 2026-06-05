import type {ImageInfo} from '../JmcomicTypes'
import {isDesktopRuntime} from '../../platform/runtime'

const imageUrls = new Map<string, string>()

// Stage 3 temporary bridge: keep desktop online reading usable before the
// token-protected /image and /thumb resource endpoints are implemented in stage 4.
export function rememberDesktopImageUrls(
  photoId: string,
  images: ImageInfo[],
  type: 'image' | 'thumb',
) {
  if (!isDesktopRuntime) return
  for (const image of images) {
    const url = image.queryParams ? `${image.url}?${image.queryParams}` : image.url
    imageUrls.set(key(photoId, image.sortOrder, type), url)
  }
}

export function getRememberedDesktopImageUrl(
  photoId: string,
  sortOrder: number,
  type: 'image' | 'thumb',
): string | null {
  if (!isDesktopRuntime) return null
  return imageUrls.get(key(photoId, sortOrder, type)) ?? null
}

function key(photoId: string, sortOrder: number, type: 'image' | 'thumb') {
  return `${type}:${photoId}:${sortOrder}`
}
