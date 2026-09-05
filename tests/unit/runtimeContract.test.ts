import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { JmcomicClient } from '@/services/jmcomic/JmcomicClient'
import { COMMON_BACKEND_METHODS } from '@/runtime/BackendClient'
import { createIdempotentListenerHandle } from '@/runtime/BackendEvents'
import { createAndroidBackendClient } from '@/runtime/android/androidBackendClient'
import { createAndroidBackendEvents } from '@/runtime/android/androidBackendEvents'
import { createAndroidPlatformServices } from '@/runtime/android/androidPlatformServices'
import { createAndroidResourceResolver } from '@/runtime/android/androidResourceResolver'
import { createAndroidUpdater } from '@/runtime/android/androidUpdater'
import { normalizeRuntimeError } from '@/runtime/errors'
import { configureRuntime, getRuntime, resetRuntimeForTests } from '@/runtime/runtimeContext'

function createNative(overrides: Record<string, unknown> = {}): JmcomicClient {
  return {
    ...Object.fromEntries(COMMON_BACKEND_METHODS.map((method) => [method, vi.fn()])),
    addListener: vi.fn(),
    ...overrides,
  } as unknown as JmcomicClient
}

beforeEach(() => {
  resetRuntimeForTests()
})

describe('runtime context', () => {
  test('未注入时失败，并且生产 runtime 只能配置一次', () => {
    expect(() => getRuntime()).toThrow('Frontend runtime is not configured')

    const runtime = {} as Parameters<typeof configureRuntime>[0]
    configureRuntime(runtime)
    expect(getRuntime()).toBe(runtime)
    expect(() => configureRuntime(runtime)).toThrow('Frontend runtime already configured')
  })
})

describe('Android bridge adapters', () => {
  test('只绑定 common backend allowlist', async () => {
    const search = vi.fn().mockResolvedValue({ content: [] })
    const native = createNative({ search })
    const backend = createAndroidBackendClient(native)

    await backend.search({
      query: { orderBy: 'mr', time: 'a', searchMainTag: 0, keyword: 'test' },
    })

    expect(search).toHaveBeenCalledOnce()
    expect(Object.keys(backend)).toEqual([...COMMON_BACKEND_METHODS])
    expect('setDownloadPublic' in backend).toBe(false)
    expect('addListener' in backend).toBe(false)
  })

  test('具名事件 handle 的 remove 幂等', async () => {
    let callback: ((event: { photoId: string }) => void) | undefined
    const remove = vi.fn().mockResolvedValue(undefined)
    const native = createNative({
      addListener: vi.fn().mockImplementation((_event, handler) => {
        callback = handler
        return Promise.resolve({ remove })
      }),
    })
    const events = createAndroidBackendEvents(native)
    const handler = vi.fn()
    const listener = await events.onImageReady(handler)

    callback?.({ photoId: 'chapter-1' })
    await listener.remove()
    await listener.remove()

    expect(handler).toHaveBeenCalledWith({ photoId: 'chapter-1' })
    expect(remove).toHaveBeenCalledOnce()
  })

  test('Android 资源解析保持现有虚拟 URL 和 native render fallback', async () => {
    const renderPdfPage = vi.fn().mockResolvedValue({ imageUrl: 'data:image/png;base64,abc' })
    const native = createNative({ renderPdfPage })
    const resources = createAndroidResourceResolver(native)

    expect(resources.imageUrl({ photoId: 'p1', sortOrder: 2, type: 'thumb' })).toBe(
      'https://jqviewer.local/thumb/p1/2',
    )
    expect(resources.pdfDocumentUrl('/books/a.pdf' as never)).toMatch(
      /^https:\/\/jqviewer\.local\/pdf\//,
    )
    expect(resources.renderPdfPage.available).toBe(true)
    if (resources.renderPdfPage.available) {
      await expect(
        resources.renderPdfPage.api.getUrl({ file: '/books/a.pdf' as never, page: 3, targetWidth: 900 }),
      ).resolves.toBe('data:image/png;base64,abc')
    }
    expect(renderPdfPage).toHaveBeenCalledWith({
      filePath: '/books/a.pdf',
      page: 3,
      targetWidth: 900,
    })
  })

  test('Android 文件和 PDF adapter 将 raw path 映射为 FileRef/displayPath', async () => {
    const native = createNative({
      pickFolder: vi.fn().mockResolvedValue({
        path: '/storage/emulated/0/Books',
        treeUri: 'content://tree/books',
        cancelled: false,
      }),
      scanPdfFiles: vi.fn().mockResolvedValue({
        files: [{ fileName: 'book.pdf', filePath: '/storage/emulated/0/Books/book.pdf' }],
      }),
      getImportedPdfs: vi.fn().mockResolvedValue({
        pdfs: [{ id: 1, filePath: '/storage/emulated/0/Books/book.pdf', fileName: 'book.pdf' }],
      }),
    })
    const events = createAndroidBackendEvents(native)
    const services = createAndroidPlatformServices(native, events)

    const folder = await services.files.pickFolder('pdf-root')
    expect(folder).toEqual({
      ref: 'content://tree/books',
      displayPath: '/storage/emulated/0/Books',
    })
    const scanned = await services.pdf.scanPdfFiles(folder!.ref)
    expect(scanned.files[0]).toEqual({
      ref: '/storage/emulated/0/Books/book.pdf',
      fileName: 'book.pdf',
      displayPath: '/storage/emulated/0/Books/book.pdf',
    })
    const imported = await services.pdf.getImportedPdfs()
    expect(imported.pdfs[0]).toMatchObject({
      fileRef: '/storage/emulated/0/Books/book.pdf',
      displayPath: '/storage/emulated/0/Books/book.pdf',
    })
  })

  test('updater action 带 revision 且同一 action 幂等', async () => {
    const getUpdateState = vi.fn().mockResolvedValue({
      revision: 4,
      phase: 'install_permission_required',
      source: '',
      githubBytes: 0,
      giteeBytes: 0,
      totalBytes: 0,
      speedBytesPerSecond: 0,
      error: '',
    })
    const requestInstallPermission = vi.fn().mockResolvedValue({ requested: true })
    const native = createNative({ getUpdateState, requestInstallPermission })
    const updater = createAndroidUpdater(native)
    const state = await updater.getState()

    expect(state.requiredUserAction).toEqual({
      id: 'grant-install-permission:4',
      kind: 'grant-install-permission',
      stateRevision: 4,
    })
    await updater.performUserAction(state.requiredUserAction!)
    await updater.performUserAction(state.requiredUserAction!)
    expect(requestInstallPermission).toHaveBeenCalledOnce()
  })

  test('过期 updater action 被拒绝为 conflict', async () => {
    const native = createNative({
      getUpdateState: vi.fn().mockResolvedValue({
        revision: 5,
        phase: 'install_permission_required',
        source: '',
        githubBytes: 0,
        giteeBytes: 0,
        totalBytes: 0,
        speedBytesPerSecond: 0,
        error: '',
      }),
    })
    const updater = createAndroidUpdater(native)

    await expect(
      updater.performUserAction({
        id: 'grant-install-permission:4',
        kind: 'grant-install-permission',
        stateRevision: 4,
      }),
    ).rejects.toMatchObject({ code: 'conflict' })
  })
})

describe('listener helper', () => {
  test('底层 remove 只执行一次', async () => {
    const remove = vi.fn().mockResolvedValue(undefined)
    const handle = createIdempotentListenerHandle(remove)
    await Promise.all([handle.remove(), handle.remove(), handle.remove()])
    expect(remove).toHaveBeenCalledOnce()
  })
})

describe('runtime errors', () => {
  test('未知 rejection 不根据自然语言猜测业务 code', () => {
    expect(normalizeRuntimeError(new Error('permission denied')).code).toBe('internal')
    expect(normalizeRuntimeError({ errorCode: 'not-found', message: 'missing' }).code).toBe(
      'not-found',
    )
  })
})
