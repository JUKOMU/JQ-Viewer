/* eslint-disable vue/one-component-per-file -- test-only Ionic fixture */
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { describe, expect, test, vi } from 'vitest'

vi.mock('@ionic/vue', () => ({
  IonIcon: defineComponent({
    name: 'IonIcon',
    setup: () => () => h('span'),
  }),
  IonSpinner: defineComponent({
    name: 'IonSpinner',
    setup: () => () => h('span'),
  }),
}))

vi.mock('ionicons/icons', () => ({
  addCircleOutline: 'add-circle',
  closeOutline: 'close',
  folderOpenOutline: 'folder',
}))

import FavoriteFolderPicker from '@/components/favorite/FavoriteFolderPicker.vue'

const baseProps = {
  modelValue: true,
  onlineFolders: [{ id: 'online-1', name: '在线收藏夹', count: 0 }],
  offlineFolders: [{ id: 'offline-1', name: '离线收藏夹', count: 2 }],
  onlineFolderCounts: { 'online-1': 4 },
}

describe('FavoriteFolderPicker 新建入口', () => {
  test('在线和离线按钮分别发出对应来源', async () => {
    const wrapper = mount(FavoriteFolderPicker, { props: baseProps })

    const addButtons = wrapper.findAll('.section-add-btn')
    expect(addButtons).toHaveLength(2)
    expect(addButtons[0].element.parentElement?.textContent).toContain('在线收藏夹')
    expect(addButtons[0].element.parentElement?.lastElementChild).toBe(addButtons[0].element)
    expect(addButtons[1].element.parentElement?.textContent).toContain('离线收藏夹')
    expect(addButtons[1].element.parentElement?.lastElementChild).toBe(addButtons[1].element)

    await addButtons[0].trigger('click')
    await addButtons[1].trigger('click')

    expect(wrapper.emitted('add-folder')).toEqual([['online'], ['offline']])
  })

  test('隐藏在线入口时只显示离线新建按钮', () => {
    const wrapper = mount(FavoriteFolderPicker, {
      props: { ...baseProps, hideOnline: true },
    })

    expect(wrapper.find('[aria-label="新建在线收藏夹"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="新建离线收藏夹"]').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('在线收藏夹')
  })

  test('列表为空时仍保留可用的新建入口', () => {
    const wrapper = mount(FavoriteFolderPicker, {
      props: {
        ...baseProps,
        onlineFolders: [],
        offlineFolders: [],
      },
    })

    expect(wrapper.find('[aria-label="新建在线收藏夹"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="新建离线收藏夹"]').exists()).toBe(true)
    expect(wrapper.find('.empty-state').exists()).toBe(true)
  })

  test('首次加载立即显示加载状态而不是伪空态', () => {
    const wrapper = mount(FavoriteFolderPicker, {
      props: {
        ...baseProps,
        onlineFolders: [],
        onlineHasSuccessfulData: false,
        onlineLoading: true,
        onlineRefreshing: true,
      },
    })

    expect(wrapper.find('.picker-panel').exists()).toBe(true)
    expect(wrapper.find('.loading-state').exists()).toBe(true)
    expect(wrapper.find('.empty-state').exists()).toBe(false)
  })

  test('缓存刷新失败时保留旧列表并提供重试', async () => {
    const wrapper = mount(FavoriteFolderPicker, {
      props: {
        ...baseProps,
        onlineHasSuccessfulData: true,
        onlineErrorMessage: '网络不可用',
      },
    })

    expect(wrapper.text()).toContain('在线收藏夹')
    expect(wrapper.text()).toContain('网络不可用')
    expect(wrapper.find('.refresh-error').exists()).toBe(true)

    await wrapper.get('.retry-btn').trigger('click')
    expect(wrapper.emitted('retry-online')).toEqual([[]])
  })
})
