import type { SearchQuery, SearchResult, SearchResultItem } from '@/services/JmcomicTypes'

export const SEARCH_PAGE_WINDOW_SIZE = 5
export const SEARCH_PAGE_CONTEXT_CACHE_LIMIT = 3

export type SearchPageContextQuery = {
  keyword: string
  orderBy: string
  time: string
  searchMainTag: number
}

export interface SearchPageContextSnapshot {
  query: SearchPageContextQuery
  pageCache: Record<number, SearchResultItem[]>
  resultMeta: SearchResult
  routePage: number
  anchorEntryKey: string | null
  anchorOffset: number | null
  scrollTop: number
  displayMode: 'list' | 'grid'
  generation: number
  savedAt: number
}

const contextCache = new Map<string, SearchPageContextSnapshot>()
let nextContextGeneration = 0

export const allocateSearchPageContextGeneration = () => {
  nextContextGeneration += 1
  return nextContextGeneration
}

const cloneItem = (item: SearchResultItem): SearchResultItem => ({
  ...item,
  authors: item.authors.slice(),
  tags: item.tags.slice(),
})

const clonePageCache = (pageCache: Record<number, SearchResultItem[]>) => {
  const cloned: Record<number, SearchResultItem[]> = {}
  for (const [page, items] of Object.entries(pageCache)) {
    cloned[Number(page)] = items.map(cloneItem)
  }
  return cloned
}

const cloneSnapshot = (snapshot: SearchPageContextSnapshot): SearchPageContextSnapshot => ({
  ...snapshot,
  query: { ...snapshot.query },
  pageCache: clonePageCache(snapshot.pageCache),
  resultMeta: { ...snapshot.resultMeta, content: snapshot.resultMeta.content.map(cloneItem) },
})

export const toSearchPageContextQuery = (query: SearchQuery): SearchPageContextQuery => ({
  keyword: query.keyword ?? '',
  orderBy: query.orderBy,
  time: query.time,
  searchMainTag: query.searchMainTag,
})

export const createSearchPageContextKey = (query: SearchQuery | SearchPageContextQuery) => {
  const contextQuery: SearchPageContextQuery =
    'page' in query
      ? toSearchPageContextQuery(query)
      : {
          keyword: query.keyword ?? '',
          orderBy: query.orderBy,
          time: query.time,
          searchMainTag: query.searchMainTag,
        }
  return JSON.stringify([
    contextQuery.keyword.trim(),
    contextQuery.orderBy,
    contextQuery.time,
    contextQuery.searchMainTag,
  ])
}

export type SearchPageWindowDirection = 'reset' | 'append' | 'prepend'

export interface SearchPageWindowCommit {
  pageCache: Record<number, SearchResultItem[]>
  pages: number[]
  evictedPages: number[]
}

export const commitSearchPageWindow = (
  pageCache: Record<number, SearchResultItem[]>,
  page: number,
  content: SearchResultItem[],
  direction: SearchPageWindowDirection,
): SearchPageWindowCommit => {
  const nextCache: Record<number, SearchResultItem[]> =
    direction === 'reset' ? {} : { ...pageCache }
  if (direction !== 'reset' && nextCache[page]) {
    return {
      pageCache: nextCache,
      pages: Object.keys(nextCache)
        .map(Number)
        .sort((a, b) => a - b),
      evictedPages: [],
    }
  }
  nextCache[page] = content
  const pages = Object.keys(nextCache)
    .map(Number)
    .sort((a, b) => a - b)
  const evictedPages: number[] = []
  while (pages.length > SEARCH_PAGE_WINDOW_SIZE) {
    const evicted = direction === 'prepend' ? pages.pop() : pages.shift()
    if (evicted === undefined) break
    evictedPages.push(evicted)
    delete nextCache[evicted]
  }
  return { pageCache: nextCache, pages, evictedPages }
}

export const saveSearchPageContext = (snapshot: SearchPageContextSnapshot) => {
  const key = createSearchPageContextKey(snapshot.query)
  const existing = contextCache.get(key)
  if (existing && snapshot.generation < existing.generation) return
  contextCache.delete(key)
  contextCache.set(key, cloneSnapshot(snapshot))
  while (contextCache.size > SEARCH_PAGE_CONTEXT_CACHE_LIMIT) {
    const oldestKey = contextCache.keys().next().value as string | undefined
    if (!oldestKey) break
    contextCache.delete(oldestKey)
  }
}

export const getSearchPageContext = (query: SearchQuery | SearchPageContextQuery) => {
  const key = createSearchPageContextKey(query)
  const snapshot = contextCache.get(key)
  if (!snapshot) return null
  contextCache.delete(key)
  contextCache.set(key, snapshot)
  return cloneSnapshot(snapshot)
}

export const clearSearchPageContext = (query: SearchQuery | SearchPageContextQuery) => {
  contextCache.delete(createSearchPageContextKey(query))
}

export const clearSearchPageContextCache = () => {
  contextCache.clear()
}

export const getSearchPageContextCacheSize = () => contextCache.size
