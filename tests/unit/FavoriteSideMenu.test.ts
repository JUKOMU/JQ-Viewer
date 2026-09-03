/* eslint-disable vue/one-component-per-file -- test-only Ionic and child-component fixtures */
import { defineComponent, h, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

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

vi.mock('@/components/common/CardContextMenu.vue', () => ({
  default: defineComponent({
    name: 'CardContextMenu',
    props: {
      visible: Boolean,
    },
    setup(props) {
      return () => (props.visible ? h('div', { class: 'card-context-menu-stub' }) : null)
    },
  }),
}))

import FavoriteSideMenu from '@/components/favorite/FavoriteSideMenu.vue'
import {
  isDraggingRight,
  isSnappingClosed,
  rightDragProgress,
  rightMenuOpen,
} from '@/composables/useSideMenuState'

const baseProps = {
  modelValue: false,
  onlineFolders: [{ id: 'online-1', name: '在线收藏', count: 0 }],
  offlineFolders: [{ id: 'offline-1', name: '离线收藏', count: 3 }],
  selectedOnlineId: '',
  selectedOfflineId: 'offline-1',
  onlineFolderCounts: { 'online-1': 5 },
}

beforeEach(() => {
  vi.useFakeTimers()
  isDraggingRight.value = false
  isSnappingClosed.value = false
  rightDragProgress.value = 0
  rightMenuOpen.value = false
})

afterEach(() => {
  vi.runOnlyPendingTimers()
  vi.useRealTimers()
  isDraggingRight.value = false
  isSnappingClosed.value = false
  rightDragProgress.value = 0
  rightMenuOpen.value = false
})

describe('FavoriteSideMenu 展示模式', () => {
  test('overlay 模式选择收藏夹后沿用关闭动画', async () => {
    const wrapper = mount(FavoriteSideMenu, {
      props: { ...baseProps, modelValue: true },
    })

    expect(wrapper.get('nav').classes()).not.toContain('fav-menu-pane')
    await wrapper.get('.folder-item').trigger('click')

    expect(wrapper.emitted('select-online-folder')).toEqual([['online-1']])
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    vi.advanceTimersByTime(260)
    await nextTick()
    expect(wrapper.emitted('update:modelValue')).toEqual([[false]])
    wrapper.unmount()
  })

  test('pane 模式保持静态显示，选择收藏夹后不自动收起', async () => {
    const wrapper = mount(FavoriteSideMenu, {
      props: { ...baseProps, displayMode: 'pane', paneOpen: true },
    })

    const navigation = wrapper.get('nav')
    expect(navigation.attributes('role')).toBe('navigation')
    expect(navigation.attributes('aria-label')).toBe('收藏夹')
    expect(navigation.classes()).toContain('fav-menu-pane')
    expect(wrapper.get('.fav-menu-panel').element.style.transform).toBe('none')

    await wrapper.get('.folder-item').trigger('click')

    expect(wrapper.emitted('select-online-folder')).toEqual([['online-1']])
    expect(wrapper.emitted('update:paneOpen')).toBeUndefined()
    expect(wrapper.get('nav').element.style.display).not.toBe('none')
    wrapper.unmount()
  })

  test('pane 模式的关闭按钮只收起 pane', async () => {
    const wrapper = mount(FavoriteSideMenu, {
      props: { ...baseProps, displayMode: 'pane', paneOpen: true },
    })

    await wrapper.get('.menu-close-btn').trigger('click')

    expect(wrapper.emitted('update:paneOpen')).toEqual([[false]])
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()
    wrapper.unmount()
  })

  test('首次在线列表加载显示 loading 而不是空态', () => {
    const wrapper = mount(FavoriteSideMenu, {
      props: {
        ...baseProps,
        modelValue: true,
        onlineFolders: [],
        onlineHasSuccessfulData: false,
        onlineLoading: true,
        onlineRefreshing: true,
      },
    })

    expect(wrapper.find('.loading-state').exists()).toBe(true)
    expect(wrapper.find('.empty-state').exists()).toBe(false)
    wrapper.unmount()
  })
})
