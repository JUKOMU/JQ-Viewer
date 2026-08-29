import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { describe, expect, test, vi } from 'vitest'

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
})
