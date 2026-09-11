import type { BackendClient } from '../BackendClient'
import { RuntimeError, type RuntimeErrorCode } from '../errors'

export const DESKTOP_BACKEND_METHODS = ['getInitStatus'] as const

export type DesktopFetch = (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>

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

async function request<T>(fetcher: DesktopFetch, method: string, body: unknown): Promise<T> {
  let response: Response
  try {
    response = await fetcher(`/api/${method}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
  } catch (error) {
    throw new RuntimeError(
      'network',
      error instanceof Error ? error.message : 'Desktop request failed',
      {
        cause: error,
      },
    )
  }

  const payload = await readJson(response)
  if (!response.ok) {
    const candidate = payload as { code?: unknown; message?: unknown } | null
    const code = isRuntimeErrorCode(candidate?.code) ? candidate.code : 'internal'
    const message =
      typeof candidate?.message === 'string' && candidate.message.trim()
        ? candidate.message
        : `Desktop request failed with status ${response.status}`
    throw new RuntimeError(code, message)
  }

  return payload as T
}

/** 构建 Desktop backend 能力面；未实现的方法不会暴露到运行时。 */
export function createDesktopBackendClient(
  fetcher: DesktopFetch = globalThis.fetch.bind(globalThis),
): BackendClient {
  const client = {
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
