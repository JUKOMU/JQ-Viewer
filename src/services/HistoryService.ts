import type {
  BrowseHistoryItem,
  BrowseHistoryOverview,
  BrowseHistoryRange,
  HistoryPageResult,
  ParseHistoryItem,
} from './JmcomicTypes'
import { JmcomicService } from './JmcomicService'

// ---- localStorage 搜索历史（有界，500 条/上下文）----

export type SearchHistoryContext = 'keyword-search' | 'search-page' | 'category' | 'favorite'

export interface SearchHistoryItem {
  keyword: string
  timestamp: number
}

const SEARCH_KEY = 'jq-search-history'

function readSearchAll(): Record<string, SearchHistoryItem[]> {
  try {
    const raw = localStorage.getItem(SEARCH_KEY)
    return raw ? JSON.parse(raw) : {}
  } catch {
    return {}
  }
}

function writeSearchAll(data: Record<string, SearchHistoryItem[]>): void {
  try {
    localStorage.setItem(SEARCH_KEY, JSON.stringify(data))
  } catch {
    /* 静默丢弃 */
  }
}

export const HistoryService = {
  // ========== 搜索历史 (localStorage) ==========

  getSearchHistory(context: SearchHistoryContext): SearchHistoryItem[] {
    return readSearchAll()[context] ?? []
  },

  addSearchHistory(context: SearchHistoryContext, keyword: string): void {
    const kw = keyword.trim()
    if (!kw) return
    const all = readSearchAll()
    let items = all[context] ?? []
    const lower = kw.toLowerCase()
    items = items.filter((i) => i.keyword.toLowerCase() !== lower)
    items.unshift({ keyword: kw, timestamp: Date.now() })
    if (items.length > 500) items = items.slice(0, 500)
    all[context] = items
    writeSearchAll(all)
  },

  clearSearchHistory(context: SearchHistoryContext): void {
    const all = readSearchAll()
    delete all[context]
    writeSearchAll(all)
  },

  // ========== 浏览历史 (原生 SQLite) ==========

  async getBrowseHistory(
    limit: number = 50,
    offset: number = 0,
    range?: Pick<BrowseHistoryRange, 'startInclusive' | 'endExclusive'>,
  ): Promise<HistoryPageResult<BrowseHistoryItem> | null> {
    try {
      const result = await JmcomicService.getBrowseHistory(limit, offset, range)
      return {
        items: result.items as BrowseHistoryItem[],
        totalCount: result.totalCount,
      }
    } catch {
      return null
    }
  },

  async getBrowseHistoryOverview(
    ranges: readonly BrowseHistoryRange[],
  ): Promise<BrowseHistoryOverview | null> {
    try {
      const result = await JmcomicService.getBrowseHistoryOverview(ranges)
      return {
        totalCount: result.totalCount,
        groupCounts: result.groupCounts,
      }
    } catch {
      return null
    }
  },

  async recordBrowse(item: Omit<BrowseHistoryItem, 'id' | 'timestamp'>): Promise<void> {
    if (!item.albumTitle) return
    try {
      await JmcomicService.recordBrowse(item)
    } catch {
      /* 静默丢弃 */
    }
  },

  async clearBrowseHistory(): Promise<void> {
    try {
      await JmcomicService.clearBrowseHistory()
    } catch {
      /* ignore */
    }
  },

  async deleteBrowseItem(id: number): Promise<void> {
    try {
      await JmcomicService.deleteBrowseItem(id)
    } catch {
      /* 静默丢弃 */
    }
  },

  // ========== 解析历史 (原生 SQLite) ==========

  async getParseHistory(
    limit: number = 50,
    offset: number = 0,
  ): Promise<HistoryPageResult<ParseHistoryItem> | null> {
    try {
      const result = await JmcomicService.getParseHistory(limit, offset)
      return {
        items: result.items as ParseHistoryItem[],
        totalCount: result.totalCount,
      }
    } catch {
      return null
    }
  },

  async addParseHistory(text: string, mode: string): Promise<void> {
    const t = text.trim()
    if (!t) return
    try {
      await JmcomicService.addParseHistory(t, mode)
    } catch {
      /* 静默丢弃 */
    }
  },

  async clearParseHistory(): Promise<void> {
    try {
      await JmcomicService.clearParseHistory()
    } catch {
      /* ignore */
    }
  },

  async deleteParseItem(id: number): Promise<boolean> {
    try {
      const result = await JmcomicService.deleteParseItem(id)
      return result.success === true
    } catch {
      return false
    }
  },
}
