export const PICACOMIC_PROVIDER = 'picacomic' as const

export type PicacomicAuthStateName = 'signed_out' | 'authenticating' | 'signed_in' | 'expired'

export type PicacomicErrorCode =
  | 'PICACOMIC_INVALID_ARGUMENT'
  | 'PICACOMIC_AUTH_REQUIRED'
  | 'PICACOMIC_AUTH_EXPIRED'
  | 'PICACOMIC_NOT_FOUND'
  | 'PICACOMIC_RATE_LIMITED'
  | 'PICACOMIC_NETWORK'
  | 'PICACOMIC_INVALID_RESPONSE'
  | 'PICACOMIC_STALE_RESOURCE'
  | 'PICACOMIC_CANCELLED'
  | 'PICACOMIC_UPSTREAM'
  | 'PICACOMIC_INTERNAL'

export interface PicacomicUser {
  id: string
  username: string
}

export interface PicacomicAuthState {
  state: PicacomicAuthStateName
  user?: PicacomicUser
}

export interface PicacomicAlbumRef {
  provider: typeof PICACOMIC_PROVIDER
  albumId: string
}

export interface PicacomicChapterRef extends PicacomicAlbumRef {
  chapterId: string
  order: number
}

export interface PicacomicImageRef {
  imageKey: string
  pageIndex: number
  cacheUrl: string
}

export interface PicacomicCatalogItem {
  ref: PicacomicAlbumRef
  title: string
  authors: string[]
  translator: string
  cover: PicacomicImageRef | null
  pagesCount: number
  finished: boolean
}

export interface PicacomicCatalogPage {
  currentPage: number
  totalPages: number
  totalItems: number
  items: PicacomicCatalogItem[]
}

export interface PicacomicChapterSummary {
  ref: PicacomicChapterRef
  title: string
  updatedAt: string
}

export interface PicacomicAlbumDetail {
  ref: PicacomicAlbumRef
  title: string
  authors: string[]
  translator: string
  categories: string[]
  tags: string[]
  cover: PicacomicImageRef | null
  description: string
  pagesCount: number
  epsCount: number
  finished: boolean
  createdAt: string
  updatedAt: string
  chapters: PicacomicChapterSummary[]
}

export interface PicacomicChapterDetail extends PicacomicChapterSummary {
  contentRevision: string
  isSingleChapterAlbum: boolean
  images: PicacomicImageRef[]
}

export interface PicacomicImageReadyEvent {
  imageKey: string
}

export interface PicacomicImageFailedEvent {
  imageKey: string
  code: PicacomicErrorCode
  retryable: boolean
}

export type PicacomicImageEventName = 'picacomicImageReady' | 'picacomicImageFailed'

export interface PicacomicImageRequestResult {
  cached: string[]
  pending: string[]
}

export interface PicacomicBuildInfo {
  debugUiEnabled: boolean
}

export interface PicacomicListenerHandle {
  remove: () => Promise<void>
}

export interface PicacomicPluginClient {
  getBuildInfo(): Promise<PicacomicBuildInfo>
  getAuthState(): Promise<PicacomicAuthState>
  login(options: { usernameOrEmail: string; password: string }): Promise<PicacomicAuthState>
  logout(): Promise<PicacomicAuthState>
  search(options: {
    query: string
    order?: string
    page?: number
  }): Promise<RawPicacomicCatalogPage>
  categories(options: {
    category: string
    order?: string
    page?: number
  }): Promise<RawPicacomicCatalogPage>
  getAlbum(options: { albumId: string }): Promise<RawPicacomicAlbumDetail>
  getPhoto(options: {
    albumId: string
    chapterId: string
    order: number
  }): Promise<RawPicacomicChapterDetail>
  requestImages(options: {
    imageKeys: string[]
    replacePending?: boolean
  }): Promise<PicacomicImageRequestResult>
  retryImage(options: { imageKey: string }): Promise<PicacomicImageRequestResult>
  addListener(
    event: PicacomicImageEventName,
    handler: (event: PicacomicImageReadyEvent | PicacomicImageFailedEvent) => void,
  ): Promise<PicacomicListenerHandle>
}

export interface RawPicacomicCatalogPage {
  currentPage: number
  totalPages: number
  totalItems: number
  items: RawPicacomicCatalogItem[]
}

export interface RawPicacomicCatalogItem {
  ref?: Partial<PicacomicAlbumRef>
  provider?: string
  albumId?: string
  title?: string
  authors?: unknown
  translator?: string
  cover?: Partial<PicacomicImageRef> | null
  pagesCount?: number
  finished?: boolean
}

export interface RawPicacomicAlbumDetail {
  ref?: Partial<PicacomicAlbumRef>
  provider?: string
  albumId?: string
  title?: string
  authors?: unknown
  translator?: string
  categories?: unknown
  tags?: unknown
  cover?: Partial<PicacomicImageRef> | null
  description?: string
  pagesCount?: number
  epsCount?: number
  finished?: boolean
  createdAt?: string
  updatedAt?: string
  chapters?: RawPicacomicChapterDetail[]
}

export interface RawPicacomicChapterSummary {
  ref?: Partial<PicacomicChapterRef>
  provider?: string
  albumId?: string
  chapterId?: string
  order?: number
  title?: string
  updatedAt?: string
}

export interface RawPicacomicChapterDetail extends RawPicacomicChapterSummary {
  contentRevision?: string
  isSingleChapterAlbum?: boolean
  images?: Array<Partial<PicacomicImageRef>>
}

export type PicacomicRouteName =
  | 'PicacomicLoginPage'
  | 'PicacomicBrowsePage'
  | 'PicacomicAlbumPage'
  | 'PicacomicReaderPage'

export interface PicacomicRedirect {
  name: PicacomicRouteName
  params: Record<string, string>
}

export const PICACOMIC_ROUTE_NAMES: readonly PicacomicRouteName[] = [
  'PicacomicLoginPage',
  'PicacomicBrowsePage',
  'PicacomicAlbumPage',
  'PicacomicReaderPage',
]

export function isPicacomicErrorCode(value: unknown): value is PicacomicErrorCode {
  return (
    typeof value === 'string' &&
    [
      'PICACOMIC_INVALID_ARGUMENT',
      'PICACOMIC_AUTH_REQUIRED',
      'PICACOMIC_AUTH_EXPIRED',
      'PICACOMIC_NOT_FOUND',
      'PICACOMIC_RATE_LIMITED',
      'PICACOMIC_NETWORK',
      'PICACOMIC_INVALID_RESPONSE',
      'PICACOMIC_STALE_RESOURCE',
      'PICACOMIC_CANCELLED',
      'PICACOMIC_UPSTREAM',
      'PICACOMIC_INTERNAL',
    ].includes(value)
  )
}
