import { mount } from '@vue/test-utils'
import { describe, expect, test } from 'vitest'
import BatchProgressOverlay from '@/components/common/BatchProgressOverlay.vue'

describe('BatchProgressOverlay', () => {
  test('在进度面板中展示业务信息并随执行进度更新', async () => {
    const wrapper = mount(BatchProgressOverlay, {
      props: {
        title: '正在提交下载',
        current: 2,
        total: 5,
        currentItem: '漫画 A',
        unit: '个本子',
      },
    })

    expect(wrapper.get('.progress-dialog .progress-title').text()).toBe('正在提交下载')
    expect(wrapper.get<HTMLElement>('.progress-fill').element.style.width).toBe('40%')
    expect(wrapper.get('.progress-percent').text()).toBe('40%')
    expect(wrapper.get('.progress-count').text()).toBe('已完成：2 / 5 个本子')
    expect(wrapper.get('.progress-file').text()).toBe('漫画 A')
    expect(wrapper.get('.progress-warning').text()).toBe('请勿关闭应用')

    await wrapper.setProps({ current: 5, currentItem: '漫画 B' })

    expect(wrapper.get<HTMLElement>('.progress-fill').element.style.width).toBe('100%')
    expect(wrapper.get('.progress-percent').text()).toBe('100%')
    expect(wrapper.get('.progress-count').text()).toBe('已完成：5 / 5 个本子')
    expect(wrapper.get('.progress-file').text()).toBe('漫画 B')
    wrapper.unmount()
  })

  test('初始化显示零进度，无当前条目时不显示文件行', () => {
    const wrapper = mount(BatchProgressOverlay, {
      props: { title: '正在删除', current: 0, total: 0 },
    })

    expect(wrapper.get<HTMLElement>('.progress-fill').element.style.width).toBe('0%')
    expect(wrapper.get('.progress-percent').text()).toBe('0%')
    expect(wrapper.get('.progress-count').text()).toBe('已完成：0 / 0 项')
    expect(wrapper.find('.progress-file').exists()).toBe(false)
    wrapper.unmount()
  })
})
