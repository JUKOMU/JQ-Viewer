const DEFAULT_DESKTOP_API_BASE = '/api'

const desktopApiBase = import.meta.env.VITE_JQ_DESKTOP_API_BASE || DEFAULT_DESKTOP_API_BASE
const desktopToken =
  import.meta.env.VITE_JQ_DESKTOP_TOKEN || localStorage.getItem('jq-desktop-token') || ''

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
