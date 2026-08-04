import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import HorizontalPageView from '@/components/reader/HorizontalPageView.vue'

describe('HorizontalPageView', () => {
  test('上报图片加载失败并提供重试操作', async () => {
    const wrapper = mount(HorizontalPageView, {
      props: {
        imageMap: new Map<number, string>([[1, 'https://jqviewer.local/image/photo/1']]),
        failedSortOrders: new Set<number>(),
        retryingSortOrders: new Set<number>(),
        totalCount: 1,
        currentIndex: 0,
      },
    })

    await wrapper.get('.page-image').trigger('error')
    expect(wrapper.emitted('image-error')).toEqual([[1]])

    await wrapper.setProps({ failedSortOrders: new Set([1]) })
    const retry = wrapper.get('.image-retry-button')
    expect(retry.text()).toContain('重试')
    await retry.trigger('click')
    expect(wrapper.emitted('retry-image')).toEqual([[1]])

    await wrapper.setProps({ retryingSortOrders: new Set([1]) })
    expect(wrapper.get('.image-retry-button').attributes('disabled')).toBeDefined()
    expect(wrapper.get('.image-retry-button').text()).toContain('重试中')
    wrapper.unmount()
  })
})
