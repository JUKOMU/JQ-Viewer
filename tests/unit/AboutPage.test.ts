import {flushPromises, mount} from '@vue/test-utils'
import {defineComponent, h} from 'vue'
import {afterEach, beforeEach, describe, expect, test, vi} from 'vitest'

const mocks = vi.hoisted(() => ({
  writeText: vi.fn(),
  showToast: vi.fn(),
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
    alertController: {create: vi.fn()},
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
  showToast: mocks.showToast,
}))

vi.mock('@/utils/version', () => ({
  RELEASES_API: 'https://example.test/releases',
  compareVersion: vi.fn(() => 0),
  sanitizeReleaseBody: vi.fn((body: string) => body),
}))

import AboutPage from '@/views/AboutPage.vue'

beforeEach(() => {
  vi.clearAllMocks()
  mocks.writeText.mockResolvedValue(undefined)
  mocks.showToast.mockResolvedValue(undefined)
  vi.stubGlobal('navigator', {clipboard: {writeText: mocks.writeText}})
})

afterEach(() => {
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
