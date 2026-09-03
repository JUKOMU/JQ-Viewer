import { ref } from 'vue'
import type { SearchResult, SearchResultItem } from '@/services/JmcomicTypes'

export interface FavoritePageCacheState {
  accountId: string
  keyword: string
  stale: boolean
  folderSource: 'online' | 'offline'
  currentFolderId: string
  onlineFolderMap: Record<string, string>
  onlineFolderCounts: Record<string, number>
  resultMeta: SearchResult
  pageCache: Record<number, SearchResultItem[]>
  displayMode: 'list' | 'grid'
}

/** 模块级缓存——组件销毁重建后仍保留 */
export const cachedState = ref<FavoritePageCacheState | null>(null)

/** 标记其他页面的收藏变更会影响已保存的收藏页快照。 */
export function invalidateFavoritePageCache() {
  if (cachedState.value) cachedState.value = { ...cachedState.value, stale: true }
}

/** 清除缓存（切换收藏夹、登出时调用） */
export function clearFavoritePageCache() {
  cachedState.value = null
}
