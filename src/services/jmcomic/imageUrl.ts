import {isDesktopRuntime} from '../../platform/runtime'
import {desktopResourceUrl} from './http'

/** 虚拟 URL 基地址，与 Android 侧 ImageRegistry.VIRTUAL_HOST 一致 */
const VIRTUAL_BASE = 'https://jqviewer.local'

/** 根据 photoId、sortOrder、type 构建虚拟图片 URL */
export function getImageUrl(
  photoId: string,
  sortOrder: number,
  type: 'image' | 'thumb' = 'image',
): string {
  if (isDesktopRuntime) {
    return desktopResourceUrl(`/${type}/${encodeURIComponent(photoId)}/${sortOrder}`)
  }
  return `${VIRTUAL_BASE}/${type}/${photoId}/${sortOrder}`
}
