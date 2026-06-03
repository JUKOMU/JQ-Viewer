import type {ReadingProgressItem} from './JmcomicTypes'

const STORAGE_KEY = 'jq-reading-progress-v1'
const MAX_ITEMS = 1000

function readAll(): Record<string, ReadingProgressItem> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

function writeAll(data: Record<string, ReadingProgressItem>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  } catch {
    /* ignore */
  }
}

function makeKey(albumId: string, chapterId: string): string {
  return `${albumId.trim()}|${chapterId.trim()}`
}

function normalizePage(page: number, totalPages: number): number {
  if (!Number.isFinite(page) || page < 1) return 1
  if (totalPages > 0) return Math.min(Math.floor(page), totalPages)
  return Math.floor(page)
}

function prune(data: Record<string, ReadingProgressItem>): Record<string, ReadingProgressItem> {
  const entries = Object.entries(data)
  if (entries.length <= MAX_ITEMS) return data
  return Object.fromEntries(
    entries
      .sort(([, a], [, b]) => b.updatedAt - a.updatedAt)
      .slice(0, MAX_ITEMS),
  )
}

export const ReadingProgressService = {
  get(albumId: string, chapterId: string): ReadingProgressItem | null {
    if (!albumId || !chapterId) return null
    const item = readAll()[makeKey(albumId, chapterId)]
    if (!item || item.page < 1) return null
    return item
  },

  record(albumId: string, chapterId: string, page: number, totalPages: number): void {
    if (!albumId || !chapterId) return
    const data = readAll()
    const key = makeKey(albumId, chapterId)
    data[key] = {
      albumId,
      chapterId,
      page: normalizePage(page, totalPages),
      totalPages: Math.max(0, Math.floor(totalPages || 0)),
      updatedAt: Date.now(),
    }
    writeAll(prune(data))
  },

  getInitialPage(routePage: unknown, albumId: string, chapterId: string, totalPages: number): number {
    const explicitPage = Number(routePage)
    if (explicitPage > 0) return normalizePage(explicitPage, totalPages)
    return this.get(albumId, chapterId)?.page ?? 1
  },
}
