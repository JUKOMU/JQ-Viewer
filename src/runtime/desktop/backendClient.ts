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

async function request<T>(fetcher: BackendFetch, method: string, body: unknown): Promise<T> {
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
    request(fetcher, method, body)

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
    preloadImages: (options: Parameters<JmcomicClient['preloadImages']>[0]) =>
      call<'preloadImages'>('preloadImages', options),
    retryImage: (options: Parameters<JmcomicClient['retryImage']>[0]) =>
      call<'retryImage'>('retryImage', options),
    login: (options: Parameters<JmcomicClient['login']>[0]) => call<'login'>('login', options),
    logout: () => call<'logout'>('logout', {}),
    checkLoginState: () => call<'checkLoginState'>('checkLoginState', {}),
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
    getBrowseHistory: (options: Parameters<JmcomicClient['getBrowseHistory']>[0]) =>
      call<'getBrowseHistory'>('getBrowseHistory', options),
    getBrowseHistoryOverview: (options: Parameters<JmcomicClient['getBrowseHistoryOverview']>[0]) =>
      call<'getBrowseHistoryOverview'>('getBrowseHistoryOverview', options),
    recordBrowse: (options: Parameters<JmcomicClient['recordBrowse']>[0]) =>
      call<'recordBrowse'>('recordBrowse', options),
    clearBrowseHistory: () => call<'clearBrowseHistory'>('clearBrowseHistory', {}),
    deleteBrowseItem: (options: Parameters<JmcomicClient['deleteBrowseItem']>[0]) =>
      call<'deleteBrowseItem'>('deleteBrowseItem', options),
    getInitStatus: async () => {
      const result = await request<{ complete?: unknown }>(fetcher, 'getInitStatus', {})
      if (!result || typeof result.complete !== 'boolean') {
        throw new RuntimeError('internal', 'Invalid getInitStatus response')
      }
      return { complete: result.complete }
    },
  }

  return client as unknown as BackendClient
}
