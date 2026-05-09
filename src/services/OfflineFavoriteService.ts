import type {SearchResultItem} from './JmcomicTypes'

export interface OfflineFolder {
  id: string
  name: string
  items: SearchResultItem[]
}

const STORAGE_KEY = 'jq-offline-favorites'

const readFolders = (): OfflineFolder[] => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : []
  } catch {
    return []
  }
}

const writeFolders = (folders: OfflineFolder[]) => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(folders))
}

let nextId = 1

export const OfflineFavoriteService = {
  /** 获取所有离线文件夹列表 */
  getFolders(): { id: string; name: string; count: number }[] {
    return readFolders().map(f => ({ id: f.id, name: f.name, count: f.items.length }))
  },

  /** 创建新文件夹 */
  createFolder(name: string) {
    const folders = readFolders()
    const id = `offline_${Date.now()}_${nextId++}`
    folders.push({ id, name, items: [] })
    writeFolders(folders)
    return id
  },

  /** 删除文件夹 */
  deleteFolder(id: string) {
    const folders = readFolders().filter(f => f.id !== id)
    writeFolders(folders)
  },

  /** 添加项目到文件夹 */
  addItem(folderId: string, item: SearchResultItem) {
    const folders = readFolders()
    const folder = folders.find(f => f.id === folderId)
    if (!folder) return
    if (folder.items.some(i => i.id === item.id)) return
    folder.items.push(item)
    writeFolders(folders)
  },

  /** 移除项目 */
  removeItem(folderId: string, itemId: string) {
    const folders = readFolders()
    const folder = folders.find(f => f.id === folderId)
    if (!folder) return
    folder.items = folder.items.filter(i => i.id !== itemId)
    writeFolders(folders)
  },

  /** 获取文件夹内容（支持关键词搜索） */
  getItems(
    folderId: string,
    keyword?: string,
    page: number = 1,
    pageSize: number = 20,
  ): { totalItems: number; totalPages: number; currentPage: number; content: SearchResultItem[] } {
    const folders = readFolders()
    const folder = folders.find(f => f.id === folderId)
    const items = folder?.items ?? []
    const filtered = keyword
      ? items.filter(i => i.title.toLowerCase().includes(keyword.toLowerCase()))
      : items
    const totalItems = filtered.length
    const totalPages = Math.max(1, Math.ceil(totalItems / pageSize))
    const currentPage = Math.min(Math.max(1, page), totalPages)
    const start = (currentPage - 1) * pageSize
    return {
      totalItems,
      totalPages,
      currentPage,
      content: filtered.slice(start, start + pageSize),
    }
  },
}
