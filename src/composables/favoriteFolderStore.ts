import { computed, reactive } from 'vue'
import type { FolderEntry } from '@/services/JmcomicTypes'
import { JmcomicService, sanitizeError } from '@/services/JmcomicService'

export interface FavoriteFolderStoreState {
  accountId: string | null
  folders: FolderEntry[]
  counts: Record<string, number>
  hasSuccessfulData: boolean
  isFetching: boolean
  errorMessage: string
}

const state = reactive<FavoriteFolderStoreState>({
  accountId: null,
  folders: [],
  counts: {},
  hasSuccessfulData: false,
  isFetching: false,
  errorMessage: '',
})

let requestGeneration = 0
let activeRequest: {
  accountId: string
  generation: number
  promise: Promise<void>
} | null = null

const isCurrentRequest = (accountId: string, generation: number) =>
  state.accountId === accountId && requestGeneration === generation

const resetState = (accountId: string | null) => {
  requestGeneration += 1
  activeRequest = null
  state.accountId = accountId
  state.folders = []
  state.counts = {}
  state.hasSuccessfulData = false
  state.isFetching = false
  state.errorMessage = ''
}

const ensureAccount = (accountId: string) => {
  if (state.accountId === accountId) return
  resetState(accountId)
}

const folderEntriesFromMap = (
  folderMap: Record<string, string>,
  counts: Record<string, number>,
): FolderEntry[] =>
  Object.entries(folderMap).map(([id, name]) => ({
    id,
    name,
    count: counts[id] ?? 0,
  }))

const retainKnownCounts = (folderMap: Record<string, string>) => {
  const counts: Record<string, number> = {}
  for (const id of Object.keys(folderMap)) {
    const count = state.counts[id]
    if (count !== undefined) counts[id] = count
  }
  return counts
}

const applyFolderMap = (
  accountId: string,
  generation: number,
  folderMap: Record<string, string>,
) => {
  if (!isCurrentRequest(accountId, generation)) return false
  state.counts = retainKnownCounts(folderMap)
  state.folders = folderEntriesFromMap(folderMap, state.counts)
  state.hasSuccessfulData = true
  state.errorMessage = ''
  return true
}

const refreshCounts = async (accountId: string, generation: number) => {
  const folderIds = state.folders.map((folder) => folder.id)
  await Promise.all(
    folderIds.map(async (folderId) => {
      try {
        const result = await JmcomicService.favorites({ folderId, page: 1 })
        if (!isCurrentRequest(accountId, generation)) return
        state.counts = { ...state.counts, [folderId]: result.totalItems }
        state.folders = state.folders.map((folder) =>
          folder.id === folderId ? { ...folder, count: result.totalItems } : folder,
        )
      } catch {
        if (!isCurrentRequest(accountId, generation)) return
        const nextCounts = { ...state.counts }
        delete nextCounts[folderId]
        state.counts = nextCounts
        state.folders = state.folders.map((folder) =>
          folder.id === folderId ? { ...folder, count: 0 } : folder,
        )
      }
    }),
  )
}

/**
 * Refresh the online folder names and their supplementary counts for one account.
 * The names request is authoritative for the visible list; count requests never
 * turn a failed count into zero.
 */
export function refreshFavoriteFolders(accountId: string | null): Promise<void> {
  if (!accountId) {
    if (state.accountId !== null || state.hasSuccessfulData) resetState(null)
    return Promise.resolve()
  }

  ensureAccount(accountId)
  if (activeRequest?.accountId === accountId) return activeRequest.promise

  const generation = ++requestGeneration
  state.isFetching = true
  state.errorMessage = ''

  const promise = (async () => {
    try {
      const result = await JmcomicService.favorites({ folderId: '0', page: 1 })
      if (!isCurrentRequest(accountId, generation)) return

      applyFolderMap(accountId, generation, result.folderList ?? {})
      await refreshCounts(accountId, generation)
    } catch (error) {
      if (!isCurrentRequest(accountId, generation)) return
      state.errorMessage = sanitizeError(error, '收藏夹列表加载失败')
    } finally {
      if (
        activeRequest?.accountId === accountId &&
        activeRequest.generation === generation &&
        isCurrentRequest(accountId, generation)
      ) {
        state.isFetching = false
        activeRequest = null
      }
    }
  })()

  activeRequest = { accountId, generation, promise }
  return promise
}

/** Mark names/counts stale while retaining the last successful list on screen. */
export function invalidateFavoriteFolders() {
  requestGeneration += 1
  activeRequest = null
  state.isFetching = false
  state.errorMessage = ''
}

/** Clear all online folder data, normally when the account logs out or changes. */
export function clearFavoriteFolderStore() {
  resetState(null)
}

/** Apply a trusted folder-list response without issuing another request. */
export function applyFavoriteFolderList(accountId: string, folderMap: Record<string, string>) {
  ensureAccount(accountId)
  const generation = requestGeneration
  applyFolderMap(accountId, generation, folderMap)
}

export const favoriteFolderState = state

const onlineFolders = computed(() => state.folders)
const onlineFolderCounts = computed(() => state.counts)
const hasSuccessfulData = computed(() => state.hasSuccessfulData)
const isFetching = computed(() => state.isFetching)
const errorMessage = computed(() => state.errorMessage)
const accountId = computed(() => state.accountId)

export function useFavoriteFolderStore() {
  return {
    accountId,
    folders: onlineFolders,
    counts: onlineFolderCounts,
    hasSuccessfulData,
    isFetching,
    errorMessage,
    refresh: refreshFavoriteFolders,
    invalidate: invalidateFavoriteFolders,
    clear: clearFavoriteFolderStore,
    applyFolderList: applyFavoriteFolderList,
  }
}
