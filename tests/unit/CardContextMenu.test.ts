/* eslint-disable vue/one-component-per-file -- test-only Ionic fixtures */
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import { afterEach, describe, expect, test, vi } from 'vitest'

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

import CardContextMenu from '@/components/common/CardContextMenu.vue'

const actions = [
  { id: 'detail', label: '详情', icon: 'detail' },
  { id: 'delete', label: '删除', icon: 'delete', danger: true },
]

const createHost = () => {
  const selected = vi.fn()
  const Host = defineComponent({
    setup() {
      const visible = ref(false)
      const anchor = ref<HTMLElement | null>(null)
      return () =>
        h('div', [
          h(
            'button',
            {
              ref: anchor,
              type: 'button',
              onClick: () => {
                visible.value = !visible.value
              },
            },
            '更多操作',
          ),
          h(CardContextMenu, {
            visible: visible.value,
            anchor: anchor.value,
            actions,
            onClose: () => {
              visible.value = false
            },
            onSelect: selected,
          }),
        ])
    },
  })
  return { wrapper: mount(Host, { attachTo: document.body }), selected }
}

afterEach(() => {
  document.body.replaceChildren()
})

describe('CardContextMenu', () => {
  test('再次点击同一更多操作按钮会关闭菜单', async () => {
    const { wrapper } = createHost()
    const toggle = wrapper.get('button')

    await toggle.trigger('click')
    expect(document.body.querySelector('.card-context-menu')).not.toBeNull()

    toggle.element.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await toggle.trigger('click')

    expect(document.body.querySelector('.card-context-menu')).toBeNull()
    wrapper.unmount()
  })

  test('点击锚点和菜单之外的区域会关闭菜单', async () => {
    const { wrapper } = createHost()
    await wrapper.get('button').trigger('click')

    document.body.dispatchEvent(new Event('pointerdown', { bubbles: true }))
    await nextTick()

    expect(document.body.querySelector('.card-context-menu')).toBeNull()
    wrapper.unmount()
  })

  test('危险动作沿用统一样式并向调用方派发动作标识', async () => {
    const { wrapper, selected } = createHost()
    await wrapper.get('button').trigger('click')

    const deleteButton = document.body.querySelector<HTMLButtonElement>('.card-menu-item--danger')
    deleteButton?.click()

    expect(selected).toHaveBeenCalledWith('delete')
    wrapper.unmount()
  })
})
