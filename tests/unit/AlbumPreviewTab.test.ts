import {mount} from '@vue/test-utils'
import {describe, expect, test} from 'vitest'
import AlbumPreviewTab from '@/components/album/AlbumPreviewTab.vue'

describe('AlbumPreviewTab', () => {
  test('首次点击后切换为上滑自动加载提示', async () => {
    const wrapper = mount(AlbumPreviewTab, {
      props: {
        slots: new Array(20).fill(null),
        totalCount: 40,
        visibleCount: 20,
        allVisible: false,
        autoLoad: false,
        loading: false,
        loadingMore: false,
        loadedCount: 20,
      },
    })

    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)

    await wrapper.setProps({autoLoad: true})
    expect(wrapper.find('button').exists()).toBe(true)
    expect(wrapper.get('button').attributes('aria-disabled')).toBe('true')
    expect(wrapper.text()).toContain('上滑加载更多...')

    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)

    await wrapper.setProps({loadedCount: 19})
    expect(wrapper.text()).toContain('上滑重新加载缺失图片...（19 / 20）')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('loadMore')).toHaveLength(1)
  })

  test('加载状态保留按钮焦点并通过 live region 播报', async () => {
    const wrapper = mount(AlbumPreviewTab, {
      attachTo: document.body,
      props: {
        slots: new Array(20).fill(null),
        totalCount: 40,
        visibleCount: 20,
        allVisible: false,
        autoLoad: false,
        loading: false,
        loadingMore: false,
        loadedCount: 20,
      },
    })

    const button = wrapper.get('button')
    const buttonElement = button.element as HTMLButtonElement
    buttonElement.focus()
    expect(document.activeElement).toBe(buttonElement)

    await button.trigger('click')
    await wrapper.setProps({loadingMore: true})

    expect(wrapper.get('button').element).toBe(buttonElement)
    expect(document.activeElement).toBe(buttonElement)
    expect(wrapper.get('[role="status"]').attributes('aria-live')).toBe('polite')
    expect(wrapper.get('[role="status"]').text()).toContain('正在加载')

    await wrapper.setProps({loadingMore: false, autoLoad: true})
    expect(wrapper.get('button').element).toBe(buttonElement)
    expect(document.activeElement).toBe(buttonElement)

    await wrapper.setProps({autoLoad: false, allVisible: true})
    expect(wrapper.get('button').element).toBe(buttonElement)
    expect(document.activeElement).toBe(buttonElement)
    expect(wrapper.get('[role="status"]').text()).toContain('已显示所有')

    wrapper.unmount()
  })
})
