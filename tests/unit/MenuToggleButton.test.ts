import { nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import MenuToggleButton from '@/components/common/MenuToggleButton.vue'
import { isWideMenu, leftMenuOpen } from '@/composables/useSideMenuState'

vi.mock('@ionic/vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    IonIcon: defineComponent({
      name: 'IonIcon',
      render: () => h('span'),
    }),
  }
})

afterEach(() => {
  isWideMenu.value = false
  leftMenuOpen.value = false
})

describe('MenuToggleButton', () => {
  test('窄屏显示 overlay 入口并打开左侧菜单', async () => {
    const wrapper = mount(MenuToggleButton)

    expect(wrapper.find('button').attributes('aria-label')).toBe('打开侧边栏')
    await wrapper.find('button').trigger('click')
    expect(leftMenuOpen.value).toBe(true)
    wrapper.unmount()
  })

  test('宽屏不重复渲染窄屏 overlay 入口', async () => {
    const wrapper = mount(MenuToggleButton)
    isWideMenu.value = true
    await nextTick()

    expect(wrapper.find('button').exists()).toBe(false)
    wrapper.unmount()
  })
})
