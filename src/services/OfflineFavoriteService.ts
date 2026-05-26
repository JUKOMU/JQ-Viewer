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

  /** 重命名文件夹 */
  renameFolder(id: string, newName: string) {
    const folders = readFolders()
    const folder = folders.find(f => f.id === id)
    if (!folder) return
    folder.name = newName
    writeFolders(folders)
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

  /** 获取文件夹全部项目（不分页） */
  getAllItems(folderId: string): SearchResultItem[] {
    const folders = readFolders()
    const folder = folders.find(f => f.id === folderId)
    return folder?.items ?? []
  },

  /** 复制文件夹：创建新文件夹并复制全部项目，返回新文件夹 ID */
  copyFolder(sourceId: string, targetName: string): string {
    const folders = readFolders()
    const source = folders.find(f => f.id === sourceId)
    const id = `offline_${Date.now()}_${nextId++}`
    folders.push({ id, name: targetName, items: source ? [...source.items] : [] })
    writeFolders(folders)
    return id
  },

  /** 获取全部离线项总数（跨所有文件夹去重，直接计数不构建数组） */
  getTotalCount(): number {
    const folders = readFolders()
    const seen = new Set<string>()
    for (const f of folders) {
      for (const item of f.items) {
        seen.add(item.id)
      }
    }
    return seen.size
  },

  /** 获取全部离线项（跨所有文件夹，按 id 去重） */
  getAllItemsMerged(): SearchResultItem[] {
    const folders = readFolders()
    const seen = new Set<string>()
    const merged: SearchResultItem[] = []
    for (const f of folders) {
      for (const item of f.items) {
        if (!seen.has(item.id)) {
          seen.add(item.id)
          merged.push(item)
        }
      }
    }
    return merged
  },

  /** 移动全部项从源夹到目标夹 */
  moveAllItems(sourceId: string, targetId: string): void {
    const folders = readFolders()
    const source = folders.find(f => f.id === sourceId)
    const target = folders.find(f => f.id === targetId)
    if (!source || !target) return
    for (const item of source.items) {
      if (!target.items.some(i => i.id === item.id)) {
        target.items.push(item)
      }
    }
    source.items = []
    writeFolders(folders)
  },

  /** 批量添加项到文件夹 */
  addItems(folderId: string, items: SearchResultItem[]): void {
    const folders = readFolders()
    const folder = folders.find(f => f.id === folderId)
    if (!folder) return
    for (const item of items) {
      if (!folder.items.some(i => i.id === item.id)) {
        folder.items.push(item)
      }
    }
    writeFolders(folders)
  },

  // --- 容灾备份 ---

  saveBackup(key: string, items: SearchResultItem[]): void {
    localStorage.setItem(`jq-fav-backup-${key}`, JSON.stringify(items))
  },

  loadBackup(key: string): SearchResultItem[] | null {
    try {
      const raw = localStorage.getItem(`jq-fav-backup-${key}`)
      return raw ? JSON.parse(raw) : null
    } catch {
      return null
    }
  },

  deleteBackup(key: string): void {
    localStorage.removeItem(`jq-fav-backup-${key}`)
  },

  /** 列出所有备份 key */
  listBackupKeys(): string[] {
    const keys: string[] = []
    const prefix = 'jq-fav-backup-'
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i)
      if (k && k.startsWith(prefix)) {
        keys.push(k.slice(prefix.length))
      }
    }
    return keys
  },
}
