import type { BackendClient } from '../BackendClient'
import { RuntimeError, type RuntimeErrorCode } from '../errors'
import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'

export type BackendFetch = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

function isRuntimeErrorCode(value: unknown): value is RuntimeErrorCode {
  return (
    value === 'unavailable' ||
    value === 'permission-denied' ||
    value === 'cancelled' ||
    value === 'not-found' ||
    value === 'conflict' ||
    value === 'network' ||
    value === 'internal'
  )
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    return null
  }
}

export async function requestBackend<T>(
  fetcher: BackendFetch,
  method: string,
  body: unknown,
): Promise<T> {
  let response: Response
  try {
    response = await fetcher(`/api/${method}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  } catch (error) {
    throw new RuntimeError('network', error instanceof Error ? error.message : '后端请求失败', {
      cause: error,
    })
  }

  const payload = await readJson(response)
  if (!response.ok) {
    const candidate = payload as { code?: unknown; message?: unknown } | null
    const code = isRuntimeErrorCode(candidate?.code) ? candidate.code : 'internal'
    const message =
      typeof candidate?.message === 'string' && candidate.message.trim()
        ? candidate.message
        : `后端请求失败，HTTP 状态码为 ${response.status}`
    throw new RuntimeError(code, message)
  }

  return payload as T
}

/** 构建 JSON 后端能力面；未实现的方法不会暴露到运行时。 */
export function createBackendClient(
  fetcher: BackendFetch = globalThis.fetch.bind(globalThis),
): BackendClient {
  const call = <K extends keyof JmcomicClient>(
    method: string,
    body: unknown,
  ): Promise<Awaited<ReturnType<Extract<JmcomicClient[K], (...args: never[]) => unknown>>>> =>
    requestBackend(fetcher, method, body)

  const client = {
    search: (options: Parameters<JmcomicClient['search']>[0]) =>
      call<'search'>('search', options.query),
    categories: (options: Parameters<JmcomicClient['categories']>[0]) =>
      call<'categories'>('categories', options.query),
    getAlbum: (options: Parameters<JmcomicClient['getAlbum']>[0]) =>
      call<'getAlbum'>('getAlbum', options),
    getPhoto: (options: Parameters<JmcomicClient['getPhoto']>[0]) =>
      call<'getPhoto'>('getPhoto', options),
    getComments: (options: Parameters<JmcomicClient['getComments']>[0]) =>
      call<'getComments'>('getComments', options),
    getFavorites: (options: Parameters<JmcomicClient['getFavorites']>[0]) =>
      call<'getFavorites'>('getFavorites', options.query),
    toggleAlbumLike: (options: Parameters<JmcomicClient['toggleAlbumLike']>[0]) =>
      call<'toggleAlbumLike'>('toggleAlbumLike', options),
    toggleAlbumFavorite: (options: Parameters<JmcomicClient['toggleAlbumFavorite']>[0]) =>
      call<'toggleAlbumFavorite'>('toggleAlbumFavorite', options),
    manageFavoriteFolder: (options: Parameters<JmcomicClient['manageFavoriteFolder']>[0]) =>
      call<'manageFavoriteFolder'>('manageFavoriteFolder', options),
    preloadImages: (options: Parameters<JmcomicClient['preloadImages']>[0]) =>
      call<'preloadImages'>('preloadImages', options),
    retryImage: (options: Parameters<JmcomicClient['retryImage']>[0]) =>
      call<'retryImage'>('retryImage', options),
    login: (options: Parameters<JmcomicClient['login']>[0]) => call<'login'>('login', options),
    logout: () => call<'logout'>('logout', {}),
    checkLoginState: () => call<'checkLoginState'>('checkLoginState', {}),
    autoLogin: () => call<'autoLogin'>('autoLogin', {}),
    getUserProfile: (options: Parameters<JmcomicClient['getUserProfile']>[0]) =>
      call<'getUserProfile'>('getUserProfile', options),
    getAllSettings: () => call<'getAllSettings'>('getAllSettings', {}),
    setPreloadConcurrency: (options: Parameters<JmcomicClient['setPreloadConcurrency']>[0]) =>
      call<'setPreloadConcurrency'>('setPreloadConcurrency', options),
    setDownloadConcurrency: (options: Parameters<JmcomicClient['setDownloadConcurrency']>[0]) =>
      call<'setDownloadConcurrency'>('setDownloadConcurrency', options),
    setReaderPreloadPages: (options: Parameters<JmcomicClient['setReaderPreloadPages']>[0]) =>
      call<'setReaderPreloadPages'>('setReaderPreloadPages', options),
    setReaderDisplayMode: (options: Parameters<JmcomicClient['setReaderDisplayMode']>[0]) =>
      call<'setReaderDisplayMode'>('setReaderDisplayMode', options),
    setReaderAutoShowToolbarAtEnd: (
      options: Parameters<JmcomicClient['setReaderAutoShowToolbarAtEnd']>[0],
    ) => call<'setReaderAutoShowToolbarAtEnd'>('setReaderAutoShowToolbarAtEnd', options),
    downloadChapter: (options: Parameters<JmcomicClient['downloadChapter']>[0]) =>
      call<'downloadChapter'>('downloadChapter', options),
    getDownloadTasks: () => call<'getDownloadTasks'>('getDownloadTasks', {}),
    cancelDownload: (options: Parameters<JmcomicClient['cancelDownload']>[0]) =>
      call<'cancelDownload'>('cancelDownload', options),
    pauseDownload: (options: Parameters<JmcomicClient['pauseDownload']>[0]) =>
      call<'pauseDownload'>('pauseDownload', options),
    resumeDownload: (options: Parameters<JmcomicClient['resumeDownload']>[0]) =>
      call<'resumeDownload'>('resumeDownload', options),
    deleteDownloaded: (options: Parameters<JmcomicClient['deleteDownloaded']>[0]) =>
      call<'deleteDownloaded'>('deleteDownloaded', options),
    getDownloadedPhoto: (options: Parameters<JmcomicClient['getDownloadedPhoto']>[0]) =>
      call<'getDownloadedPhoto'>('getDownloadedPhoto', options),
    getBrowseHistory: (options: Parameters<JmcomicClient['getBrowseHistory']>[0]) =>
      call<'getBrowseHistory'>('getBrowseHistory', options),
    getBrowseHistoryOverview: (options: Parameters<JmcomicClient['getBrowseHistoryOverview']>[0]) =>
      call<'getBrowseHistoryOverview'>('getBrowseHistoryOverview', options),
    recordBrowse: (options: Parameters<JmcomicClient['recordBrowse']>[0]) =>
      call<'recordBrowse'>('recordBrowse', options),
    clearBrowseHistory: () => call<'clearBrowseHistory'>('clearBrowseHistory', {}),
    deleteBrowseItem: (options: Parameters<JmcomicClient['deleteBrowseItem']>[0]) =>
      call<'deleteBrowseItem'>('deleteBrowseItem', options),
    getOfflineFolders: () => call<'getOfflineFolders'>('getOfflineFolders', {}),
    createOfflineFolder: (options: Parameters<JmcomicClient['createOfflineFolder']>[0]) =>
      call<'createOfflineFolder'>('createOfflineFolder', options),
    renameOfflineFolder: (options: Parameters<JmcomicClient['renameOfflineFolder']>[0]) =>
      call<'renameOfflineFolder'>('renameOfflineFolder', options),
    deleteOfflineFolder: (options: Parameters<JmcomicClient['deleteOfflineFolder']>[0]) =>
      call<'deleteOfflineFolder'>('deleteOfflineFolder', options),
    addOfflineFavorite: (options: Parameters<JmcomicClient['addOfflineFavorite']>[0]) =>
      call<'addOfflineFavorite'>('addOfflineFavorite', options),
    removeOfflineFavorite: (options: Parameters<JmcomicClient['removeOfflineFavorite']>[0]) =>
      call<'removeOfflineFavorite'>('removeOfflineFavorite', options),
    getOfflineFavorites: (options: Parameters<JmcomicClient['getOfflineFavorites']>[0]) =>
      call<'getOfflineFavorites'>('getOfflineFavorites', options),
    getAllOfflineFavorites: (options: Parameters<JmcomicClient['getAllOfflineFavorites']>[0]) =>
      call<'getAllOfflineFavorites'>('getAllOfflineFavorites', options),
    getOfflineFavoritesTotalCount: () =>
      call<'getOfflineFavoritesTotalCount'>('getOfflineFavoritesTotalCount', {}),
    getAllOfflineFavoritesMerged: () =>
      call<'getAllOfflineFavoritesMerged'>('getAllOfflineFavoritesMerged', {}),
    moveAllOfflineFavorites: (options: Parameters<JmcomicClient['moveAllOfflineFavorites']>[0]) =>
      call<'moveAllOfflineFavorites'>('moveAllOfflineFavorites', options),
    copyOfflineFolder: (options: Parameters<JmcomicClient['copyOfflineFolder']>[0]) =>
      call<'copyOfflineFolder'>('copyOfflineFolder', options),
    addOfflineFavoritesBatch: (options: Parameters<JmcomicClient['addOfflineFavoritesBatch']>[0]) =>
      call<'addOfflineFavoritesBatch'>('addOfflineFavoritesBatch', options),
    mergeOfflineAllToFolder: (options: Parameters<JmcomicClient['mergeOfflineAllToFolder']>[0]) =>
      call<'mergeOfflineAllToFolder'>('mergeOfflineAllToFolder', options),
    saveOfflineBackup: (options: Parameters<JmcomicClient['saveOfflineBackup']>[0]) =>
      call<'saveOfflineBackup'>('saveOfflineBackup', options),
    loadOfflineBackup: (options: Parameters<JmcomicClient['loadOfflineBackup']>[0]) =>
      call<'loadOfflineBackup'>('loadOfflineBackup', options),
    deleteOfflineBackup: (options: Parameters<JmcomicClient['deleteOfflineBackup']>[0]) =>
      call<'deleteOfflineBackup'>('deleteOfflineBackup', options),
    listOfflineBackupKeys: () => call<'listOfflineBackupKeys'>('listOfflineBackupKeys', {}),
    getDomainStates: () => call<'getDomainStates'>('getDomainStates', {}),
    reprobeDomains: () => call<'reprobeDomains'>('reprobeDomains', {}),
    measureLatency: () => call<'measureLatency'>('measureLatency', {}),
    getInitStatus: async () => {
      const result = await requestBackend<{ complete?: unknown }>(fetcher, 'getInitStatus', {})
      if (!result || typeof result.complete !== 'boolean') {
        throw new RuntimeError('internal', 'Invalid getInitStatus response')
      }
      return { complete: result.complete }
    },
  }

  return client as unknown as BackendClient
}
