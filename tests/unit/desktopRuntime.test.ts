import { describe, expect, test, vi } from 'vitest'
import { createDesktopBackendClient } from '@/runtime/desktop/desktopBackendClient'
import { createDesktopRuntime } from '@/runtime/desktop/createDesktopRuntime'
import { RuntimeError } from '@/runtime/errors'

function response(payload: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: async () => payload,
  } as Response
}

describe('Desktop runtime', () => {
  test('sends the existing JSON command shape for every implemented method', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ complete: true }))
    const backend = createDesktopBackendClient(fetcher)

    await expect(backend.getInitStatus()).resolves.toEqual({ complete: true })
    expect(fetcher).toHaveBeenLastCalledWith('/api/getInitStatus', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })

    fetcher.mockResolvedValue(response({ value: 'ok' }))
    const calls: Array<[() => Promise<unknown>, string, unknown]> = [
      [
        () => backend.search({ query: { orderBy: 'mr', time: 'a', searchMainTag: 0 } }),
        'search',
        { query: { orderBy: 'mr', time: 'a', searchMainTag: 0 } },
      ],
      [
        () => backend.categories({ query: { orderBy: 'mr', time: 'a', searchMainTag: 0 } }),
        'categories',
        { query: { orderBy: 'mr', time: 'a', searchMainTag: 0 } },
      ],
      [() => backend.getAlbum({ id: '10' }), 'getAlbum', { id: '10' }],
      [() => backend.getPhoto({ id: '20' }), 'getPhoto', { id: '20' }],
      [
        () => backend.getComments({ albumId: '10', page: 2 }),
        'getComments',
        { albumId: '10', page: 2 },
      ],
      [
        () => backend.login({ username: 'user', password: 'pass' }),
        'login',
        { username: 'user', password: 'pass' },
      ],
      [() => backend.logout(), 'logout', {}],
      [() => backend.checkLoginState(), 'checkLoginState', {}],
      [() => backend.getUserProfile({ uid: '1' }), 'getUserProfile', { uid: '1' }],
      [() => backend.getAllSettings(), 'getAllSettings', {}],
      [() => backend.setPreloadConcurrency({ n: 4 }), 'setPreloadConcurrency', { n: 4 }],
      [
        () => backend.setDownloadConcurrency({ n: 5 }),
        'setDownloadConcurrency',
        { n: 5 },
      ],
      [
        () => backend.setReaderPreloadPages({ n: 20 }),
        'setReaderPreloadPages',
        { n: 20 },
      ],
      [
        () => backend.setReaderDisplayMode({ mode: 'horizontal' }),
        'setReaderDisplayMode',
        { mode: 'horizontal' },
      ],
      [
        () => backend.setReaderAutoShowToolbarAtEnd({ enabled: false }),
        'setReaderAutoShowToolbarAtEnd',
        { enabled: false },
      ],
      [
        () => backend.getBrowseHistory({ limit: 20, offset: 0 }),
        'getBrowseHistory',
        { limit: 20, offset: 0 },
      ],
      [
        () => backend.getBrowseHistoryOverview({ ranges: [] }),
        'getBrowseHistoryOverview',
        { ranges: [] },
      ],
      [
        () =>
          backend.recordBrowse({
            albumId: '10',
            albumTitle: 'A',
            coverUrl: '',
            authors: '',
            chapterId: '20',
            chapterTitle: 'C',
          }),
        'recordBrowse',
        {
          albumId: '10',
          albumTitle: 'A',
          coverUrl: '',
          authors: '',
          chapterId: '20',
          chapterTitle: 'C',
        },
      ],
      [() => backend.clearBrowseHistory(), 'clearBrowseHistory', {}],
      [() => backend.deleteBrowseItem({ id: 7 }), 'deleteBrowseItem', { id: 7 }],
    ]

    for (const [invoke, method, body] of calls) {
      await expect(invoke()).resolves.toEqual({ value: 'ok' })
      expect(fetcher).toHaveBeenLastCalledWith(`/api/${method}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
    }

    expect(Object.keys(backend)).toHaveLength(21)
    expect((backend as unknown as { autoLogin?: unknown }).autoLogin).toBeUndefined()
    expect((backend as unknown as { retryImage?: unknown }).retryImage).toBeUndefined()
  })

  test('normalizes transport and malformed response failures without adding a fallback method', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('connection refused'))
    const backend = createDesktopBackendClient(fetcher)

    await expect(backend.getInitStatus()).rejects.toMatchObject({
      code: 'network',
      message: 'connection refused',
    })

    const malformed = createDesktopBackendClient(
      vi.fn().mockResolvedValue(response({ complete: 'yes' })),
    )
    await expect(malformed.getInitStatus()).rejects.toMatchObject({
      code: 'internal',
    })
    expect((backend as unknown as { autoLogin?: unknown }).autoLogin).toBeUndefined()
  })

  test('uses same-origin resources and explicit unavailable Desktop capabilities', async () => {
    const runtime = createDesktopRuntime(vi.fn().mockResolvedValue(response({ complete: true })))

    expect(runtime.platform).toBe('linux')
    expect(runtime.resources.imageUrl({ photoId: 'chapter/1', sortOrder: 2, type: 'thumb' })).toBe(
      '/thumb/chapter%2F1/2',
    )
    expect(runtime.resources.pdfDocumentUrl('file:path:/books/book.pdf' as never)).toBe(
      '/pdf/file%3Apath%3A%2Fbooks%2Fbook.pdf',
    )
    expect(runtime.services.storage.available).toBe(false)
    expect(runtime.services.updater.available).toBe(false)
    expect(runtime.services.reader.fullscreen.available).toBe(false)
    expect(runtime.services.notifications.kind).toBe('host-managed')
    expect('files' in runtime.services).toBe(false)
    expect('pdf' in runtime.services).toBe(false)
    await expect(runtime.events.onNetworkProbe(() => undefined)).rejects.toMatchObject({
      code: 'unavailable',
    })
  })

  test('preserves structured backend error responses', async () => {
    const backend = createDesktopBackendClient(
      vi
        .fn()
        .mockResolvedValue(response({ code: 'conflict', message: 'stale request' }, false, 409)),
    )

    await expect(backend.getInitStatus()).rejects.toEqual(
      new RuntimeError('conflict', 'stale request'),
    )
  })
})
