import { afterEach, describe, expect, test, vi } from 'vitest'
import { createBackendClient } from '@/runtime/desktop/backendClient'
import { createRuntime } from '@/runtime/desktop/createRuntime'
import { RuntimeError } from '@/runtime/errors'
import { asFileRef, asFolderRef } from '@/runtime/FileReferences'

function response(payload: unknown, ok = true, status = 200): Response {
  return {
    ok,
    status,
    json: async () => payload,
  } as Response
}

class FakeEventSource {
  static instances: FakeEventSource[] = []
  readonly url: string
  readonly listeners = new Map<string, Set<EventListener>>()
  readonly close = vi.fn()

  constructor(url: string) {
    this.url = url
    FakeEventSource.instances.push(this)
  }

  addEventListener(name: string, listener: EventListener) {
    const listeners = this.listeners.get(name) ?? new Set<EventListener>()
    listeners.add(listener)
    this.listeners.set(name, listeners)
  }

  removeEventListener(name: string, listener: EventListener) {
    this.listeners.get(name)?.delete(listener)
  }

  emit(name: string, data: unknown) {
    const event = { data: JSON.stringify(data) } as MessageEvent<string>
    for (const listener of this.listeners.get(name) ?? []) listener(event)
  }
}

afterEach(() => {
  vi.unstubAllGlobals()
  FakeEventSource.instances = []
})

describe('runtime', () => {
  test('绑定在线核心方法并发送页面使用的 JSON 参数', async () => {
    const fetcher = vi.fn().mockImplementation((_input, init: RequestInit) => {
      const method = String(init.body).includes('keyword') ? 'search' : 'getInitStatus'
      return Promise.resolve(response(method === 'search' ? { content: [] } : { complete: true }))
    })
    const backend = createBackendClient(fetcher)

    await expect(backend.getInitStatus()).resolves.toEqual({ complete: true })
    await backend.search({
      query: { keyword: 'keyword', orderBy: 'mr', time: 'a', searchMainTag: 0, page: 2 },
    })
    expect(Object.keys(backend)).toEqual([
      'search',
      'categories',
      'getAlbum',
      'getPhoto',
      'getComments',
      'preloadImages',
      'retryImage',
      'login',
      'logout',
      'checkLoginState',
      'autoLogin',
      'getUserProfile',
      'getAllSettings',
      'setPreloadConcurrency',
      'setDownloadConcurrency',
      'setReaderPreloadPages',
      'setReaderDisplayMode',
      'setReaderAutoShowToolbarAtEnd',
      'downloadChapter',
      'getDownloadTasks',
      'cancelDownload',
      'pauseDownload',
      'resumeDownload',
      'deleteDownloaded',
      'getDownloadedPhoto',
      'getBrowseHistory',
      'getBrowseHistoryOverview',
      'recordBrowse',
      'clearBrowseHistory',
      'deleteBrowseItem',
      'getInitStatus',
    ])
    expect(fetcher).toHaveBeenCalledWith('/api/getInitStatus', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: '{}',
    })
    expect(fetcher).toHaveBeenLastCalledWith('/api/search', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        keyword: 'keyword',
        orderBy: 'mr',
        time: 'a',
        searchMainTag: 0,
        page: 2,
      }),
    })
  })

  test('归一化传输和结构化后端错误，不添加回退方法', async () => {
    const fetcher = vi.fn().mockRejectedValue(new Error('connection refused'))
    const backend = createBackendClient(fetcher)

    await expect(backend.getInitStatus()).rejects.toMatchObject({
      code: 'network',
      message: 'connection refused',
    })

    const malformed = createBackendClient(vi.fn().mockResolvedValue(response({ complete: 'yes' })))
    await expect(malformed.getInitStatus()).rejects.toMatchObject({
      code: 'internal',
    })
    expect((backend as unknown as { toggleAlbumLike?: unknown }).toggleAlbumLike).toBeUndefined()
  })

  test('按共享契约转发下载方法', async () => {
    const fetcher = vi.fn().mockResolvedValue(response({ success: true }))
    const backend = createBackendClient(fetcher)

    await backend.downloadChapter({
      albumId: 'album-1',
      chapterId: 'photo-1',
      albumTitle: 'Album',
      chapterTitle: 'Photo',
      coverUrl: 'cover.jpg',
    })
    await backend.getDownloadTasks()
    await backend.cancelDownload({ taskId: 'album-1_photo-1' })
    await backend.pauseDownload({ taskId: 'album-1_photo-1' })
    await backend.resumeDownload({ taskId: 'album-1_photo-1' })
    await backend.deleteDownloaded({ albumId: 'album-1', chapterId: 'photo-1' })
    await backend.getDownloadedPhoto({ albumId: 'album-1', chapterId: 'photo-1' })

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      '/api/downloadChapter',
      '/api/getDownloadTasks',
      '/api/cancelDownload',
      '/api/pauseDownload',
      '/api/resumeDownload',
      '/api/deleteDownloaded',
      '/api/getDownloadedPhoto',
    ])
    expect(fetcher).toHaveBeenNthCalledWith(1, '/api/downloadChapter', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        albumId: 'album-1',
        chapterId: 'photo-1',
        albumTitle: 'Album',
        chapterTitle: 'Photo',
        coverUrl: 'cover.jpg',
      }),
    })
  })

  test('构造指定平台的同源运行时并保留未提供的平台能力状态', () => {
    const runtime = createRuntime(
      'windows',
      vi.fn().mockResolvedValue(response({ complete: true })),
    )

    expect(runtime.platform).toBe('windows')
    expect(runtime.resources.imageUrl({ photoId: 'chapter/1', sortOrder: 2, type: 'thumb' })).toBe(
      '/thumb/chapter%2F1/2',
    )
    expect(runtime.resources.pdfDocumentUrl('file:path:/books/book.pdf' as never)).toBe(
      '/pdf/ZmlsZTpwYXRoOi9ib29rcy9ib29rLnBkZg',
    )
    expect(runtime.resources.renderPdfPage.available).toBe(true)
    expect(runtime.services.storage.available).toBe(false)
    expect(runtime.services.updater.available).toBe(false)
    expect(runtime.services.reader.fullscreen.available).toBe(false)
    expect(runtime.services.notifications.kind).toBe('host-managed')
    expect('files' in runtime.services).toBe(true)
    expect('pdf' in runtime.services).toBe(true)
    expect(runtime.events.onNetworkProbe).toBeTypeOf('function')
  })

  test('Desktop 文件与 PDF 导出设置 adapter 使用 opaque ref 传输', async () => {
    const fetcher = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      switch (String(input)) {
        case '/api/pickFolder':
          return Promise.resolve(response({ ref: 'folder:path:/exports', displayPath: '/exports' }))
        case '/api/getDefaultFolder':
          return Promise.resolve(
            response({ ref: 'folder:path:/downloads', displayPath: '/downloads' }),
          )
        case '/api/checkFilesExist':
          return Promise.resolve(response({ existing: ['file:path:/exports/book.pdf'] }))
        case '/api/scanPdfFiles':
          return Promise.resolve(
            response({
              files: [
                {
                  ref: 'file:path:/exports/book.pdf',
                  fileName: 'book.pdf',
                  displayPath: '/exports/book.pdf',
                },
              ],
            }),
          )
        case '/api/getPdfExportPreferences':
          return Promise.resolve(
            response({
              exportFolder: {
                folderRef: 'folder:path:/exports',
                displayPath: '/exports',
              },
              directoryTemplate: '{author}/{id}',
              fileNameTemplate: '{title}',
            }),
          )
        default:
          return Promise.resolve(response({ success: true }))
      }
    })
    const runtime = createRuntime('linux', fetcher)
    const book = asFileRef('file:path:/exports/book.pdf')
    const missing = asFileRef('file:path:/exports/missing.pdf')

    await expect(runtime.services.files.pickFolder('pdf-export')).resolves.toEqual({
      ref: 'folder:path:/exports',
      displayPath: '/exports',
    })
    await expect(runtime.services.files.getDefaultFolder('download')).resolves.toEqual({
      ref: 'folder:path:/downloads',
      displayPath: '/downloads',
    })
    await expect(runtime.services.files.checkFilesExist([book, missing])).resolves.toEqual({
      existing: [book],
    })
    await expect(runtime.services.files.openFile(book)).resolves.toBeUndefined()
    await expect(runtime.services.files.openContainingFolder(book)).resolves.toBeUndefined()
    await expect(
      runtime.services.files.scanPdfFiles(asFolderRef('folder:path:/exports')),
    ).resolves.toEqual({
      files: [
        {
          ref: book,
          fileName: 'book.pdf',
          displayPath: '/exports/book.pdf',
        },
      ],
    })

    const preferences = runtime.services.pdfExportPreferences
    await expect(preferences.get()).resolves.toEqual({
      exportFolder: { folderRef: 'folder:path:/exports', displayPath: '/exports' },
      directoryTemplate: '{author}/{id}',
      fileNameTemplate: '{title}',
    })
    await preferences.setExportFolder({
      folderRef: asFolderRef('folder:path:/exports/new'),
      displayPath: '/exports/new',
    })
    await preferences.setExportFolder(null)
    await preferences.setDirectoryTemplate('{id}')
    await preferences.setFileNameTemplate(null)

    expect(
      fetcher.mock.calls.map(([url, init]) => [String(url), JSON.parse(String(init?.body))]),
    ).toEqual([
      ['/api/pickFolder', { purpose: 'pdf-export' }],
      ['/api/getDefaultFolder', { purpose: 'download' }],
      ['/api/checkFilesExist', { files: [String(book), String(missing)] }],
      ['/api/openFile', { file: String(book) }],
      ['/api/openContainingFolder', { file: String(book) }],
      ['/api/scanPdfFiles', { folder: 'folder:path:/exports' }],
      ['/api/getPdfExportPreferences', {}],
      [
        '/api/setPdfExportFolder',
        {
          folder: {
            folderRef: 'folder:path:/exports/new',
            displayPath: '/exports/new',
          },
        },
      ],
      ['/api/setPdfExportFolder', { folder: null }],
      ['/api/setPdfExportDirectoryTemplate', { value: '{id}' }],
      ['/api/setPdfExportFileNameTemplate', { value: null }],
    ])
  })

  test('Desktop PDF 导出 adapter 隐藏传输字段并恢复输出文件描述', async () => {
    const rawTask = {
      accepted: true,
      exportId: 'export-1',
      batchId: 'batch-1',
      mode: 'chapter',
      albumId: 'album-1',
      albumTitle: 'Album',
      coverUrl: '',
      authors: 'Alice',
      isSingleEpisode: false,
      chapterId: 'chapter-1',
      displayTitle: 'Chapter 1',
      targetFolderRef: 'folder:path:C:\\Exports',
      targetName: 'nested/book.pdf',
      outputFileRef: 'file:path:C:\\Exports\\nested\\book.pdf',
      displayPath: 'C:\\Exports\\nested\\book.pdf',
      allowOverwrite: false,
      useOriginal: true,
      compressionRatio: 1,
      splitPages: 0,
      status: 'completed',
      phase: 'completed',
      currentPage: 10,
      totalPages: 10,
      currentVolume: 1,
      totalVolumes: 1,
      snapshotRevision: 4,
      cancelRequested: false,
      createdAt: 1,
      updatedAt: 2,
      completedAt: 2,
    }
    const fetcher = vi.fn().mockImplementation((input: RequestInfo | URL) => {
      if (String(input) === '/api/getPdfExportTasks') {
        return Promise.resolve(response({ tasks: [rawTask], nextCursor: 'cursor-2' }))
      }
      if (String(input) === '/api/deletePdfExportTask') {
        return Promise.resolve(response({ success: true }))
      }
      return Promise.resolve(
        response(String(input) === '/api/exportPdfBatch' ? { tasks: [rawTask] } : rawTask),
      )
    })
    const pdf = createRuntime('windows', fetcher).services.pdf
    const task = {
      mode: 'chapter' as const,
      albumId: 'album-1',
      albumTitle: 'Album',
      chapterId: 'chapter-1',
      chapterTitle: 'Chapter 1',
      target: {
        folder: asFolderRef('folder:path:C:\\Exports'),
        relativePath: 'nested/book.pdf',
      },
      displayPath: 'C:\\Exports\\nested\\book.pdf',
      useOriginal: true,
      compressionRatio: 1,
      splitPages: 0,
    }

    const submitted = await pdf.exportPdfBatch({ tasks: [task] })
    const listed = await pdf.getPdfExportTasks({ status: 'completed', limit: 20 })
    const loaded = await pdf.getPdfExportTask('export-1')
    const cancelled = await pdf.cancelPdfExport('export-1')
    const retried = await pdf.retryPdfExport('export-1')
    await expect(pdf.deletePdfExportTask('export-1')).resolves.toEqual({ success: true })

    for (const result of [submitted.tasks[0], listed.tasks[0], loaded, cancelled, retried]) {
      expect(result).toMatchObject({
        exportId: 'export-1',
        outputFile: {
          ref: 'file:path:C:\\Exports\\nested\\book.pdf',
          fileName: 'book.pdf',
          displayPath: 'C:\\Exports\\nested\\book.pdf',
        },
      })
      expect(result).not.toHaveProperty('targetFolderRef')
      expect(result).not.toHaveProperty('targetName')
      expect(result).not.toHaveProperty('outputFileRef')
    }
    expect(listed.nextCursor).toBe('cursor-2')
    expect(
      fetcher.mock.calls.map(([url, init]) => [String(url), JSON.parse(String(init?.body))]),
    ).toEqual([
      [
        '/api/exportPdfBatch',
        {
          tasks: [
            {
              mode: 'chapter',
              albumId: 'album-1',
              albumTitle: 'Album',
              chapterId: 'chapter-1',
              chapterTitle: 'Chapter 1',
              target: {
                folder: 'folder:path:C:\\Exports',
                relativePath: 'nested/book.pdf',
              },
              displayPath: 'C:\\Exports\\nested\\book.pdf',
              useOriginal: true,
              compressionRatio: 1,
              splitPages: 0,
            },
          ],
        },
      ],
      ['/api/getPdfExportTasks', { status: 'completed', limit: 20 }],
      ['/api/getPdfExportTask', { exportId: 'export-1' }],
      ['/api/cancelPdfExport', { exportId: 'export-1' }],
      ['/api/retryPdfExport', { exportId: 'export-1', allowOverwrite: false }],
      ['/api/deletePdfExportTask', { exportId: 'export-1' }],
    ])
  })

  test('Desktop PDF 文件库与页面回退使用统一后端契约', async () => {
    const rawFile = {
      id: 7,
      fileRef: 'file:path:/books/book.pdf',
      displayPath: '/books/book.pdf',
      fileName: 'book.pdf',
      sourceType: 'imported',
      ownership: 'external_reference',
      chapterLinkStatus: 'resolved',
      albumId: 'album-1',
      albumTitle: 'Album',
      coverUrl: '',
      authors: 'Alice',
      chapterId: 'chapter-1',
      chapterTitle: 'Chapter 1',
      chapterSortOrder: 1,
      createdAt: 1,
      fileSize: 100,
      pageCount: 2,
      availability: 'available',
      verificationStatus: 'valid',
      updatedAt: 1,
    }
    const fetcher = vi.fn().mockImplementation((input: RequestInfo | URL, _init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/importPdfs') {
        return Promise.resolve(
          response({
            imported: 1,
            skipped: 0,
            duplicateCount: 0,
            errorCount: 0,
            results: [
              {
                result: 'imported',
                fileRef: rawFile.fileRef,
                displayPath: rawFile.displayPath,
                fileName: rawFile.fileName,
                id: rawFile.id,
              },
            ],
          }),
        )
      }
      if (url === '/api/getPdfFiles') {
        return Promise.resolve(response({ files: [rawFile] }))
      }
      if (url === '/api/deletePdfFile') {
        return Promise.resolve(
          response({
            result: 'deleted',
            id: rawFile.id,
            sourceType: rawFile.sourceType,
            ownership: rawFile.ownership,
            fileRef: rawFile.fileRef,
            displayPath: rawFile.displayPath,
            fileName: rawFile.fileName,
          }),
        )
      }
      if (url === '/api/renderPdfPage') {
        return Promise.resolve(response({ resourceUrl: '/pdf-page/' + 'a'.repeat(64) + '.png' }))
      }
      throw new Error(`unexpected request: ${url}`)
    })
    const runtime = createRuntime('linux', fetcher)
    const item = {
      fileRef: rawFile.fileRef as never,
      displayPath: rawFile.displayPath,
      fileName: rawFile.fileName,
      albumId: rawFile.albumId,
      albumTitle: rawFile.albumTitle,
      coverUrl: '',
      authors: rawFile.authors,
      chapterId: rawFile.chapterId,
      chapterTitle: rawFile.chapterTitle,
      chapterSortOrder: 1,
    }

    await expect(runtime.services.pdf.importPdfs([item])).resolves.toMatchObject({ imported: 1 })
    await expect(runtime.services.pdf.getPdfFiles({ limit: 50 })).resolves.toMatchObject({
      files: [expect.objectContaining({ fileRef: rawFile.fileRef })],
    })
    await expect(runtime.services.pdf.deletePdfFile(rawFile.id)).resolves.toMatchObject({
      result: 'deleted',
      file: { ref: rawFile.fileRef, displayPath: rawFile.displayPath, fileName: rawFile.fileName },
    })
    if (!runtime.resources.renderPdfPage.available) throw new Error('renderer unavailable')
    await expect(
      runtime.resources.renderPdfPage.api.getUrl({
        file: rawFile.fileRef as never,
        page: 1,
        targetWidth: 720,
      }),
    ).resolves.toBe('/pdf-page/' + 'a'.repeat(64) + '.png')

    expect(fetcher.mock.calls.map(([url]) => url)).toEqual([
      '/api/importPdfs',
      '/api/getPdfFiles',
      '/api/deletePdfFile',
      '/api/renderPdfPage',
    ])
    expect(JSON.parse(String(fetcher.mock.calls[0][1]?.body))).toEqual({ items: [item] })
  })

  test('共享一个 SSE 连接，并只在自动重连后通知状态失效', async () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const events = createRuntime('linux').events
    const first = vi.fn(() => {
      throw new Error('listener failed')
    })
    const second = vi.fn()
    const invalidated = vi.fn()
    const firstHandle = await events.onImageReady(first)
    const secondHandle = await events.onImageReady(second)
    const invalidationHandle = await events.onStateInvalidated?.(invalidated)

    expect(FakeEventSource.instances).toHaveLength(1)
    expect(FakeEventSource.instances[0].url).toBe('/events')
    FakeEventSource.instances[0].emit('open', null)
    expect(invalidated).not.toHaveBeenCalled()
    FakeEventSource.instances[0].emit('open', null)
    expect(invalidated).toHaveBeenCalledOnce()
    FakeEventSource.instances[0].emit('imageReady', { photoId: 'p1', sortOrder: 2, type: 'image' })
    expect(first).toHaveBeenCalledWith({ photoId: 'p1', sortOrder: 2, type: 'image' })
    expect(second).toHaveBeenCalledWith({ photoId: 'p1', sortOrder: 2, type: 'image' })

    await firstHandle.remove()
    await firstHandle.remove()
    await secondHandle.remove()
    expect(FakeEventSource.instances[0].close).not.toHaveBeenCalled()
    await invalidationHandle?.remove()
    expect(FakeEventSource.instances[0].close).toHaveBeenCalledOnce()
  })

  test('preserves structured backend error responses', async () => {
    const backend = createBackendClient(
      vi
        .fn()
        .mockResolvedValue(response({ code: 'conflict', message: 'stale request' }, false, 409)),
    )

    await expect(backend.getInitStatus()).rejects.toEqual(
      new RuntimeError('conflict', 'stale request'),
    )
  })
})
