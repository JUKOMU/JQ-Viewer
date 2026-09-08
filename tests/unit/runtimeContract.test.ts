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

function deferred<T>(): {
  promise: Promise<T>
  resolve: (value: T) => void
  reject: (reason?: unknown) => void
} {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

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

  test('Android 资源解析保持虚拟 URL 并只透传 native resourceUrl', async () => {
    const renderPdfPage = vi.fn().mockResolvedValue({
      resourceUrl: 'https://jqviewer.local/pdf-page/' + 'a'.repeat(64) + '.png',
    })
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
        resources.renderPdfPage.api.getUrl({
          file: 'file:path:/books/a.pdf' as never,
          page: 3,
          targetWidth: 900,
        }),
      ).resolves.toBe('https://jqviewer.local/pdf-page/' + 'a'.repeat(64) + '.png')
    }
    expect(renderPdfPage).toHaveBeenCalledWith({
      fileRef: 'file:path:/books/a.pdf',
      page: 3,
      targetWidth: 900,
    })
  })

  test('Android 文件和 PDF adapter 将 raw path 映射为 FileRef/displayPath', async () => {
    const native = createNative({
      pickFolder: vi.fn().mockResolvedValue({
        folderRef: 'folder:saf:content://tree/books',
        displayPath: '/storage/emulated/0/Books',
        provider: 'saf',
        cancelled: false,
      }),
      scanPdfFiles: vi.fn().mockResolvedValue({
        files: [{
          fileName: 'book.pdf',
          fileRef: 'file:saf:content://tree/books/book.pdf',
          displayPath: '/storage/emulated/0/Books/book.pdf',
        }],
      }),
      getImportedPdfs: vi.fn().mockResolvedValue({
        pdfs: [{
          id: 1,
          fileRef: 'file:saf:content://tree/books/book.pdf',
          displayPath: '/storage/emulated/0/Books/book.pdf',
          fileName: 'book.pdf',
        }],
      }),
    })
    const events = createAndroidBackendEvents(native)
    const services = createAndroidPlatformServices(native, events)

    const folder = await services.files.pickFolder('pdf-root')
    expect(folder).toEqual({
      ref: 'folder:saf:content://tree/books',
      displayPath: '/storage/emulated/0/Books',
    })
    const scanned = await services.pdf.scanPdfFiles(folder!.ref)
    expect(scanned.files[0]).toEqual({
      ref: 'file:saf:content://tree/books/book.pdf',
      fileName: 'book.pdf',
      displayPath: '/storage/emulated/0/Books/book.pdf',
    })
    const imported = await services.pdf.getImportedPdfs()
    expect(imported.pdfs[0]).toMatchObject({
      fileRef: 'file:saf:content://tree/books/book.pdf',
      displayPath: '/storage/emulated/0/Books/book.pdf',
    })
    expect('renderPdfPage' in services.pdf).toBe(false)
  })

  test('importPdfs 只接受 fileRef，缺失引用时明确失败', async () => {
    const importPdfs = vi.fn()
    const native = createNative({ importPdfs })
    const events = createAndroidBackendEvents(native)
    const services = createAndroidPlatformServices(native, events)

    const item = {
      fileRef: undefined,
      displayPath: '/fallback/display-path.pdf',
      fileName: 'display-path.pdf',
      albumId: 'album-1',
      albumTitle: '测试漫画',
      coverUrl: '',
      authors: '',
      chapterId: 'chapter-1',
      chapterTitle: '第一话',
      chapterSortOrder: 1,
    }

    await expect(services.pdf.importPdfs([item as never])).rejects.toMatchObject({
      code: 'not-found',
    })
    expect(importPdfs).not.toHaveBeenCalled()
  })

  test('importPdfs 不把空白 fileRef 当作展示路径', async () => {
    const importPdfs = vi.fn()
    const native = createNative({ importPdfs })
    const events = createAndroidBackendEvents(native)
    const services = createAndroidPlatformServices(native, events)

    const item = {
      fileRef: '   ',
      displayPath: '/fallback/display-path.pdf',
      fileName: 'display-path.pdf',
      albumId: 'album-1',
      albumTitle: '测试漫画',
      coverUrl: '',
      authors: '',
      chapterId: 'chapter-1',
      chapterTitle: '第一话',
      chapterSortOrder: 1,
    }

    await expect(services.pdf.importPdfs([item as never])).rejects.toMatchObject({
      code: 'not-found',
    })
    expect(importPdfs).not.toHaveBeenCalled()
  })

  test('Android PDF adapter 只发送目录 ref 与相对 targetName', async () => {
    const exportPdfBatch = vi.fn().mockResolvedValue({ tasks: [] })
    const native = createNative({ exportPdfBatch })
    const events = createAndroidBackendEvents(native)
    const services = createAndroidPlatformServices(native, events)

    await services.pdf.exportPdfBatch({
      tasks: [
        {
          mode: 'merged',
          albumId: 'album-1',
          chapterTitle: '第1-2话',
          target: { folder: 'folder:path:/exports', relativePath: 'merged.pdf' },
          displayPath: '/exports/merged.pdf',
          useOriginal: true,
          compressionRatio: 0.5,
          splitPages: 0,
        },
      ],
    })

    expect(exportPdfBatch).toHaveBeenCalledWith({
      tasks: [
        expect.objectContaining({
          targetFolderRef: 'folder:path:/exports',
          targetName: 'merged.pdf',
          displayPath: '/exports/merged.pdf',
        }),
      ],
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

  test('同一 updater action 并发调用复用进行中的权限请求', async () => {
    const request = deferred<{ requested: boolean }>()
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
    const requestInstallPermission = vi.fn().mockReturnValue(request.promise)
    const native = createNative({ getUpdateState, requestInstallPermission })
    const updater = createAndroidUpdater(native)
    const action = {
      id: 'grant-install-permission:4',
      kind: 'grant-install-permission' as const,
      stateRevision: 4,
    }

    const first = updater.performUserAction(action)
    const second = updater.performUserAction(action)
    await vi.waitFor(() => expect(requestInstallPermission).toHaveBeenCalledOnce())

    request.resolve({ requested: true })
    await expect(Promise.all([first, second])).resolves.toEqual([undefined, undefined])
    await updater.performUserAction(action)
    expect(requestInstallPermission).toHaveBeenCalledOnce()
  })

  test('updater action 失败后清理进行中记录并允许重试', async () => {
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
    const requestInstallPermission = vi
      .fn()
      .mockRejectedValueOnce(new Error('permission request failed'))
      .mockResolvedValueOnce({ requested: true })
    const native = createNative({ getUpdateState, requestInstallPermission })
    const updater = createAndroidUpdater(native)
    const action = {
      id: 'grant-install-permission:4',
      kind: 'grant-install-permission' as const,
      stateRevision: 4,
    }

    await expect(updater.performUserAction(action)).rejects.toMatchObject({ code: 'internal' })
    await expect(updater.performUserAction(action)).resolves.toBeUndefined()
    expect(requestInstallPermission).toHaveBeenCalledTimes(2)
  })

  test('不同 action 不会共享权限请求，过期 action 仍被拒绝', async () => {
    const getUpdateState = vi.fn().mockResolvedValue({
      revision: 5,
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

    await expect(
      updater.performUserAction({
        id: 'grant-install-permission:4',
        kind: 'grant-install-permission',
        stateRevision: 4,
      }),
    ).rejects.toMatchObject({ code: 'conflict' })

    await expect(
      updater.performUserAction({
        id: 'grant-install-permission:5',
        kind: 'grant-install-permission',
        stateRevision: 5,
      }),
    ).resolves.toBeUndefined()
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
  test('支持的 rejection code 会归一化并保留原始 message', () => {
    expect(
      normalizeRuntimeError({ code: 'not-found', message: 'PDF 导出任务不存在' }),
    ).toMatchObject({ code: 'not-found', message: 'PDF 导出任务不存在' })
    expect(
      normalizeRuntimeError({ errorCode: 'permission-denied', message: '权限已失效' }),
    ).toMatchObject({ code: 'permission-denied', message: '权限已失效' })
    expect(normalizeRuntimeError({ code: 'conflict', message: '当前任务状态不能重试' })).toMatchObject({
      code: 'conflict',
      message: '当前任务状态不能重试',
    })
  })

  test('未知 rejection 不根据自然语言猜测业务 code', () => {
    expect(normalizeRuntimeError(new Error('permission denied')).code).toBe('internal')
    expect(normalizeRuntimeError({ code: 'permission denied', message: '权限已失效' })).toMatchObject({
      code: 'internal',
      message: '权限已失效',
    })
  })
})
