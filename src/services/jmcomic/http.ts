const DEFAULT_DESKTOP_API_BASE = '/api'
const DESKTOP_TOKEN_STORAGE_KEY = 'jq-desktop-token'
const DESKTOP_LAUNCH_TOKEN_PARAM = 'jqDesktopToken'

const desktopApiBase = import.meta.env.VITE_JQ_DESKTOP_API_BASE || DEFAULT_DESKTOP_API_BASE
const desktopToken = resolveDesktopToken()

function resolveDesktopToken(): string {
  const envToken = import.meta.env.VITE_JQ_DESKTOP_TOKEN
  if (envToken) return envToken

  const launchToken = consumeLaunchToken()
  if (launchToken) return launchToken

  return readStoredToken()
}

function consumeLaunchToken(): string {
  if (typeof window === 'undefined') return ''

  const params = new URLSearchParams(window.location.search)
  const token = params.get(DESKTOP_LAUNCH_TOKEN_PARAM) || ''
  if (!token) return ''

  try {
    localStorage.setItem(DESKTOP_TOKEN_STORAGE_KEY, token)
  } catch {
    // The in-memory token still works for this page load if storage is blocked.
  }

  params.delete(DESKTOP_LAUNCH_TOKEN_PARAM)
  const search = params.toString()
  const cleanUrl = `${window.location.pathname}${search ? `?${search}` : ''}${window.location.hash}`
  window.history.replaceState(window.history.state, document.title, cleanUrl)
  return token
}

function readStoredToken(): string {
  if (typeof localStorage === 'undefined') return ''
  return localStorage.getItem(DESKTOP_TOKEN_STORAGE_KEY) || ''
}

export function desktopResourceUrl(path: string): string {
  if (!desktopToken) {
    throw new Error('Missing desktop token. Set VITE_JQ_DESKTOP_TOKEN or jq-desktop-token.')
  }

  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const base = desktopApiBase === '/api'
    ? ''
    : desktopApiBase.endsWith('/api')
      ? desktopApiBase.slice(0, -'/api'.length)
      : desktopApiBase
  const separator = normalizedPath.includes('?') ? '&' : '?'
  return `${base}${normalizedPath}${separator}token=${encodeURIComponent(desktopToken)}`
}

export function desktopEventUrl(path: string): string {
  return desktopResourceUrl(path)
}

export async function desktopRequest<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  if (!desktopToken) {
    throw new Error('Missing desktop token. Set VITE_JQ_DESKTOP_TOKEN or jq-desktop-token.')
  }

  const headers = new Headers(options.headers)
  headers.set('X-JQ-Desktop-Token', desktopToken)
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  const response = await fetch(`${desktopApiBase}${path}`, {
    ...options,
    headers,
  })
  const text = await response.text()
  const data = text ? JSON.parse(text) : null

  if (!response.ok) {
    const message =
      data && typeof data.error === 'string' ? data.error : `Desktop API failed: ${response.status}`
    throw new Error(message)
  }
  return data as T
}

export function jsonBody(value: unknown): BodyInit {
  return JSON.stringify(value)
}
