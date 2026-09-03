import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  favorites: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: { favorites: mocks.favorites },
  sanitizeError: (error: unknown, fallback: string) =>
    error instanceof Error ? error.message : fallback,
}))

import {
  clearFavoriteFolderStore,
  favoriteFolderState,
  invalidateFavoriteFolders,
  refreshFavoriteFolders,
} from '@/composables/favoriteFolderStore'

const deferred = <T>() => {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

const folderResult = (folderList: Record<string, string>) => ({
  folderName: '全部',
  folderId: '0',
  currentPage: 1,
  totalItems: 0,
  totalPages: 1,
  content: [],
  folderList,
})

beforeEach(() => {
  clearFavoriteFolderStore()
  mocks.favorites.mockReset()
})

describe('favoriteFolderStore', () => {
  test('先展示 loading，主列表成功后独立更新计数', async () => {
    const main = deferred<ReturnType<typeof folderResult>>()
    mocks.favorites.mockImplementation(({ folderId }: { folderId: string }) => {
      if (folderId === '0') return main.promise
      if (folderId === 'a') return Promise.resolve({ totalItems: 3 })
      return Promise.reject(new Error('count failed'))
    })

    const request = refreshFavoriteFolders('account-a')
    expect(favoriteFolderState.isFetching).toBe(true)
    expect(favoriteFolderState.folders).toEqual([])
    expect(favoriteFolderState.hasSuccessfulData).toBe(false)

    main.resolve(folderResult({ a: '收藏夹 A', b: '收藏夹 B' }))
    await request

    expect(favoriteFolderState.isFetching).toBe(false)
    expect(favoriteFolderState.hasSuccessfulData).toBe(true)
    expect(favoriteFolderState.folders.map((folder) => folder.name)).toEqual([
      '收藏夹 A',
      '收藏夹 B',
    ])
    expect(favoriteFolderState.counts).toEqual({ a: 3 })
  })

  test('同一账号的并发刷新复用请求，账号变化会丢弃旧响应', async () => {
    const accountA = deferred<ReturnType<typeof folderResult>>()
    const accountB = deferred<ReturnType<typeof folderResult>>()
    mocks.favorites.mockImplementation(({ folderId }: { folderId: string }) => {
      if (folderId !== '0') return Promise.resolve({ totalItems: 1 })
      if (favoriteFolderState.accountId === 'account-a') return accountA.promise
      return accountB.promise
    })

    const first = refreshFavoriteFolders('account-a')
    expect(refreshFavoriteFolders('account-a')).toBe(first)
    const oldRequest = first
    const newRequest = refreshFavoriteFolders('account-b')

    accountB.resolve(folderResult({ b: '账号 B' }))
    await newRequest
    expect(favoriteFolderState.accountId).toBe('account-b')
    expect(favoriteFolderState.folders.map((folder) => folder.name)).toEqual(['账号 B'])

    accountA.resolve(folderResult({ a: '账号 A' }))
    await oldRequest
    expect(favoriteFolderState.accountId).toBe('account-b')
    expect(favoriteFolderState.folders.map((folder) => folder.name)).toEqual(['账号 B'])
  })

  test('刷新失败时保留旧列表并提供错误，登出清空缓存', async () => {
    mocks.favorites.mockResolvedValueOnce(folderResult({ a: '旧列表' }))
    mocks.favorites.mockResolvedValueOnce({ totalItems: 2 })
    await refreshFavoriteFolders('account-a')

    invalidateFavoriteFolders()
    mocks.favorites.mockRejectedValueOnce(new Error('网络不可用'))
    await refreshFavoriteFolders('account-a')

    expect(favoriteFolderState.folders.map((folder) => folder.name)).toEqual(['旧列表'])
    expect(favoriteFolderState.hasSuccessfulData).toBe(true)
    expect(favoriteFolderState.errorMessage).toBe('网络不可用')

    clearFavoriteFolderStore()
    expect(favoriteFolderState.accountId).toBeNull()
    expect(favoriteFolderState.folders).toEqual([])
    expect(favoriteFolderState.hasSuccessfulData).toBe(false)
  })

  test('已有计数在后续计数请求失败时被隐藏', async () => {
    mocks.favorites.mockResolvedValueOnce(folderResult({ a: '收藏夹 A' }))
    mocks.favorites.mockResolvedValueOnce({ totalItems: 4 })
    await refreshFavoriteFolders('account-a')
    expect(favoriteFolderState.counts).toEqual({ a: 4 })

    invalidateFavoriteFolders()
    mocks.favorites.mockResolvedValueOnce(folderResult({ a: '收藏夹 A' }))
    mocks.favorites.mockRejectedValueOnce(new Error('count failed'))
    await refreshFavoriteFolders('account-a')

    expect(favoriteFolderState.counts).toEqual({})
    expect(favoriteFolderState.folders[0]?.count).toBe(0)
    expect(favoriteFolderState.errorMessage).toBe('')
  })
})
