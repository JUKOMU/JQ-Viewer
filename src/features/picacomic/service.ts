import { picacomicNativeClient } from './client'
import { createPicacomicFixtureClient } from './fixture'
import { PicacomicServiceError } from './errors'
import type {
  PicacomicAlbumDetail,
  PicacomicAlbumRef,
  PicacomicAuthState,
  PicacomicBuildInfo,
  PicacomicCatalogItem,
  PicacomicCatalogPage,
  PicacomicChapterDetail,
  PicacomicChapterRef,
  PicacomicChapterSummary,
  PicacomicErrorCode,
  PicacomicImageFailedEvent,
  PicacomicImageReadyEvent,
  PicacomicImageRef,
  PicacomicImageRequestResult,
  PicacomicListenerHandle,
  PicacomicPluginClient,
  RawPicacomicAlbumDetail,
  RawPicacomicCatalogItem,
  RawPicacomicChapterDetail,
  RawPicacomicChapterSummary,
} from './types'
import { isPicacomicErrorCode } from './types'

export { PicacomicServiceError } from './errors'

const DEFAULT_ERROR_MESSAGE: Record<PicacomicErrorCode, string> = {
  PICACOMIC_INVALID_ARGUMENT: '请输入有效的 PicaComic 信息',
  PICACOMIC_AUTH_REQUIRED: '请先登录 PicaComic',
  PICACOMIC_AUTH_EXPIRED: '登录已过期，请重新登录',
  PICACOMIC_NOT_FOUND: '没有找到这本漫画',
  PICACOMIC_RATE_LIMITED: '请求过于频繁，请稍后再试',
  PICACOMIC_NETWORK: '网络请求失败，请检查连接后重试',
  PICACOMIC_INVALID_RESPONSE: '服务返回了无法识别的数据',
  PICACOMIC_STALE_RESOURCE: '章节已变化，请刷新目录后重试',
  PICACOMIC_CANCELLED: '请求已取消',
  PICACOMIC_UPSTREAM: 'PicaComic 服务暂时不可用',
  PICACOMIC_INTERNAL: 'PicaComic 功能暂时不可用',
}

export function picacomicErrorMessage(error: unknown, fallback = 'PicaComic 请求失败') {
  const normalized = normalizePicacomicError(error, 'view')
  return DEFAULT_ERROR_MESSAGE[normalized.code] ?? fallback
}

export function isPicacomicAuthError(error: unknown) {
  const code = normalizePicacomicError(error, 'auth').code
  return code === 'PICACOMIC_AUTH_REQUIRED' || code === 'PICACOMIC_AUTH_EXPIRED'
}

export function normalizePicacomicError(error: unknown, operation: string) {
  if (error instanceof PicacomicServiceError) return error

  const candidate = asRecord(error)
  const rawCode = candidate?.code ?? candidate?.errorCode
  if (isPicacomicErrorCode(rawCode)) {
    return new PicacomicServiceError(rawCode, operation, error)
  }

  const status = candidate?.status ?? candidate?.statusCode
  if (status === 401) return new PicacomicServiceError('PICACOMIC_AUTH_EXPIRED', operation, error)
  if (status === 403) return new PicacomicServiceError('PICACOMIC_AUTH_REQUIRED', operation, error)
  if (status === 404) return new PicacomicServiceError('PICACOMIC_NOT_FOUND', operation, error)
  if (status === 429) return new PicacomicServiceError('PICACOMIC_RATE_LIMITED', operation, error)
  if (error instanceof DOMException && error.name === 'AbortError') {
    return new PicacomicServiceError('PICACOMIC_CANCELLED', operation, error)
  }
  return new PicacomicServiceError('PICACOMIC_INTERNAL', operation, error)
}

export interface PicacomicImageScopeHandlers {
  onReady: (event: PicacomicImageReadyEvent) => void
  onFailed: (event: PicacomicImageFailedEvent) => void
}

export interface PicacomicImageScope {
  start: () => Promise<void>
  request: (imageKeys: string[], replacePending?: boolean) => Promise<PicacomicImageRequestResult>
  retry: (imageKey: string) => Promise<PicacomicImageRequestResult>
  dispose: () => Promise<void>
}

export class PicacomicService {
  constructor(readonly client: PicacomicPluginClient) {}

  getBuildInfo(): Promise<PicacomicBuildInfo> {
    return this.client.getBuildInfo()
  }

  async getAuthState(): Promise<PicacomicAuthState> {
    try {
      return normalizeAuthState(await this.client.getAuthState())
    } catch (error) {
      throw normalizePicacomicError(error, 'auth state')
    }
  }

  async login(usernameOrEmail: string, password: string): Promise<PicacomicAuthState> {
    if (!usernameOrEmail.trim() || !password) {
      throw new PicacomicServiceError('PICACOMIC_INVALID_ARGUMENT', 'login')
    }
    try {
      return normalizeAuthState(await this.client.login({ usernameOrEmail, password }))
    } catch (error) {
      throw normalizePicacomicError(error, 'login')
    }
  }

  async logout(): Promise<PicacomicAuthState> {
    try {
      return normalizeAuthState(await this.client.logout())
    } catch (error) {
      throw normalizePicacomicError(error, 'logout')
    }
  }

  async search(query: string, page = 1, order = 'latest'): Promise<PicacomicCatalogPage> {
    try {
      return normalizeCatalogPage(await this.client.search({ query, page, order }))
    } catch (error) {
      throw normalizePicacomicError(error, 'search')
    }
  }

  async categories(category = 'all', page = 1, order = 'latest') {
    try {
      return normalizeCatalogPage(await this.client.categories({ category, page, order }))
    } catch (error) {
      throw normalizePicacomicError(error, 'categories')
    }
  }

  async getAlbum(albumId: string): Promise<PicacomicAlbumDetail> {
    if (!albumId.trim()) throw new PicacomicServiceError('PICACOMIC_INVALID_ARGUMENT', 'album')
    try {
      return normalizeAlbum(await this.client.getAlbum({ albumId }))
    } catch (error) {
      throw normalizePicacomicError(error, 'album')
    }
  }

  async getPhoto(ref: PicacomicChapterRef): Promise<PicacomicChapterDetail> {
    if (!ref.albumId.trim() || !ref.chapterId.trim() || ref.order <= 0) {
      throw new PicacomicServiceError('PICACOMIC_INVALID_ARGUMENT', 'chapter')
    }
    try {
      return normalizeChapter(await this.client.getPhoto(ref))
    } catch (error) {
      throw normalizePicacomicError(error, 'chapter')
    }
  }

  createImageScope(handlers: PicacomicImageScopeHandlers): PicacomicImageScope {
    let active = true
    let started = false
    let startPromise: Promise<void> | null = null
    let readyHandle: PicacomicListenerHandle | null = null
    let failedHandle: PicacomicListenerHandle | null = null
    const acceptedKeys = new Set<string>()

    const onReady = (event: PicacomicImageReadyEvent) => {
      if (active && acceptedKeys.has(event.imageKey)) handlers.onReady(event)
    }
    const onFailed = (event: PicacomicImageFailedEvent) => {
      if (active && acceptedKeys.has(event.imageKey)) handlers.onFailed(event)
    }

    const start = async () => {
      if (!active) throw new PicacomicServiceError('PICACOMIC_CANCELLED', 'images')
      if (started) return startPromise ?? Promise.resolve()
      started = true
      startPromise = Promise.all([
        this.client.addListener('picacomicImageReady', (event) =>
          onReady(event as PicacomicImageReadyEvent),
        ),
        this.client.addListener('picacomicImageFailed', (event) =>
          onFailed(event as PicacomicImageFailedEvent),
        ),
      ])
        .then(([ready, failed]) => {
          readyHandle = ready
          failedHandle = failed
        })
        .catch((error) => {
          throw normalizePicacomicError(error, 'image listener')
        })
      return startPromise
    }

    const request = async (imageKeys: string[], replacePending = false) => {
      await start()
      if (!active) throw new PicacomicServiceError('PICACOMIC_CANCELLED', 'images')
      for (const key of imageKeys) acceptedKeys.add(key)
      try {
        return await this.client.requestImages({ imageKeys, replacePending })
      } catch (error) {
        throw normalizePicacomicError(error, 'images')
      }
    }

    const retry = async (imageKey: string) => {
      await start()
      if (!active) throw new PicacomicServiceError('PICACOMIC_CANCELLED', 'image retry')
      acceptedKeys.add(imageKey)
      try {
        return await this.client.retryImage({ imageKey })
      } catch (error) {
        throw normalizePicacomicError(error, 'image retry')
      }
    }

    const dispose = async () => {
      active = false
      acceptedKeys.clear()
      const handles = [readyHandle, failedHandle].filter(
        (handle): handle is PicacomicListenerHandle => handle !== null,
      )
      readyHandle = null
      failedHandle = null
      await Promise.allSettled(handles.map((handle) => handle.remove()))
    }

    return { start, request, retry, dispose }
  }
}

const defaultPicacomicClient =
  import.meta.env.DEV || import.meta.env.MODE === 'test'
    ? createPicacomicFixtureClient()
    : picacomicNativeClient

export const picacomicService = new PicacomicService(defaultPicacomicClient)

function normalizeAuthState(value: unknown): PicacomicAuthState {
  const record = requireRecord(value, 'auth state')
  const state = record.state
  if (
    state !== 'signed_out' &&
    state !== 'authenticating' &&
    state !== 'signed_in' &&
    state !== 'expired'
  ) {
    throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'auth state')
  }
  const userRecord = asRecord(record.user)
  if (
    state === 'signed_in' &&
    (!userRecord || !text(userRecord.id) || !text(userRecord.username))
  ) {
    throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'auth state')
  }
  return {
    state,
    ...(userRecord
      ? { user: { id: text(userRecord.id), username: text(userRecord.username) } }
      : {}),
  }
}

function normalizeCatalogPage(value: unknown): PicacomicCatalogPage {
  const record = requireRecord(value, 'catalog')
  const currentPage = positiveInteger(record.currentPage)
  const totalPages = nonNegativeInteger(record.totalPages)
  const totalItems = nonNegativeInteger(record.totalItems)
  if (
    !currentPage ||
    totalPages === null ||
    totalItems === null ||
    totalPages < 0 ||
    totalItems < 0 ||
    (totalPages > 0 && currentPage > totalPages)
  ) {
    throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'catalog')
  }
  const rawItems = Array.isArray(record.items) ? record.items : null
  if (!rawItems) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'catalog')
  return {
    currentPage,
    totalPages,
    totalItems,
    items: rawItems.map((item) => normalizeCatalogItem(item)),
  }
}

function normalizeCatalogItem(value: unknown): PicacomicCatalogItem {
  const item = asRecord(value) as RawPicacomicCatalogItem | null
  if (!item) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'catalog')
  const ref = normalizeAlbumRef(item, 'catalog')
  return {
    ref,
    title: text(item.title),
    authors: strings(item.authors),
    translator: text(item.translator),
    cover:
      item.cover === null || item.cover === undefined ? null : normalizeImage(item.cover, 'cover'),
    pagesCount: nonNegativeInteger(item.pagesCount) ?? 0,
    finished: item.finished === true,
  }
}

function normalizeAlbum(value: unknown): PicacomicAlbumDetail {
  const album = asRecord(value) as RawPicacomicAlbumDetail | null
  if (!album) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'album')
  const ref = normalizeAlbumRef(album, 'album')
  const rawChapters = Array.isArray(album.chapters) ? album.chapters : null
  if (!rawChapters) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'album')
  const chapters = rawChapters.map((chapter) => normalizeChapterSummary(chapter, ref.albumId))
  return {
    ref,
    title: text(album.title),
    authors: strings(album.authors),
    translator: text(album.translator),
    categories: strings(album.categories),
    tags: strings(album.tags),
    cover:
      album.cover === null || album.cover === undefined
        ? null
        : normalizeImage(album.cover, 'cover'),
    description: text(album.description),
    pagesCount: nonNegativeInteger(album.pagesCount) ?? 0,
    epsCount: nonNegativeInteger(album.epsCount) ?? chapters.length,
    finished: album.finished === true,
    createdAt: text(album.createdAt),
    updatedAt: text(album.updatedAt),
    chapters: chapters.sort((a, b) => a.ref.order - b.ref.order),
  }
}

function normalizeChapter(value: unknown): PicacomicChapterDetail {
  const chapter = asRecord(value) as RawPicacomicChapterDetail | null
  if (!chapter) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'chapter')
  const summary = normalizeChapterSummary(chapter)
  const rawImages = Array.isArray(chapter.images) ? chapter.images : null
  if (!rawImages || rawImages.length === 0) {
    throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'chapter')
  }
  return {
    ...summary,
    contentRevision: text(chapter.contentRevision),
    isSingleChapterAlbum: chapter.isSingleChapterAlbum === true,
    images: rawImages.map((image) => normalizeImage(image, 'chapter')),
  }
}

function normalizeChapterSummary(value: unknown, albumIdHint = ''): PicacomicChapterSummary {
  const chapter = asRecord(value) as RawPicacomicChapterSummary | null
  if (!chapter) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'chapter')
  const refRecord = asRecord(chapter.ref)
  const albumId = text(refRecord?.albumId ?? chapter.albumId ?? albumIdHint)
  const chapterId = text(refRecord?.chapterId ?? chapter.chapterId)
  const order = positiveInteger(refRecord?.order ?? chapter.order)
  if (!albumId || !chapterId || !order) {
    throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'chapter')
  }
  if (refRecord?.provider !== undefined && refRecord.provider !== 'picacomic') {
    throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', 'chapter')
  }
  return {
    ref: { provider: 'picacomic', albumId, chapterId, order },
    title: text(chapter.title),
    updatedAt: text(chapter.updatedAt),
  }
}

function normalizeAlbumRef(value: unknown, operation: string): PicacomicAlbumRef {
  const source = asRecord(value) as {
    ref?: Partial<PicacomicAlbumRef>
    provider?: string
    albumId?: string
  } | null
  if (!source) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', operation)
  const ref = asRecord(source.ref)
  const provider = ref?.provider ?? source.provider
  const albumId = text(ref?.albumId ?? source.albumId)
  if ((provider !== undefined && provider !== 'picacomic') || !albumId) {
    throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', operation)
  }
  return { provider: 'picacomic', albumId }
}

function normalizeImage(value: unknown, operation: string): PicacomicImageRef {
  const image = asRecord(value) as Partial<PicacomicImageRef> | null
  if (!image) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', operation)
  const imageKey = text(image.imageKey)
  const cacheUrl = text(image.cacheUrl)
  const pageIndex = positiveInteger(image.pageIndex)
  if (!imageKey || !cacheUrl || !pageIndex || !imageKey.startsWith('pica-')) {
    throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', operation)
  }
  return { imageKey, pageIndex, cacheUrl }
}

function strings(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string => typeof item === 'string')
    : []
}

function requireRecord(value: unknown, operation: string): Record<string, any> {
  const record = asRecord(value)
  if (!record) throw new PicacomicServiceError('PICACOMIC_INVALID_RESPONSE', operation)
  return record
}

function asRecord(value: unknown): Record<string, any> | null {
  return value !== null && typeof value === 'object' ? (value as Record<string, any>) : null
}

function text(value: unknown, fallback = '') {
  return typeof value === 'string' ? value : fallback
}

function positiveInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value > 0 ? value : null
}

function nonNegativeInteger(value: unknown): number | null {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null
}
