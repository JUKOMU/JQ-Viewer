import { beforeEach, describe, expect, test } from 'vitest'
import {
  clearSearchPageContextCache,
  commitSearchPageWindow,
  getSearchPageContext,
  getSearchPageContextCacheSize,
  saveSearchPageContext,
  type SearchPageContextSnapshot,
} from '@/composables/searchPageContextCache'

const makeSnapshot = (keyword: string, generation: number): SearchPageContextSnapshot => {
  const item = {
    id: `${keyword}-1`,
    title: keyword,
    coverUrl: `${keyword}.jpg`,
    authors: ['作者'],
    tags: ['标签'],
  }
  return {
    query: { keyword, orderBy: 'mr', time: 'a', searchMainTag: 0 },
    pageCache: { 1: [item] },
    resultMeta: { currentPage: 1, totalItems: 1, totalPages: 1, content: [item] },
    routePage: 1,
    anchorEntryKey: `${keyword}-anchor`,
    anchorOffset: 12,
    scrollTop: 480,
    displayMode: 'list',
    generation,
    savedAt: generation,
  }
}

describe('searchPageContextCache', () => {
  beforeEach(() => clearSearchPageContextCache())

  test('窗口追加时只保留五页并淘汰最早页', () => {
    let pageCache: Record<number, SearchPageContextSnapshot['pageCache'][number]> = {}
    for (let page = 1; page <= 5; page += 1) {
      pageCache = commitSearchPageWindow(pageCache, page, [], 'append').pageCache
    }
    const result = commitSearchPageWindow(pageCache, 6, [], 'append')

    expect(Object.keys(result.pageCache).map(Number)).toEqual([2, 3, 4, 5, 6])
    expect(result.evictedPages).toEqual([1])
  })

  test('窗口前插时淘汰最晚页并拒绝重复页覆盖', () => {
    let pageCache: SearchPageContextSnapshot['pageCache'] = {}
    for (let page = 5; page >= 1; page -= 1) {
      pageCache = commitSearchPageWindow(pageCache, page, [], 'prepend').pageCache
    }
    const result = commitSearchPageWindow(pageCache, 0, [], 'prepend')
    const duplicate = commitSearchPageWindow(
      result.pageCache,
      1,
      [{ id: 'new', title: 'new', coverUrl: '', authors: [], tags: [] }],
      'prepend',
    )

    expect(Object.keys(result.pageCache).map(Number)).toEqual([0, 1, 2, 3, 4])
    expect(result.evictedPages).toEqual([5])
    expect(duplicate.pageCache[1]).toEqual([])
    expect(duplicate.evictedPages).toEqual([])
  })

  test('页码变化不会产生新的搜索上下文 key', () => {
    saveSearchPageContext(makeSnapshot('A', 1))

    expect(
      getSearchPageContext({
        keyword: 'A',
        orderBy: 'mr',
        time: 'a',
        searchMainTag: 0,
        page: 20,
      }),
    ).toMatchObject({ routePage: 1 })
    expect(getSearchPageContextCacheSize()).toBe(1)
  })

  test('最多保留三个上下文并按最近访问淘汰', () => {
    saveSearchPageContext(makeSnapshot('A', 1))
    saveSearchPageContext(makeSnapshot('B', 2))
    saveSearchPageContext(makeSnapshot('C', 3))
    expect(
      getSearchPageContext({ keyword: 'A', orderBy: 'mr', time: 'a', searchMainTag: 0 }),
    ).not.toBeNull()

    saveSearchPageContext(makeSnapshot('D', 4))

    expect(
      getSearchPageContext({ keyword: 'A', orderBy: 'mr', time: 'a', searchMainTag: 0 }),
    ).not.toBeNull()
    expect(
      getSearchPageContext({ keyword: 'B', orderBy: 'mr', time: 'a', searchMainTag: 0 }),
    ).toBeNull()
    expect(getSearchPageContextCacheSize()).toBe(3)
  })

  test('读取快照返回深拷贝，不共享页数组和条目数组', () => {
    saveSearchPageContext(makeSnapshot('A', 1))
    const first = getSearchPageContext({
      keyword: 'A',
      orderBy: 'mr',
      time: 'a',
      searchMainTag: 0,
    })!
    first.pageCache[1].push({ ...first.pageCache[1][0], id: 'mutated' })
    first.pageCache[1][0].authors.push('另一个作者')
    first.resultMeta.content.push({ ...first.resultMeta.content[0], id: 'mutated-meta' })

    const second = getSearchPageContext({
      keyword: 'A',
      orderBy: 'mr',
      time: 'a',
      searchMainTag: 0,
    })!
    expect(second.pageCache[1]).toHaveLength(1)
    expect(second.pageCache[1][0].authors).toEqual(['作者'])
    expect(second.resultMeta.content).toHaveLength(1)
  })

  test('旧 generation 不能覆盖更新的上下文快照', () => {
    saveSearchPageContext(makeSnapshot('A', 2))
    saveSearchPageContext(makeSnapshot('A', 1))

    expect(
      getSearchPageContext({ keyword: 'A', orderBy: 'mr', time: 'a', searchMainTag: 0 })
        ?.generation,
    ).toBe(2)
  })
})
