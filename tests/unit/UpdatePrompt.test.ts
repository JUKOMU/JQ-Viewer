import { mount } from '@vue/test-utils'
import { describe, expect, test, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import type { UpdateManifest } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  dismiss: vi.fn(),
}))

vi.mock('@ionic/vue', () => ({
  IonPage: defineComponent({
    name: 'IonPage',
    setup(_, { slots }) {
      return () => h('div', slots.default?.())
    },
  }),
  modalController: { dismiss: mocks.dismiss },
}))

import UpdatePrompt from '@/components/update/UpdatePrompt.vue'

const manifest: UpdateManifest = {
  tag: 'v1.4.0',
  versionName: '1.4.0',
  versionCode: 16,
  packageName: 'io.github.jukomu',
  apkName: 'JQ-Viewer-1_4_0.apk',
  sizeBytes: 1024,
  sha256: 'a'.repeat(64),
  signingCertificateSha256: 'b'.repeat(64),
  releaseNotes: '# v1.4.0\n\n## 新增\n\n- 支持 **双源下载**',
  sources: {
    github: 'https://github.com/example.apk',
    gitee: 'https://gitee.com/example.apk',
  },
}

describe('UpdatePrompt', () => {
  test('展示 Markdown 结构而不是原始标记', () => {
    const wrapper = mount(UpdatePrompt, { props: { manifest } })

    expect(wrapper.get('h2').text()).toBe('1.4.0')
    expect(wrapper.get('.release-markdown h2').text()).toBe('新增')
    expect(wrapper.get('.release-markdown strong').text()).toBe('双源下载')
    expect(wrapper.text()).not.toContain('# v1.4.0')
  })

  test('按钮使用对应的关闭角色', async () => {
    const wrapper = mount(UpdatePrompt, { props: { manifest } })

    await wrapper.get('button.secondary').trigger('click')
    await wrapper.get('button.primary').trigger('click')

    expect(mocks.dismiss).toHaveBeenNthCalledWith(1, undefined, 'cancel')
    expect(mocks.dismiss).toHaveBeenNthCalledWith(2, undefined, 'confirm')
  })
})
