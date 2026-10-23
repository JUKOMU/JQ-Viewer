import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import MultiSelectInfoBar from '@/components/common/MultiSelectInfoBar.vue'

describe('MultiSelectInfoBar', () => {
  test('全量准备期间取消按钮只请求取消准备，不触发退出选择', async () => {
    const wrapper = mount(MultiSelectInfoBar, { props: { count: 2, preparing: true } })

    await wrapper.get('button').trigger('click')

    expect(wrapper.emitted('cancel-preparation')).toHaveLength(1)
    expect(wrapper.emitted('cancel')).toBeUndefined()
    expect(wrapper.text()).toContain('正在准备')
  })
})
