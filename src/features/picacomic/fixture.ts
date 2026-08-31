import { PicacomicServiceError } from './errors'
import type {
  PicacomicAuthState,
  PicacomicImageFailedEvent,
  PicacomicImageReadyEvent,
  PicacomicImageRequestResult,
  PicacomicPluginClient,
  RawPicacomicAlbumDetail,
  RawPicacomicCatalogPage,
  RawPicacomicChapterDetail,
} from './types'

export interface PicacomicFixtureOptions {
  initiallySignedIn?: boolean
  failImageKeys?: string[]
  deferImageEvents?: boolean
}

type ImageListener = (event: PicacomicImageReadyEvent | PicacomicImageFailedEvent) => void

export function createPicacomicFixtureClient(
  options: PicacomicFixtureOptions = {},
): PicacomicPluginClient & {
  readonly calls: string[]
  readonly imageKeys: readonly string[]
  readonly flushImageEvents: () => void
} {
  const listeners = new Map<'picacomicImageReady' | 'picacomicImageFailed', Set<ImageListener>>([
    ['picacomicImageReady', new Set()],
    ['picacomicImageFailed', new Set()],
  ])
  const calls: string[] = []
  const loadedImages = new Set<string>()
  const pendingImages = new Set<string>()
  const failedOnce = new Set<string>()
  const failImageKeys = new Set(options.failImageKeys ?? [])
  const deferredImageEvents: Array<() => void> = []
  let authState: PicacomicAuthState = options.initiallySignedIn
    ? { state: 'signed_in', user: { id: 'fixture-user', username: 'fixture-user' } }
    : { state: 'signed_out' }

  const albums = [
    createAlbum('fixture-album-1', '月光档案', '作者甲'),
    createAlbum('fixture-album-2', '纸上花园', '作者乙'),
  ]
  const imageMap = new Map<string, { cacheUrl: string }>()
  for (const album of albums) {
    for (const chapter of album.chapters ?? []) {
      for (const image of chapter.images ?? []) {
        if (image.imageKey && image.cacheUrl)
          imageMap.set(image.imageKey, { cacheUrl: image.cacheUrl })
      }
    }
    if (album.cover?.imageKey && album.cover.cacheUrl) {
      imageMap.set(album.cover.imageKey, { cacheUrl: album.cover.cacheUrl })
    }
  }

  const emit = (
    eventName: 'picacomicImageReady' | 'picacomicImageFailed',
    event: PicacomicImageReadyEvent | PicacomicImageFailedEvent,
  ) => {
    for (const listener of listeners.get(eventName) ?? []) listener(event)
  }

  const ensureSignedIn = () => {
    if (authState.state !== 'signed_in') {
      throw new PicacomicServiceError('PICACOMIC_AUTH_REQUIRED', 'fixture')
    }
  }

  const scenario = (value: string) => {
    switch (value.trim().toLowerCase()) {
      case '401':
        throw new PicacomicServiceError('PICACOMIC_AUTH_EXPIRED', 'fixture')
      case '403':
        throw new PicacomicServiceError('PICACOMIC_AUTH_REQUIRED', 'fixture')
      case 'network':
        throw new PicacomicServiceError('PICACOMIC_NETWORK', 'fixture')
      case 'parse':
        throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'fixture')
      default:
        return
    }
  }

  const fixture: PicacomicPluginClient & {
    readonly calls: string[]
    readonly imageKeys: readonly string[]
    readonly flushImageEvents: () => void
  } = {
    calls,
    get imageKeys() {
      return [...imageMap.keys()]
    },
    flushImageEvents() {
      const events = deferredImageEvents.splice(0)
      for (const event of events) queueMicrotask(event)
    },
    async getBuildInfo() {
      calls.push('getBuildInfo')
      return { debugUiEnabled: true }
    },
    async getAuthState() {
      calls.push('getAuthState')
      return authState
    },
    async login({ usernameOrEmail }: { usernameOrEmail: string; password: string }) {
      calls.push('login')
      scenario(usernameOrEmail)
      authState = {
        state: 'signed_in',
        user: { id: 'fixture-user', username: usernameOrEmail },
      }
      return authState
    },
    async logout() {
      calls.push('logout')
      authState = { state: 'signed_out' }
      loadedImages.clear()
      pendingImages.clear()
      return authState
    },
    async search({ query, page = 1 }: { query: string; order?: string; page?: number }) {
      calls.push(`search:${query}:${page}`)
      ensureSignedIn()
      scenario(query)
      if (query.trim().toLowerCase() === 'empty') return emptyPage(page)
      return catalogPage(page)
    },
    async categories({ category, page = 1 }: { category: string; order?: string; page?: number }) {
      calls.push(`categories:${category}:${page}`)
      ensureSignedIn()
      scenario(category)
      if (category.trim().toLowerCase() === 'empty') return emptyPage(page)
      return catalogPage(page)
    },
    async getAlbum({ albumId }: { albumId: string }) {
      calls.push(`getAlbum:${albumId}`)
      ensureSignedIn()
      const album = albums.find((candidate) => candidate.albumId === albumId)
      if (!album) throw new PicacomicServiceError('PICACOMIC_NOT_FOUND', 'fixture')
      return album
    },
    async getPhoto({
      albumId,
      chapterId,
      order,
    }: {
      albumId: string
      chapterId: string
      order: number
    }) {
      calls.push(`getPhoto:${albumId}:${chapterId}:${order}`)
      ensureSignedIn()
      if (chapterId === 'stale')
        throw new PicacomicServiceError('PICACOMIC_STALE_RESOURCE', 'fixture')
      const album = albums.find((candidate) => candidate.albumId === albumId)
      const chapter = album?.chapters?.find(
        (candidate) => candidate.ref?.chapterId === chapterId && candidate.ref.order === order,
      )
      if (!chapter) throw new PicacomicServiceError('PICACOMIC_STALE_RESOURCE', 'fixture')
      return chapter
    },
    async requestImages({
      imageKeys,
      replacePending = false,
    }): Promise<PicacomicImageRequestResult> {
      calls.push(`requestImages:${imageKeys.join(',')}:${replacePending}`)
      ensureSignedIn()
      const cached: string[] = []
      const pending: string[] = []
      for (const imageKey of [...new Set(imageKeys)]) {
        if (!imageMap.has(imageKey))
          throw new PicacomicServiceError('PICACOMIC_INVALID_ARGUMENT', 'fixture')
        if (loadedImages.has(imageKey)) {
          cached.push(imageKey)
          continue
        }
        if (pendingImages.has(imageKey) && !replacePending) {
          pending.push(imageKey)
          continue
        }
        pendingImages.add(imageKey)
        pending.push(imageKey)
        const emitImageEvent = () => {
          pendingImages.delete(imageKey)
          if (failImageKeys.has(imageKey) && !failedOnce.has(imageKey)) {
            failedOnce.add(imageKey)
            emit('picacomicImageFailed', {
              imageKey,
              code: 'PICACOMIC_NETWORK',
              retryable: true,
            })
            return
          }
          loadedImages.add(imageKey)
          emit('picacomicImageReady', { imageKey })
        }
        if (options.deferImageEvents) deferredImageEvents.push(emitImageEvent)
        else queueMicrotask(emitImageEvent)
      }
      return { cached, pending }
    },
    async retryImage({ imageKey }) {
      calls.push(`retryImage:${imageKey}`)
      return fixture.requestImages({ imageKeys: [imageKey], replacePending: true })
    },
    async addListener(event, handler) {
      const bucket = listeners.get(event)
      if (!bucket) throw new PicacomicServiceError('PICACOMIC_INTERNAL', 'listener')
      bucket.add(handler)
      return {
        remove: async () => {
          bucket.delete(handler)
        },
      }
    },
  }

  return fixture

  function catalogPage(page: number): RawPicacomicCatalogPage {
    const safePage = page === 2 ? 2 : 1
    const album = albums[safePage - 1]
    return {
      currentPage: safePage,
      totalPages: 2,
      totalItems: albums.length,
      items: [
        {
          provider: 'picacomic',
          albumId: album.albumId,
          title: album.title,
          authors: album.authors,
          translator: album.translator,
          cover: album.cover,
          pagesCount: album.pagesCount,
          finished: album.finished,
        },
      ],
    }
  }

  function emptyPage(page: number): RawPicacomicCatalogPage {
    return { currentPage: Math.max(1, page), totalPages: 1, totalItems: 0, items: [] }
  }
}

function createAlbum(id: string, title: string, author: string): RawPicacomicAlbumDetail {
  const cover = createImage(`${id}-cover`, 1, `${id}/cover.jpg`)
  const chapterOne = createChapter(id, 'chapter-1', 1, '第一章', 3)
  const chapterTwo = createChapter(id, 'chapter-2', 2, '第二章', 2)
  return {
    provider: 'picacomic',
    albumId: id,
    title,
    authors: [author],
    translator: 'Fixture Team',
    categories: ['debug'],
    tags: ['fixture', '只读'],
    cover,
    description: '仅用于 CP3 的本地 fake 只读闭环。',
    pagesCount: 5,
    epsCount: 2,
    finished: false,
    createdAt: '2026-08-01',
    updatedAt: '2026-08-02',
    chapters: [chapterTwo, chapterOne],
  }
}

function createChapter(
  albumId: string,
  chapterId: string,
  order: number,
  title: string,
  pageCount: number,
): RawPicacomicChapterDetail {
  return {
    provider: 'picacomic',
    albumId,
    chapterId,
    order,
    title,
    updatedAt: `2026-08-0${order}`,
    contentRevision: `fixture-revision-${order}`,
    isSingleChapterAlbum: false,
    images: Array.from({ length: pageCount }, (_, index) =>
      createImage(
        `${albumId}-${chapterId}-${index + 1}`,
        index + 1,
        `${albumId}/${chapterId}/${index + 1}.png`,
      ),
    ),
  }
}

function createImage(imageKeySuffix: string, pageIndex: number, path: string) {
  return {
    imageKey: `pica-fixture-${imageKeySuffix}`,
    pageIndex,
    cacheUrl: `https://jqviewer.local/picacomic/pica-fixture-${imageKeySuffix}/${pageIndex}`,
    path,
  }
}
