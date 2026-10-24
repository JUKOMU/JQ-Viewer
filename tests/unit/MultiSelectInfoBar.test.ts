import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import MultiSelectInfoBar from '@/components/common/MultiSelectInfoBar.vue'

describe('MultiSelectInfoBar', () => {
  test('全量准备期间取消仍退出多选，数量位置显示加载状态', async () => {
    const wrapper = mount(MultiSelectInfoBar, {
      props: { count: 2, preparing: true, preparingAction: 'select-all' },
    })

    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('cancel')).toHaveLength(1)
    expect(wrapper.emitted('cancel-preparation')).toBeUndefined()
    expect(wrapper.get('.selection-count').text()).toBe('已选择项')
    expect(wrapper.get('.selection-count').find('ion-spinner').exists()).toBe(true)
    expect(wrapper.findAll('button')[1].find('ion-spinner').exists()).toBe(true)
    expect(wrapper.get('button').text()).toContain('取消')
    expect(wrapper.get('button').text()).not.toContain('取消准备')
  })

  test('已全选时禁用全选按钮', () => {
    const wrapper = mount(MultiSelectInfoBar, { props: { count: 2, allSelected: true } })

    expect(wrapper.findAll('button')[1].element.disabled).toBe(true)
  })
})
