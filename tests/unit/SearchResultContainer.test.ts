import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, describe, expect, test, vi } from 'vitest'

vi.mock('@ionic/vue', () => ({
  IonSpinner: defineComponent({ name: 'IonSpinner', setup: () => () => h('span') }),
}))

import SearchResultContainer from '@/components/search/SearchResultContainer.vue'

const items = [
  {
    item: { id: '111111', title: '第一个', coverUrl: '', authors: [], tags: [] },
    page: 1,
    indexInPage: 0,
  },
  {
    item: { id: '222222', title: '第二个', coverUrl: '', authors: [], tags: [] },
    page: 1,
    indexInPage: 1,
  },
]

const result = {
  currentPage: 1,
  totalItems: 2,
  totalPages: 1,
  content: [],
}

describe('SearchResultContainer 活动项', () => {
  test('只给 activeEntryKey 对应的卡片增加活动 class', () => {
    const wrapper = mount(SearchResultContainer, {
      props: {
        result,
        items,
        loading: false,
        errorMessage: '',
        mode: 'list',
        activeEntryKey: '1-1-222222',
      },
    })

    const cards = wrapper.findAll('[data-entry-key]')
    expect(cards[0].classes()).not.toContain('entry-highlighted')
    expect(cards[1].classes()).toContain('entry-highlighted')
  })

  test('长按进入选择后抑制普通点击，短按仍触发原卡片行为', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SearchResultContainer, {
      props: { result, items, loading: false, errorMessage: '', mode: 'list' },
    })
    const firstCard = wrapper.find('[data-entry-key="1-0-111111"]')
    await firstCard.trigger('pointerdown', { button: 0, isPrimary: true })
    await vi.advanceTimersByTimeAsync(500)
    await firstCard.trigger('pointerup')
    await firstCard.trigger('click')
    expect(wrapper.emitted('long-press')?.[0]?.[0]).toEqual(items[0].item)
    expect(wrapper.emitted('item-click')).toBeUndefined()

    await firstCard.trigger('pointerdown', { button: 0, isPrimary: true })
    await firstCard.trigger('pointerup')
    await firstCard.trigger('click')
    expect(wrapper.emitted('item-click')?.[0]?.[0]).toEqual(items[0].item)
    wrapper.unmount()
    vi.useRealTimers()
  })

  test('卡片滑动超过阈值后取消长按', async () => {
    vi.useFakeTimers()
    const wrapper = mount(SearchResultContainer, {
      props: { result, items, loading: false, errorMessage: '', mode: 'list' },
    })
    const firstCard = wrapper.find('[data-entry-key="1-0-111111"]')
    await firstCard.trigger('pointerdown', { button: 0, isPrimary: true, clientX: 20, clientY: 20 })
    await firstCard.trigger('pointermove', { clientX: 20, clientY: 40 })
    await vi.advanceTimersByTimeAsync(500)

    expect(wrapper.emitted('long-press')).toBeUndefined()
    wrapper.unmount()
    vi.useRealTimers()
  })
})

afterEach(() => vi.useRealTimers())
