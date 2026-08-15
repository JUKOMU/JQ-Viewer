import {flushPromises, mount} from '@vue/test-utils'
import {defineComponent, h} from 'vue'
import {afterEach, beforeEach, describe, expect, test, vi} from 'vitest'

const mocks = vi.hoisted(() => ({
  writeText: vi.fn(),
  showToast: vi.fn(),
  alertCreate: vi.fn(),
  alertPresent: vi.fn(),
  checkUpdate: vi.fn(),
  addUpdateProgressListener: vi.fn(),
}))

vi.mock('@capacitor/app', () => ({
  App: {getInfo: vi.fn().mockResolvedValue({version: '1.2.0'})},
}))

vi.mock('@ionic/vue', () => {
  const withSlot = (name: string, tag = 'div') =>
    defineComponent({
      name,
      setup(_, {slots}) {
        return () => h(tag, slots.default?.())
      },
    })

  return {
    alertController: {create: mocks.alertCreate},
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
    checkNotificationPermission: vi.fn(),
    requestNotificationPermission: vi.fn(),
    startUpdate: vi.fn(),
    cancelUpdate: vi.fn(),
    installUpdate: vi.fn(),
    requestInstallPermission: vi.fn(),
  },
  showToast: mocks.showToast,
}))

import AboutPage from '@/views/AboutPage.vue'

beforeEach(() => {
  vi.clearAllMocks()
  mocks.writeText.mockResolvedValue(undefined)
  mocks.showToast.mockResolvedValue(undefined)
  mocks.alertCreate.mockResolvedValue({
    present: mocks.alertPresent,
    onDidDismiss: vi.fn().mockResolvedValue({role: 'cancel'}),
  })
  mocks.addUpdateProgressListener.mockResolvedValue({remove: vi.fn()})
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
      sources: {github: 'https://github.com/example.apk', gitee: 'https://gitee.com/example.apk'},
    },
  })
  vi.stubGlobal('navigator', {clipboard: {writeText: mocks.writeText}})
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
})
