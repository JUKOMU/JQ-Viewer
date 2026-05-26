import { ref } from 'vue'
import type { SearchResultItem } from './JmcomicTypes'
import { JmcomicService } from './JmcomicService'

export interface OfflineFolder {
  id: string
  name: string
  items: SearchResultItem[]
}

/** 响应式缓存（供 FavoritePage computed 同步读取） */
export const offlineFolderCache = ref<{ id: string; name: string; count: number }[]>([])
export const offlineTotalCount = ref(0)

let initPromise: Promise<void> | null = null

async function ensureInit() {
  if (initPromise) return initPromise
  initPromise = refreshCache()
  return initPromise
}

export async function refreshOfflineCache() {
  await refreshCache()
}

async function refreshCache() {
  try {
    const result = await JmcomicService.getOfflineFolders()
    offlineFolderCache.value = result.folders.map(f => ({
      id: f.folderId,
      name: f.name,
      count: f.count,
    }))
    const tc = await JmcomicService.getOfflineFavoritesTotalCount()
    offlineTotalCount.value = tc.count
  } catch { /* offline db not available */ }
}

export const OfflineFavoriteService = {
  ensureInit,
  refreshCache,

  /** 获取所有离线文件夹列表（从缓存同步读取） */
  getFolders(): { id: string; name: string; count: number }[] {
    return offlineFolderCache.value
  },

  /** 获取全部离线项总数（跨所有文件夹去重，从缓存同步读取） */
  getTotalCount(): number {
    return offlineTotalCount.value
  },

  /** 创建新文件夹 */
  async createFolder(name: string): Promise<string> {
    const result = await JmcomicService.createOfflineFolder(name)
    await refreshCache()
    return result.folderId
  },

  /** 重命名文件夹 */
  async renameFolder(id: string, newName: string): Promise<void> {
    await JmcomicService.renameOfflineFolder(id, newName)
    await refreshCache()
  },

  /** 删除文件夹 */
  async deleteFolder(id: string): Promise<void> {
    await JmcomicService.deleteOfflineFolder(id)
    await refreshCache()
  },

  /** 添加项目到文件夹 */
  async addItem(folderId: string, item: SearchResultItem): Promise<void> {
    await JmcomicService.addOfflineFavorite(folderId, item)
    await refreshCache()
  },

  /** 移除项目 */
  async removeItem(folderId: string, itemId: string): Promise<void> {
    await JmcomicService.removeOfflineFavorite(folderId, itemId)
    await refreshCache()
  },

  /** 获取文件夹内容（支持关键词搜索，分页） */
  async getItems(
    folderId: string,
    keyword?: string,
    page: number = 1,
    pageSize: number = 20,
  ): Promise<{ totalItems: number; totalPages: number; currentPage: number; content: SearchResultItem[] }> {
    return JmcomicService.getOfflineFavorites(folderId, keyword, page, pageSize)
  },

  /** 获取文件夹全部项目（不分页） */
  async getAllItems(folderId: string): Promise<SearchResultItem[]> {
    const result = await JmcomicService.getAllOfflineFavorites(folderId)
    return result.items
  },

  /** 获取全部离线项（跨所有文件夹，按 id 去重） */
  async getAllItemsMerged(): Promise<SearchResultItem[]> {
    const result = await JmcomicService.getAllOfflineFavoritesMerged()
    return result.items
  },

  /** 复制文件夹：创建新文件夹并复制全部项目，返回新文件夹 ID */
  async copyFolder(sourceId: string, targetName: string): Promise<string> {
    const result = await JmcomicService.copyOfflineFolder(sourceId, targetName)
    await refreshCache()
    return result.folderId
  },

  /** 移动全部项从源夹到目标夹 */
  async moveAllItems(sourceId: string, targetId: string): Promise<void> {
    await JmcomicService.moveAllOfflineFavorites(sourceId, targetId)
    await refreshCache()
  },

  /** 批量添加项到文件夹 */
  async addItems(folderId: string, items: SearchResultItem[]): Promise<void> {
    await JmcomicService.addOfflineFavoritesBatch(folderId, items)
    await refreshCache()
  },

  /** 将全部离线项合并到目标文件夹（去重），其余文件夹清空 */
  async mergeAllToFolder(targetId: string): Promise<boolean> {
    const result = await JmcomicService.mergeOfflineAllToFolder(targetId)
    if (result.success) await refreshCache()
    return result.success
  },

  // --- 容灾备份 ---

  async saveBackup(key: string, items: SearchResultItem[]): Promise<void> {
    await JmcomicService.saveOfflineBackup(key, items)
  },

  async loadBackup(key: string): Promise<SearchResultItem[] | null> {
    const result = await JmcomicService.loadOfflineBackup(key)
    return result.items
  },

  async deleteBackup(key: string): Promise<void> {
    await JmcomicService.deleteOfflineBackup(key)
  },

  async listBackupKeys(): Promise<string[]> {
    const result = await JmcomicService.listOfflineBackupKeys()
    return result.keys
  },
}
