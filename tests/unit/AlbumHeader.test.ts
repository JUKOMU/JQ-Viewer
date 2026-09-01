import { mount } from '@vue/test-utils'
import { describe, expect, test, vi } from 'vitest'
import AlbumHeader from '@/components/album/AlbumHeader.vue'

vi.mock('@ionic/vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    IonIcon: defineComponent({
      name: 'IonIcon',
      render: () => h('span'),
    }),
  }
})

vi.mock('ionicons/icons', () => ({
  arrowBack: 'arrow-back',
  documentOutline: 'document',
  ellipsisVertical: 'ellipsis',
  globeOutline: 'globe',
  imageOutline: 'image',
}))

const baseProps = {
  coverUrl: '',
  title: '测试本子',
  authors: '作者',
  pageCount: 2,
  loading: false,
  sourceMenuOpen: true,
  imageAvailable: false,
  pdfAvailable: false,
}

describe('AlbumHeader 阅读来源', () => {
  test('网络图片阅读始终可选择并发出网络来源事件', async () => {
    const wrapper = mount(AlbumHeader, { props: baseProps })
    const networkButton = wrapper.get('[aria-label="网络图片阅读"]')

    expect((networkButton.element as HTMLButtonElement).disabled).toBe(false)
    expect(networkButton.classes()).toContain('available')

    await networkButton.trigger('click')

    expect(wrapper.emitted('select-source')).toEqual([['network']])
  })

  test('下载和 PDF 按钮仍按本地资源状态控制', async () => {
    const wrapper = mount(AlbumHeader, { props: baseProps })
    const imageButton = wrapper.get('[aria-label="本地图片阅读"]')
    const pdfButton = wrapper.get('[aria-label="PDF 阅读"]')

    expect((imageButton.element as HTMLButtonElement).disabled).toBe(true)
    expect((pdfButton.element as HTMLButtonElement).disabled).toBe(true)

    await wrapper.setProps({ imageAvailable: true, pdfAvailable: true })
    expect((imageButton.element as HTMLButtonElement).disabled).toBe(false)
    expect((pdfButton.element as HTMLButtonElement).disabled).toBe(false)

    await imageButton.trigger('click')
    await pdfButton.trigger('click')

    expect(wrapper.emitted('select-source')).toEqual([['download'], ['pdf']])
  })
})
