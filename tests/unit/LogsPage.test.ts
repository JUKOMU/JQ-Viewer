/* eslint-disable vue/one-component-per-file -- test-only Ionic stubs */
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  listFiles: vi.fn(),
  getCurrent: vi.fn(),
  exportFiles: vi.fn(),
  pickFolder: vi.fn(),
}))

vi.mock('@ionic/vue', () => {
  const component = (name: string, tag = 'div') =>
    defineComponent({
      name,
      inheritAttrs: false,
      setup(_, { attrs, slots }) {
        return () => h(tag, attrs, slots.default?.())
      },
    })

  return {
    IonBackButton: component('IonBackButton', 'button'),
    IonButton: component('IonButton', 'button'),
    IonButtons: component('IonButtons'),
    IonContent: component('IonContent', 'main'),
    IonHeader: component('IonHeader', 'header'),
    IonIcon: component('IonIcon', 'span'),
    IonPage: component('IonPage'),
    IonTitle: component('IonTitle', 'h1'),
    IonToolbar: component('IonToolbar'),
  }
})

vi.mock('ionicons/icons', () => ({
  closeOutline: 'close',
  downloadOutline: 'download',
  refreshOutline: 'refresh',
}))

vi.mock('@/runtime/runtimeContext', () => ({
  getRuntime: () => ({
    services: {
      files: { pickFolder: mocks.pickFolder },
      logs: {
        available: true,
        api: {
          listFiles: mocks.listFiles,
          getCurrent: mocks.getCurrent,
          exportFiles: mocks.exportFiles,
        },
      },
    },
  }),
}))

import LogsPage from '@/views/LogsPage.vue'

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  document.body.innerHTML = ''
  mocks.getCurrent.mockResolvedValue({
    fileName: 'current.log',
    updatedAt: 1,
    nextLine: 1,
    nextOffset: 10,
    content: 'current log\n',
    reset: true,
  })
  mocks.listFiles.mockResolvedValue([
    { fileName: 'current.log', updatedAt: 1, sizeBytes: 10, current: true },
    { fileName: 'jq-viewer-2026-10-14.log', updatedAt: 2, sizeBytes: 20, current: false },
  ])
  mocks.pickFolder.mockResolvedValue({ ref: 'folder:path:/exports', displayPath: '/exports' })
  mocks.exportFiles.mockResolvedValue({
    exported: ['current.log'],
    skipped: ['jq-viewer-2026-10-14.log'],
    failed: [{ fileName: 'broken.log', message: '无法读取' }],
  })
})

afterEach(() => {
  document.body.innerHTML = ''
  vi.useRealTimers()
})

describe('LogsPage 日志文件选择与导出', () => {
  test('点击导出日志后，在弹窗中支持单选、全选和取消全选', async () => {
    const wrapper = mount(LogsPage)
    await flushPromises()

    expect(wrapper.text()).not.toContain('日志文件（')
    expect(mocks.listFiles).not.toHaveBeenCalled()
    const exportButton = wrapper.get('button[aria-label="导出日志"]')
    expect(exportButton.text()).toContain('导出日志')
    await exportButton.trigger('click')
    await flushPromises()
    expect(mocks.listFiles).toHaveBeenCalledTimes(1)
    const dialog = document.body.querySelector('.log-export-dialog')
    expect(dialog?.textContent).toContain('导出日志')
    expect(dialog?.textContent).toContain('选择日志（0/2）')

    const modalExportButton = document.body.querySelector(
      '.log-export-dialog button[aria-label="导出选中日志"]',
    ) as HTMLButtonElement
    expect(modalExportButton.disabled).toBe(true)

    const first = document.body.querySelector(
      'input[aria-label="选择 current.log"]',
    ) as HTMLInputElement
    await first.dispatchEvent(new Event('change', { bubbles: true }))
    await flushPromises()
    expect(modalExportButton.disabled).toBe(false)

    const selectAll = document.body.querySelector('button.select-all') as HTMLButtonElement
    await selectAll.click()
    const checkboxes = [...document.body.querySelectorAll<HTMLInputElement>('input[type="checkbox"]')]
    expect(checkboxes).toHaveLength(2)
    expect(checkboxes.every((input) => input.checked)).toBe(true)

    await selectAll.click()
    expect(checkboxes.every((input) => !input.checked)).toBe(true)

    await (document.body.querySelector('button.btn-cancel') as HTMLButtonElement).click()
    await exportButton.trigger('click')
    await flushPromises()
    expect(mocks.listFiles).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })

  test('导出后明确展示已导出、跳过和失败结果', async () => {
    const wrapper = mount(LogsPage)
    await flushPromises()
    await wrapper.get('button[aria-label="导出日志"]').trigger('click')
    await flushPromises()
    ;(document.body.querySelector('button.select-all') as HTMLButtonElement).click()
    await flushPromises()
    ;(document.body.querySelector(
      '.log-export-dialog button[aria-label="导出选中日志"]',
    ) as HTMLButtonElement).click()
    await flushPromises()

    expect(mocks.pickFolder).toHaveBeenCalledWith('logs')
    expect(mocks.exportFiles).toHaveBeenCalledWith({
      folder: 'folder:path:/exports',
      fileNames: ['current.log', 'jq-viewer-2026-10-14.log'],
    })
    expect(wrapper.text()).toContain('已导出 1 个')
    expect(wrapper.text()).toContain('跳过 1 个')
    expect(wrapper.text()).toContain('失败 1 个：broken.log')
    expect(document.body.querySelector('.log-export-dialog')).toBeNull()
    wrapper.unmount()
  })
})
