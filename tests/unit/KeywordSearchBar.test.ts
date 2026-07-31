/* eslint-disable vue/one-component-per-file -- test-only Ionic and child-component fixtures */
import {flushPromises, mount} from '@vue/test-utils'
import {defineComponent, h} from 'vue'
import {beforeEach, describe, expect, test, vi} from 'vitest'

const mocks = vi.hoisted(() => ({
  router: {push: vi.fn(() => Promise.resolve())},
  getAlbum: vi.fn(),
  showToast: vi.fn(() => Promise.resolve()),
}))

vi.mock('vue-router', () => ({
  useRouter: () => mocks.router,
}))

vi.mock('@ionic/vue', () => ({
  IonIcon: defineComponent({
    name: 'IonIcon',
    setup() {
      return () => h('span')
    },
  }),
  IonSearchbar: defineComponent({
    name: 'IonSearchbar',
    props: {modelValue: {type: String, default: ''}},
    emits: ['update:modelValue'],
    setup(props, {emit}) {
      return () =>
        h('input', {
          value: props.modelValue,
          onInput: (event: Event) => emit('update:modelValue', (event.target as HTMLInputElement).value),
        })
    },
  }),
  alertController: {create: vi.fn()},
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {getAlbum: mocks.getAlbum, pickImageAndOcr: vi.fn()},
  sanitizeError: vi.fn((error: unknown, fallback: string) => String(error || fallback)),
  showToast: mocks.showToast,
}))

vi.mock('@/services/HistoryService', () => ({
  HistoryService: {
    addParseHistory: vi.fn(),
    addSearchHistory: vi.fn(),
    getSearchHistory: vi.fn(() => []),
    clearSearchHistory: vi.fn(),
  },
}))

vi.mock('@/services/SettingsService', () => ({
  SettingsStore: {getOcrEnabled: vi.fn(() => false)},
}))

vi.mock('@/components/history/SearchHistoryDropdown.vue', () => ({
  default: defineComponent({name: 'SearchHistoryDropdown', setup: () => () => null}),
}))

import KeywordSearchBar from '@/components/search/KeywordSearchBar.vue'

const album = {
  id: '123',
  title: '测试本子',
  image: 'cover.jpg',
  authors: ['作者'],
}

describe('KeywordSearchBar 数字 ID 导航', () => {
  beforeEach(() => {
    mocks.router.push.mockClear()
    mocks.getAlbum.mockReset()
    mocks.showToast.mockClear()
  })

  test('有效纯数字输入直接进入详情页', async () => {
    mocks.getAlbum.mockResolvedValue(album)
    const wrapper = mount(KeywordSearchBar)

    await wrapper.find('input').setValue('123')
    await wrapper.get('.search-trigger-btn').trigger('click')
    await flushPromises()

    expect(mocks.router.push).toHaveBeenCalledWith({
      path: '/album/123',
      query: {title: '测试本子', coverUrl: 'cover.jpg', authors: '作者'},
    })
    expect(wrapper.emitted('search')).toBeUndefined()
  })

  test('单个解析提取到有效 ID 后直接进入详情页', async () => {
    mocks.getAlbum.mockResolvedValue(album)
    const wrapper = mount(KeywordSearchBar)

    await wrapper.get('.mode-btn').trigger('click')
    await wrapper.find('input').setValue('abc123')
    await wrapper.get('.search-trigger-btn').trigger('click')
    await flushPromises()

    expect(mocks.getAlbum).toHaveBeenCalledWith('123')
    expect(mocks.router.push).toHaveBeenCalledWith(expect.objectContaining({path: '/album/123'}))
    expect(wrapper.emitted('search')).toBeUndefined()
  })

  test('无效数字 ID 只提示本子不存在，不发生跳转', async () => {
    mocks.getAlbum.mockRejectedValue(new Error('not found'))
    const wrapper = mount(KeywordSearchBar)

    await wrapper.find('input').setValue('999')
    await wrapper.get('.search-trigger-btn').trigger('click')
    await flushPromises()

    expect(mocks.showToast).toHaveBeenCalledWith('本子不存在', 'danger')
    expect(mocks.router.push).not.toHaveBeenCalled()
    expect(wrapper.emitted('search')).toBeUndefined()
  })

  test('返回空详情对象时只提示本子不存在，不发生跳转', async () => {
    mocks.getAlbum.mockResolvedValue({id: '', title: ''})
    const wrapper = mount(KeywordSearchBar)

    await wrapper.find('input').setValue('999')
    await wrapper.get('.search-trigger-btn').trigger('click')
    await flushPromises()

    expect(mocks.showToast).toHaveBeenCalledWith('本子不存在', 'danger')
    expect(mocks.router.push).not.toHaveBeenCalled()
    expect(wrapper.emitted('search')).toBeUndefined()
  })

  test('普通关键词仍发出搜索事件', async () => {
    const wrapper = mount(KeywordSearchBar)

    await wrapper.find('input').setValue('作者')
    await wrapper.get('.search-trigger-btn').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('search')).toHaveLength(1)
    expect(mocks.getAlbum).not.toHaveBeenCalled()
  })
})
