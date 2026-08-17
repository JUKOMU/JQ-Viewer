import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  dismiss: vi.fn(),
}))

vi.mock('@ionic/vue', () => ({
  modalController: { dismiss: mocks.dismiss },
}))

import AppAlert from '@/components/common/AppAlert.vue'

beforeEach(() => {
  vi.clearAllMocks()
  mocks.dismiss.mockResolvedValue(true)
})

describe('AppAlert', () => {
  test('混合输入按字段名返回文本和单选值', async () => {
    const handler = vi.fn()
    let buttonHandled = false
    const runtime = {
      isButtonHandled: () => buttonHandled,
      setButtonHandled: (handled: boolean) => {
        buttonHandled = handled
      },
    }
    const wrapper = mount(AppAlert, {
      props: {
        modalId: 'mixed-alert',
        runtime,
        options: {
          header: '复制收藏夹',
          layout: 'choice',
          tone: 'default',
          inputs: [
            { name: 'name', type: 'text', placeholder: '新文件夹名称', value: '原名称' },
            { name: 'target', type: 'radio', label: '离线副本', value: 'offline', checked: true },
            { name: 'target', type: 'radio', label: '同步到在线', value: 'online' },
          ],
          buttons: [
            { text: '取消', role: 'cancel' },
            { text: '确定', role: 'confirm', handler },
          ],
        },
      },
    })

    await wrapper.get('input[type="text"]').setValue('新名称')
    await wrapper.findAll('input[type="radio"]')[1].setValue()
    await wrapper.findAll('.app-alert__button')[1].trigger('click')
    await flushPromises()

    const expected = { name: '新名称', target: 'online' }
    expect(handler).toHaveBeenCalledWith(expected)
    expect(mocks.dismiss).toHaveBeenCalledWith(expected, 'confirm', 'mixed-alert')
    expect(runtime.isButtonHandled()).toBe(true)
  })

  test('handler 返回 false 时保持弹窗打开', async () => {
    const handler = vi.fn().mockReturnValue(false)
    let buttonHandled = false
    const runtime = {
      isButtonHandled: () => buttonHandled,
      setButtonHandled: (handled: boolean) => {
        buttonHandled = handled
      },
    }
    const wrapper = mount(AppAlert, {
      props: {
        modalId: 'validation-alert',
        runtime,
        options: {
          header: '跳转页码',
          layout: 'input',
          tone: 'default',
          inputs: [{ name: 'page', type: 'number', placeholder: '页码', value: '1' }],
          buttons: [{ text: '跳转', role: 'confirm', handler }],
        },
      },
    })

    await wrapper.get('.app-alert__button').trigger('click')
    await flushPromises()

    expect(mocks.dismiss).not.toHaveBeenCalled()
    expect(runtime.isButtonHandled()).toBe(false)
    expect(wrapper.get('.app-alert__button').attributes('disabled')).toBeUndefined()
  })

  test('危险态和三按钮布局使用对应样式', () => {
    const wrapper = mount(AppAlert, {
      props: {
        modalId: 'danger-alert',
        runtime: {
          isButtonHandled: () => false,
          setButtonHandled: vi.fn(),
        },
        options: {
          header: '确认删除',
          layout: 'confirm',
          tone: 'danger',
          buttons: [
            { text: '取消', role: 'cancel' },
            { text: '打开设置', role: 'settings' },
            { text: '删除', role: 'destructive' },
          ],
        },
      },
    })

    expect(wrapper.get('.app-alert').classes()).toContain('app-alert--danger')
    expect(wrapper.get('.app-alert__actions').classes()).toContain('app-alert__actions--stacked')
    expect(wrapper.findAll('.app-alert__button')[2].classes()).toContain(
      'app-alert__button--danger',
    )
  })
})
