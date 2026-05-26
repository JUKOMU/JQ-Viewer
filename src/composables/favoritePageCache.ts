import { ref } from 'vue'
import type { SearchResult, SearchResultItem } from '@/services/JmcomicTypes'

export interface FavoritePageCacheState {
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
