import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  create: vi.fn(),
}))

vi.mock('@ionic/vue', () => ({
  modalController: { create: mocks.create },
}))

import { createAppAlert } from '@/services/AppAlertService'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('AppAlertService', () => {
  test('根据输入和按钮推导布局与危险态', async () => {
    const modal = {
      onWillDismiss: vi.fn(() => new Promise(() => {})),
    }
    mocks.create.mockResolvedValue(modal)

    await createAppAlert({
      header: '删除收藏夹',
      inputs: [{ type: 'radio', label: '目标', value: 'target' }],
      buttons: [
        { text: '取消', role: 'cancel' },
        { text: '删除', role: 'destructive' },
      ],
    })

    expect(mocks.create).toHaveBeenCalledWith(
      expect.objectContaining({
        cssClass: expect.arrayContaining([
          'app-alert-modal',
          'app-alert-modal--choice',
          'app-alert-modal--danger',
        ]),
        componentProps: expect.objectContaining({
          options: expect.objectContaining({ layout: 'choice', tone: 'danger' }),
        }),
      }),
    )
  })

  test('点击遮罩时调用取消按钮 handler', async () => {
    let resolveDismiss!: (detail: { role: string }) => void
    const modal = {
      onWillDismiss: vi.fn(
        () =>
          new Promise<{ role: string }>((resolve) => {
            resolveDismiss = resolve
          }),
      ),
    }
    mocks.create.mockResolvedValue(modal)
    const cancel = vi.fn()

    await createAppAlert({
      header: '文件已存在',
      buttons: [
        { text: '取消', role: 'cancel', handler: cancel },
        { text: '覆盖', role: 'destructive' },
      ],
    })
    resolveDismiss({ role: 'backdrop' })
    await flushPromises()

    expect(cancel).toHaveBeenCalledWith(undefined)
  })

  test('已处理按钮时不重复调用取消 handler', async () => {
    let resolveDismiss!: (detail: { role: string }) => void
    const modal = {
      onWillDismiss: vi.fn(
        () =>
          new Promise<{ role: string }>((resolve) => {
            resolveDismiss = resolve
          }),
      ),
    }
    mocks.create.mockResolvedValue(modal)
    const cancel = vi.fn()

    await createAppAlert({
      header: '确认',
      buttons: [{ text: '取消', role: 'cancel', handler: cancel }],
    })
    const props = mocks.create.mock.calls[0][0].componentProps
    props.runtime.setButtonHandled(true)
    resolveDismiss({ role: 'cancel' })
    await flushPromises()

    expect(cancel).not.toHaveBeenCalled()
  })
})
