import { getRuntime } from '@/runtime/runtimeContext'

/** 根据 photoId、sortOrder、type 构建虚拟图片 URL */
export function getImageUrl(
  photoId: string,
  sortOrder: number,
  type: 'image' | 'thumb' = 'image',
): string {
  return getRuntime().resources.imageUrl({ photoId, sortOrder, type })
}
