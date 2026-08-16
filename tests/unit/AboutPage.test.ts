import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest'
import type { UpdateProgressEvent } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  writeText: vi.fn(),
  showToast: vi.fn(),
  alertCreate: vi.fn(),
  modalCreate: vi.fn(),
  alertPresent: vi.fn(),
  checkUpdate: vi.fn(),
  addUpdateProgressListener: vi.fn(),
  getUpdateState: vi.fn(),
  progressHandler: undefined as ((event: UpdateProgressEvent) => void) | undefined,
}))

vi.mock('@capacitor/app', () => ({
  App: { getInfo: vi.fn().mockResolvedValue({ version: '1.2.0' }) },
}))

vi.mock('@ionic/vue', () => {
  const withSlot = (name: string, tag = 'div') =>
    defineComponent({
      name,
      setup(_, { slots }) {
        return () => h(tag, slots.default?.())
      },
    })

  return {
    alertController: { create: mocks.alertCreate },
    modalController: { create: mocks.modalCreate },
    IonBackButton: withSlot('IonBackButton'),
    IonButtons: withSlot('IonButtons'),
    IonContent: withSlot('IonContent', 'main'),
    IonHeader: withSlot('IonHeader', 'header'),
    IonIcon: withSlot('IonIcon', 'span'),
    IonPage: withSlot('IonPage'),
    IonSpinner: withSlot('IonSpinner', 'span'),
    IonTitle: withSlot('IonTitle'),
    IonToolbar: withSlot('IonToolbar'),
  }
})

vi.mock('ionicons/icons', () => ({
  chevronForwardOutline: 'chevron-forward',
  logoGithub: 'logo-github',
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    checkUpdate: mocks.checkUpdate,
    addUpdateProgressListener: mocks.addUpdateProgressListener,
    getUpdateState: mocks.getUpdateState,
    checkNotificationPermission: vi.fn(),
    requestNotificationPermission: vi.fn(),
    openNotificationSettings: vi.fn(),
    startUpdate: vi.fn(),
    cancelUpdate: vi.fn(),
    installUpdate: vi.fn(),
    requestInstallPermission: vi.fn(),
  },
  showToast: mocks.showToast,
}))

import AboutPage from '@/views/AboutPage.vue'
import { UpdateService } from '@/services/UpdateService'

beforeEach(async () => {
  await UpdateService.dispose()
  vi.clearAllMocks()
  UpdateService.manifest.value = null
  UpdateService.state.value = {
    revision: 0,
    phase: 'idle',
    source: '',
    githubBytes: 0,
    giteeBytes: 0,
    totalBytes: 0,
    error: '',
  }
  mocks.progressHandler = undefined
  mocks.writeText.mockResolvedValue(undefined)
  mocks.showToast.mockResolvedValue(undefined)
  mocks.alertCreate.mockResolvedValue({
    present: mocks.alertPresent,
    onDidDismiss: vi.fn().mockResolvedValue({ role: 'cancel' }),
  })
  mocks.modalCreate.mockResolvedValue({
    present: vi.fn(),
    onDidDismiss: vi.fn().mockResolvedValue({ role: 'cancel' }),
  })
  mocks.addUpdateProgressListener.mockImplementation(async (handler) => {
    mocks.progressHandler = handler
    return { remove: vi.fn() }
  })
  mocks.getUpdateState.mockResolvedValue({ ...UpdateService.state.value })
  mocks.checkUpdate.mockResolvedValue({
    updateAvailable: false,
    manifest: {
      tag: 'v1.3.0',
      versionName: '1.3.0',
      versionCode: 130,
      packageName: 'io.github.jukomu',
      apkName: 'jq-viewer.apk',
      sizeBytes: 1024,
      sha256: 'a'.repeat(64),
      signingCertificateSha256: 'b'.repeat(64),
      releaseNotes: '',
      sources: { github: 'https://github.com/example.apk', gitee: 'https://gitee.com/example.apk' },
    },
  })
  vi.stubGlobal('navigator', { clipboard: { writeText: mocks.writeText } })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('AboutPage 仓库链接', () => {
  test('点击 JMComic-Api-Java 链接只复制对应地址', async () => {
    const wrapper = mount(AboutPage)
    const links = wrapper
      .findAll('a')
      .filter((link) => link.attributes('href')?.includes('github.com'))

    await links[1].trigger('click')
    await flushPromises()

    expect(mocks.writeText).toHaveBeenCalledTimes(1)
    expect(mocks.writeText).toHaveBeenCalledWith('https://github.com/JUKOMU/JMComic-Api-Java')
    wrapper.unmount()
  })

  test('点击 JQ Viewer 链接只复制主仓库地址', async () => {
    const wrapper = mount(AboutPage)
    const links = wrapper
      .findAll('a')
      .filter((link) => link.attributes('href')?.includes('github.com'))

    await links[0].trigger('click')
    await flushPromises()

    expect(mocks.writeText).toHaveBeenCalledTimes(1)
    expect(mocks.writeText).toHaveBeenCalledWith('https://github.com/jukomu/jq-viewer')
    wrapper.unmount()
  })
})

describe('AboutPage 更新状态', () => {
  test('首次检查前显示中性状态', () => {
    const wrapper = mount(AboutPage)

    expect(wrapper.get('button.info-row-action').text()).toContain('点击检查')
    wrapper.unmount()
  })

  test('检查成功且没有新版本后显示已是最新', async () => {
    vi.useFakeTimers()
    const wrapper = mount(AboutPage)

    await wrapper.get('button.info-row-action').trigger('click')
    await flushPromises()
    expect(wrapper.get('button.info-row-action').text()).toContain('检查中...')

    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(wrapper.get('button.info-row-action').text()).toContain('已是最新')
    wrapper.unmount()
  })

  test('检查失败后显示失败状态', async () => {
    vi.useFakeTimers()
    mocks.checkUpdate.mockRejectedValue(new Error('network error'))
    const wrapper = mount(AboutPage)

    await wrapper.get('button.info-row-action').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(1000)
    await flushPromises()

    expect(wrapper.get('button.info-row-action').text()).toContain('检查失败')
    wrapper.unmount()
  })

  test('下载中重新进入页面仍显示更新卡片', async () => {
    vi.useFakeTimers()
    mocks.checkUpdate.mockResolvedValue({
      updateAvailable: true,
      manifest: {
        tag: 'v1.4.0',
        versionName: '1.4.0',
        versionCode: 16,
        packageName: 'io.github.jukomu',
        apkName: 'JQ-Viewer-1_4_0.apk',
        sizeBytes: 20 * 1024 * 1024,
        sha256: 'a'.repeat(64),
        signingCertificateSha256: 'b'.repeat(64),
        releaseNotes: '更新说明',
        sources: {
          github: 'https://github.com/example.apk',
          gitee: 'https://gitee.com/example.apk',
        },
      },
    })
    const first = mount(AboutPage)

    await first.get('button.info-row-action').trigger('click')
    await flushPromises()
    mocks.progressHandler?.({
      revision: 2,
      phase: 'racing',
      source: 'racing',
      githubBytes: 1024,
      giteeBytes: 512,
      totalBytes: 20 * 1024 * 1024,
      error: '',
    })
    await flushPromises()
    first.unmount()

    const second = mount(AboutPage)
    await flushPromises()

    expect(second.find('.update-card').exists()).toBe(true)
    expect(second.get('.update-card').text()).toContain('下载中')
    second.unmount()
  })
})
