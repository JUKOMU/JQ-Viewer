import {afterEach, beforeEach, describe, expect, test, vi} from 'vitest'

describe('desktop http runtime', () => {
  beforeEach(() => {
    vi.resetModules()
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    localStorage.clear()
    window.history.pushState({}, '', '/')
  })

  afterEach(() => {
    vi.unstubAllEnvs()
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  test('appends the desktop token to resource URLs', async () => {
    vi.stubEnv('VITE_JQ_DESKTOP_TOKEN', 'stage-token')

    const {desktopResourceUrl} = await import('@/services/jmcomic/http')

    expect(desktopResourceUrl('/image/photo-1/2')).toBe('/image/photo-1/2?token=stage-token')
    expect(desktopResourceUrl('/api/pdf/page?path=a.pdf&page=1')).toBe(
      '/api/pdf/page?path=a.pdf&page=1&token=stage-token',
    )
  })

  test('uses the backend origin for resource URLs when api base is absolute', async () => {
    vi.stubEnv('VITE_JQ_DESKTOP_API_BASE', 'http://127.0.0.1:18080/api')
    vi.stubEnv('VITE_JQ_DESKTOP_TOKEN', 'space token')

    const {desktopResourceUrl} = await import('@/services/jmcomic/http')

    expect(desktopResourceUrl('/thumb/photo-1/1')).toBe(
      'http://127.0.0.1:18080/thumb/photo-1/1?token=space%20token',
    )
  })

  test('sends token headers on desktop API requests', async () => {
    vi.stubEnv('VITE_JQ_DESKTOP_TOKEN', 'request-token')
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({success: true}), {status: 200}))
    vi.stubGlobal('fetch', fetchMock)

    const {desktopRequest, jsonBody} = await import('@/services/jmcomic/http')
    const result = await desktopRequest<{success: boolean}>('/settings', {
      method: 'POST',
      body: jsonBody({readerBrightness: 0.5}),
    })

    const [, init] = fetchMock.mock.calls[0]
    const headers = init?.headers as Headers
    expect(result.success).toBe(true)
    expect(fetchMock).toHaveBeenCalledWith('/api/settings', expect.any(Object))
    expect(headers.get('X-JQ-Desktop-Token')).toBe('request-token')
    expect(headers.get('Content-Type')).toBe('application/json')
  })

  test('consumes launch token, persists it, and removes it from the visible URL', async () => {
    window.history.pushState({}, '', '/reader?jqDesktopToken=launch-token&chapter=1#page')

    const {desktopResourceUrl} = await import('@/services/jmcomic/http')

    expect(localStorage.getItem('jq-desktop-token')).toBe('launch-token')
    expect(window.location.href).toBe('http://localhost:3000/reader?chapter=1#page')
    expect(desktopResourceUrl('/events')).toBe('/events?token=launch-token')
  })
})
