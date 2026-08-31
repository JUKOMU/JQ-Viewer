/* eslint-disable vue/one-component-per-file -- Ionic primitives are test fixtures. */
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  route: {
    params: { albumId: 'fixture-album-1', chapterId: 'chapter-1' },
  },
  router: {
    push: vi.fn(() => Promise.resolve()),
    replace: vi.fn(() => Promise.resolve()),
  },
  auth: {
    user: { value: { id: 'fixture-user', username: 'fixture-user' } },
    refresh: vi.fn(() => Promise.resolve({ state: 'signed_in' })),
    login: vi.fn(() => Promise.resolve({ state: 'signed_in' })),
    logout: vi.fn(() => Promise.resolve()),
    consumeRedirect: vi.fn(() => null),
  },
  service: {
    search: vi.fn(),
    categories: vi.fn(),
    getAlbum: vi.fn(),
    getPhoto: vi.fn(),
    createImageScope: vi.fn(),
  },
  scopeHandlers: null as null | { onReady: (event: any) => void; onFailed: (event: any) => void },
  scopeResponse: (keys: string[]) => ({ cached: keys, pending: [] as string[] }),
  scope: null as null | {
    start: ReturnType<typeof vi.fn>
    request: ReturnType<typeof vi.fn>
    retry: ReturnType<typeof vi.fn>
    dispose: ReturnType<typeof vi.fn>
  },
}))

vi.mock('vue-router', () => ({
  useRoute: () => mocks.route,
  useRouter: () => mocks.router,
}))

vi.mock('@ionic/vue', async () => {
  const { defineComponent, h } = await import('vue')
  const ionic = (name: string, tag: string, input = false) =>
    defineComponent({
      name,
      inheritAttrs: false,
      props: { modelValue: { type: [String, Number], default: '' } },
      emits: ['update:modelValue'],
      setup(props, { attrs, emit, slots }) {
        return () =>
          h(
            tag,
            {
              ...attrs,
              ...(input
                ? {
                    value: props.modelValue,
                    onInput: (event: Event) =>
                      emit('update:modelValue', (event.target as HTMLInputElement).value),
                  }
                : {}),
            },
            slots.default?.(),
          )
      },
    })
  return {
    IonButton: ionic('IonButton', 'button'),
    IonButtons: ionic('IonButtons', 'div'),
    IonContent: ionic('IonContent', 'main'),
    IonHeader: ionic('IonHeader', 'header'),
    IonInput: ionic('IonInput', 'input', true),
    IonPage: ionic('IonPage', 'div'),
    IonSpinner: ionic('IonSpinner', 'span'),
    IonTitle: ionic('IonTitle', 'h2'),
    IonToolbar: ionic('IonToolbar', 'div'),
  }
})

vi.mock('@/features/picacomic/auth', () => ({
  usePicacomicAuth: () => mocks.auth,
}))

vi.mock('@/features/picacomic/service', () => ({
  picacomicService: mocks.service,
  picacomicErrorMessage: vi.fn(() => 'Pica fixture error'),
  isPicacomicAuthError: vi.fn(() => false),
}))

vi.mock('@/features/picacomic/routeGate', () => ({
  routeForPicacomicError: vi.fn(() => null),
}))

import PicacomicAlbumPage from '@/features/picacomic/pages/PicacomicAlbumPage.vue'
import PicacomicBrowsePage from '@/features/picacomic/pages/PicacomicBrowsePage.vue'
import PicacomicLoginPage from '@/features/picacomic/pages/PicacomicLoginPage.vue'
import PicacomicReaderPage from '@/features/picacomic/pages/PicacomicReaderPage.vue'

const image = (key: string, pageIndex: number) => ({
  imageKey: key,
  pageIndex,
  cacheUrl: `https://jqviewer.local/picacomic/${key}/${pageIndex}`,
})

const chapterOne = {
  ref: {
    provider: 'picacomic' as const,
    albumId: 'fixture-album-1',
    chapterId: 'chapter-1',
    order: 1,
  },
  title: '第一章',
  updatedAt: '2026-08-01',
  contentRevision: 'revision-1',
  isSingleChapterAlbum: false,
  images: [image('pica-fixture-page-1', 1), image('pica-fixture-page-2', 2)],
}

const chapterTwo = {
  ref: {
    provider: 'picacomic' as const,
    albumId: 'fixture-album-1',
    chapterId: 'chapter-2',
    order: 2,
  },
  title: '第二章',
  updatedAt: '2026-08-02',
  contentRevision: 'revision-2',
  isSingleChapterAlbum: false,
  images: [image('pica-fixture-page-3', 1)],
}

const album = {
  ref: { provider: 'picacomic' as const, albumId: 'fixture-album-1' },
  title: 'Fixture Album',
  authors: ['Fixture Author'],
  translator: 'Fixture Team',
  categories: ['debug'],
  tags: ['fixture'],
  cover: image('pica-fixture-cover', 1),
  description: 'A fake album',
  pagesCount: 3,
  epsCount: 2,
  finished: false,
  createdAt: '2026-08-01',
  updatedAt: '2026-08-02',
  chapters: [chapterOne, chapterTwo].map(
    ({ images: _images, contentRevision: _revision, isSingleChapterAlbum: _single, ...summary }) =>
      summary,
  ),
}

const catalogPage = {
  currentPage: 1,
  totalPages: 2,
  totalItems: 2,
  items: [
    {
      ref: album.ref,
      title: album.title,
      authors: album.authors,
      translator: album.translator,
      cover: album.cover,
      pagesCount: album.pagesCount,
      finished: album.finished,
    },
  ],
}

function configureScope() {
  mocks.scopeHandlers = null
  mocks.scope = {
    start: vi.fn(() => Promise.resolve()),
    request: vi.fn((keys: string[]) => Promise.resolve(mocks.scopeResponse(keys))),
    retry: vi.fn((key: string) => {
      mocks.scopeHandlers?.onReady({ imageKey: key })
      return Promise.resolve({ cached: [key], pending: [] })
    }),
    dispose: vi.fn(() => Promise.resolve()),
  }
  mocks.service.createImageScope.mockImplementation((handlers: typeof mocks.scopeHandlers) => {
    mocks.scopeHandlers = handlers
    return mocks.scope
  })
}

beforeEach(() => {
  mocks.route.params = { albumId: 'fixture-album-1', chapterId: 'chapter-1' }
  mocks.router.push.mockClear()
  mocks.router.replace.mockClear()
  mocks.auth.user.value = { id: 'fixture-user', username: 'fixture-user' }
  mocks.auth.refresh.mockReset()
  mocks.auth.refresh.mockResolvedValue({ state: 'signed_in' })
  mocks.auth.login.mockReset()
  mocks.auth.login.mockResolvedValue({ state: 'signed_in' })
  mocks.auth.logout.mockReset()
  mocks.auth.logout.mockResolvedValue(undefined)
  mocks.auth.consumeRedirect.mockReset()
  mocks.auth.consumeRedirect.mockReturnValue(null)
  mocks.service.search.mockReset()
  mocks.service.categories.mockReset()
  mocks.service.getAlbum.mockReset()
  mocks.service.getPhoto.mockReset()
  mocks.service.search.mockResolvedValue(catalogPage)
  mocks.service.categories.mockResolvedValue(catalogPage)
  mocks.service.getAlbum.mockResolvedValue(album)
  mocks.service.getPhoto.mockResolvedValue(chapterOne)
  mocks.scopeResponse = (keys: string[]) => ({ cached: keys, pending: [] })
  configureScope()
})

describe('Picacomic fake debug pages', () => {
  test('login uses the isolated auth controller and structured browse navigation', async () => {
    mocks.auth.refresh.mockResolvedValue({ state: 'signed_out' })
    const wrapper = mount(PicacomicLoginPage)
    await flushPromises()

    await wrapper.find('[data-testid="picacomic-username"]').setValue('fixture-user')
    await wrapper.find('[data-testid="picacomic-password"]').setValue('fixture-password')
    await wrapper.find('[data-testid="picacomic-login-form"]').trigger('submit')
    await flushPromises()

    expect(mocks.auth.login).toHaveBeenCalledWith('fixture-user', 'fixture-password')
    expect(mocks.router.replace).toHaveBeenCalledWith({ name: 'PicacomicBrowsePage' })
    expect(mocks.router.replace.mock.calls[0][0]).not.toHaveProperty('query')
  })

  test('browse covers search, category, empty state, pagination and safe album navigation', async () => {
    const wrapper = mount(PicacomicBrowsePage)
    await flushPromises()

    await wrapper.find('[data-testid="picacomic-search-form"]').trigger('submit')
    await flushPromises()
    expect(mocks.service.search).toHaveBeenCalledWith('', 1)
    expect(wrapper.find('[data-testid="picacomic-results"]').exists()).toBe(true)

    await wrapper.find('[data-testid="picacomic-album-fixture-album-1"]').trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'PicacomicAlbumPage',
      params: { albumId: 'fixture-album-1' },
    })

    await wrapper.find('[data-testid="picacomic-page-next"]').trigger('click')
    await flushPromises()
    expect(mocks.service.search).toHaveBeenCalledWith('', 2)

    mocks.service.categories.mockResolvedValue({ ...catalogPage, items: [] })
    await wrapper.find('[data-testid="picacomic-category-submit"]').trigger('click')
    await flushPromises()
    expect(mocks.service.categories).toHaveBeenCalledWith('all', 1)
    expect(wrapper.find('[data-testid="picacomic-empty"]').exists()).toBe(true)
  })

  test('album exposes chapters and reader navigation carries only stable identity', async () => {
    const wrapper = mount(PicacomicAlbumPage)
    await flushPromises()

    expect(wrapper.find('[data-testid="picacomic-chapters"]').exists()).toBe(true)
    await wrapper.find('[data-testid="picacomic-chapter-chapter-1"]').trigger('click')
    expect(mocks.router.push).toHaveBeenCalledWith({
      name: 'PicacomicReaderPage',
      params: { albumId: 'fixture-album-1', chapterId: 'chapter-1' },
    })
  })

  test('reader isolates failed image events and retries one image through native scope', async () => {
    mocks.scopeResponse = (keys: string[]) => ({ cached: [], pending: keys })
    const wrapper = mount(PicacomicReaderPage)
    await flushPromises()

    expect(wrapper.find('[data-testid="picacomic-reader-images"]').exists()).toBe(true)
    const failedKey = chapterOne.images[0].imageKey
    mocks.scopeHandlers?.onFailed({
      imageKey: failedKey,
      code: 'PICACOMIC_NETWORK',
      retryable: true,
    })
    await nextTick()
    expect(wrapper.find('[data-testid="picacomic-image-failed"]').exists()).toBe(true)

    await wrapper.find('[data-testid="picacomic-retry-1"]').trigger('click')
    await flushPromises()
    expect(mocks.scope?.retry).toHaveBeenCalledWith(failedKey)
    expect(wrapper.find('[data-testid="picacomic-image-failed"]').exists()).toBe(false)

    await wrapper.find('[data-testid="picacomic-next-chapter"]').trigger('click')
    expect(mocks.router.replace).toHaveBeenCalledWith({
      name: 'PicacomicReaderPage',
      params: { albumId: 'fixture-album-1', chapterId: 'chapter-2' },
    })
  })
})
