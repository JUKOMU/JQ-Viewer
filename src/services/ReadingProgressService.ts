import type { FileReadingProgressItem, LocalFileChapter, ReadingProgressItem } from './JmcomicTypes'

const STORAGE_KEY = 'jq-reading-progress-v1'
const FILE_STORAGE_KEY = 'jq-file-reading-progress-v1'
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

function readFileProgress(): Record<string, FileReadingProgressItem> {
  try {
    const raw = localStorage.getItem(FILE_STORAGE_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

function writeFileProgress(data: Record<string, FileReadingProgressItem>): void {
  try {
    localStorage.setItem(FILE_STORAGE_KEY, JSON.stringify(data))
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
    entries.sort(([, a], [, b]) => b.updatedAt - a.updatedAt).slice(0, MAX_ITEMS),
  )
}

export const ReadingProgressService = {
  getChapter(albumId: string, chapterId: string): ReadingProgressItem | null {
    if (!albumId || !chapterId) return null
    const item = readAll()[makeKey(albumId, chapterId)]
    if (!item || item.page < 1) return null
    return item
  },

  recordChapter(albumId: string, chapterId: string, page: number, totalPages: number): void {
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

  getInitialChapterPage(
    routePage: unknown,
    albumId: string,
    chapterId: string,
    totalPages: number,
  ): number {
    const explicitPage = Number(routePage)
    if (explicitPage > 0) return normalizePage(explicitPage, totalPages)
    return this.getChapter(albumId, chapterId)?.page ?? 1
  },

  getFile(fileId: number): FileReadingProgressItem | null {
    if (!Number.isInteger(fileId) || fileId <= 0) return null
    const item = readFileProgress()[String(fileId)]
    return item?.page > 0 ? item : null
  },

  recordFile(fileId: number, page: number, totalPages: number): void {
    if (!Number.isInteger(fileId) || fileId <= 0) return
    const data = readFileProgress()
    data[String(fileId)] = {
      fileId,
      page: normalizePage(page, totalPages),
      totalPages: Math.max(0, Math.floor(totalPages || 0)),
      updatedAt: Date.now(),
    }
    const entries = Object.entries(data)
    writeFileProgress(
      entries.length <= MAX_ITEMS
        ? data
        : Object.fromEntries(
            entries.sort(([, a], [, b]) => b.updatedAt - a.updatedAt).slice(0, MAX_ITEMS),
          ),
    )
  },

  chapterToFilePage(chapter: Pick<LocalFileChapter, 'startPage' | 'pageCount'>, page: number) {
    return chapter.startPage + normalizePage(page, chapter.pageCount) - 1
  },

  fileToChapterPage(
    chapter: Pick<LocalFileChapter, 'startPage' | 'endPage' | 'pageCount'>,
    filePage: number,
  ): number | null {
    const endPage =
      chapter.endPage > 0 ? chapter.endPage : chapter.startPage + chapter.pageCount - 1
    if (filePage < chapter.startPage || filePage > endPage) return null
    return normalizePage(filePage - chapter.startPage + 1, chapter.pageCount)
  },

  getInitialFilePage(options: {
    routePage: unknown
    fileId: number
    totalPages: number
    chapter?: LocalFileChapter
  }): number {
    const explicitPage = Number(options.routePage)
    if (explicitPage > 0) return normalizePage(explicitPage, options.totalPages)
    const fileProgress = this.getFile(options.fileId)
    if (fileProgress) return normalizePage(fileProgress.page, options.totalPages)
    if (options.chapter) {
      const legacy = this.getChapter(options.chapter.albumId, options.chapter.chapterId)
      if (legacy) {
        const seeded = this.chapterToFilePage(options.chapter, legacy.page)
        this.recordFile(options.fileId, seeded, options.totalPages)
        return seeded
      }
    }
    return 1
  },
}
