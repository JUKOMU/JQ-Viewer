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
  test('only installs getInitStatus and sends the existing JSON command shape', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ complete: true }))
    const backend = createDesktopBackendClient(fetcher)

    await expect(backend.getInitStatus()).resolves.toEqual({ complete: true })
    expect(Object.keys(backend)).toEqual(['getInitStatus'])
    expect(fetcher).toHaveBeenCalledWith('/api/getInitStatus', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
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
    expect((backend as unknown as { search?: unknown }).search).toBeUndefined()
  })

  test('uses same-origin resources and explicit unavailable phase-1 capabilities', async () => {
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
