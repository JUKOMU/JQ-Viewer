import { DOMWrapper, mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import AlbumHeader from '@/components/album/AlbumHeader.vue'

const mocks = vi.hoisted(() => ({
  useBackButton: vi.fn(),
}))

vi.mock('@ionic/vue', async () => {
  const { defineComponent, h } = await import('vue')
  return {
    IonIcon: defineComponent({
      name: 'IonIcon',
      render: () => h('span'),
    }),
    useBackButton: mocks.useBackButton,
  }
})

vi.mock('ionicons/icons', () => ({
  archiveOutline: 'archive',
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
  description: '这是作品简介',
  pageCount: 2,
  loading: false,
  sourceMenuOpen: true,
  imageAvailable: false,
  cbzAvailable: false,
  pdfAvailable: false,
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
  mocks.useBackButton.mockReset()
  document.body.innerHTML = ''
})

function getBackButtonHandler() {
  const handler = mocks.useBackButton.mock.calls[0]?.[1]
  expect(handler).toBeTypeOf('function')
  return handler as (processNextHandler: () => void) => void
}

function pointer(pointerId: number, clientX: number, clientY: number, pointerType = 'touch') {
  return { pointerId, pointerType, button: 0, clientX, clientY }
}

async function openPreview() {
  const wrapper = mount(AlbumHeader, {
    props: { ...baseProps, coverUrl: 'https://example.com/cover.jpg' },
    attachTo: document.body,
  })
  await wrapper.get('[aria-label="预览封面"]').trigger('click')
  const overlayElement = document.body.querySelector('.cover-preview-overlay')
  expect(overlayElement).not.toBeNull()
  const overlay = new DOMWrapper(overlayElement as Element)
  return { wrapper, overlay }
}

describe('AlbumHeader 阅读来源', () => {
  test('展示作品简介并允许通过按钮预览封面', async () => {
    const { wrapper } = await openPreview()

    expect(wrapper.get('.album-description').text()).toBe('这是作品简介')
    expect(document.body.querySelector('.cover-preview-overlay')).not.toBeNull()

    wrapper.unmount()
  })

  test('系统返回键打开预览时只关闭封面，不继续路由返回', async () => {
    const { wrapper } = await openPreview()
    const handler = getBackButtonHandler()
    const processNextHandler = vi.fn()

    handler(processNextHandler)
    await wrapper.vm.$nextTick()

    expect(document.body.querySelector('.cover-preview-overlay')).toBeNull()
    expect(processNextHandler).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  test('移动端双指缩放封面并限制在 5 倍，双击按 1/2/3/5/1 倍循环', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1000)
    const { wrapper, overlay } = await openPreview()

    await overlay.trigger('pointerdown', pointer(1, 100, 200))
    await overlay.trigger('pointerdown', pointer(2, 200, 200))
    await overlay.trigger('pointermove', pointer(1, -200, 200))
    await overlay.trigger('pointermove', pointer(2, 500, 200))
    await overlay.trigger('pointerup', pointer(1, 150, 200))
    await overlay.trigger('pointerup', pointer(2, 150, 200))
    expect(overlay.get('.cover-preview-img').attributes('style')).toContain('scale(5)')

    vi.spyOn(Date, 'now').mockReturnValue(2000)
    await overlay.trigger('pointerdown', pointer(3, 150, 200))
    await overlay.trigger('pointerup', pointer(3, 150, 200))
    vi.spyOn(Date, 'now').mockReturnValue(2100)
    await overlay.trigger('pointerdown', pointer(4, 150, 200))
    await overlay.trigger('pointerup', pointer(4, 150, 200))
    expect(overlay.get('.cover-preview-img').attributes('style')).toContain('scale(1)')

    wrapper.unmount()
  })

  test('移动端未缩放时拖拽超过阈值关闭，未超过阈值回弹', async () => {
    const first = await openPreview()
    await first.overlay.trigger('pointerdown', pointer(1, 150, 200))
    await first.overlay.trigger('pointermove', pointer(1, 300, 200))
    await first.wrapper.vm.$nextTick()
    expect(first.overlay.element.getAttribute('style') ?? '').toContain('background-color:')
    await first.overlay.trigger('pointerup', pointer(1, 300, 200))
    await first.wrapper.vm.$nextTick()
    expect(document.body.querySelector('.cover-preview-overlay')).toBeNull()
    first.wrapper.unmount()

    const second = await openPreview()
    await second.overlay.trigger('pointerdown', pointer(1, 150, 200))
    await second.overlay.trigger('pointermove', pointer(1, 180, 200))
    await second.overlay.trigger('pointerup', pointer(1, 180, 200))
    await second.wrapper.vm.$nextTick()
    expect(second.overlay.exists()).toBe(true)
    expect(second.overlay.get('.cover-preview-img').attributes('style')).toContain(
      'translate3d(0px, 0px, 0)',
    )
    second.wrapper.unmount()
  })

  test('桌面鼠标单击关闭', async () => {
    const { wrapper, overlay } = await openPreview()
    vi.useFakeTimers()
    await overlay.trigger('pointerdown', pointer(1, 150, 200, 'mouse'))
    await overlay.trigger('pointerup', pointer(1, 150, 200, 'mouse'))
    expect(document.body.querySelector('.cover-preview-overlay')).not.toBeNull()
    vi.advanceTimersByTime(280)
    await wrapper.vm.$nextTick()
    expect(document.body.querySelector('.cover-preview-overlay')).toBeNull()
    wrapper.unmount()
  })

  test('桌面鼠标双击缩放，拖拽超过阈值关闭', async () => {
    const { wrapper, overlay } = await openPreview()
    vi.useFakeTimers()
    await overlay.trigger('pointerdown', pointer(1, 150, 200, 'mouse'))
    await overlay.trigger('pointerup', pointer(1, 150, 200, 'mouse'))
    vi.advanceTimersByTime(250)
    await overlay.trigger('pointerdown', pointer(1, 150, 200, 'mouse'))
    await overlay.trigger('pointerup', pointer(1, 150, 200, 'mouse'))
    expect(overlay.get('.cover-preview-img').attributes('style')).toContain('scale(2)')
    wrapper.unmount()

    const drag = await openPreview()
    await drag.overlay.trigger('pointerdown', pointer(1, 150, 200, 'mouse'))
    await drag.overlay.trigger('pointermove', pointer(1, 300, 200, 'mouse'))
    await drag.overlay.trigger('pointerup', pointer(1, 300, 200, 'mouse'))
    await drag.wrapper.vm.$nextTick()
    expect(document.body.querySelector('.cover-preview-overlay')).toBeNull()
    drag.wrapper.unmount()
  })

  test('网络图片阅读始终可选择并发出网络来源事件', async () => {
    const wrapper = mount(AlbumHeader, { props: baseProps })
    const networkButton = wrapper.get('[aria-label="网络图片阅读"]')

    expect((networkButton.element as HTMLButtonElement).disabled).toBe(false)
    expect(networkButton.classes()).toContain('available')

    await networkButton.trigger('click')

    expect(wrapper.emitted('select-source')).toEqual([['network']])
  })

  test('下载、CBZ 和 PDF 按钮按本地资源状态控制', async () => {
    const wrapper = mount(AlbumHeader, { props: baseProps })
    const imageButton = wrapper.get('[aria-label="本地图片阅读"]')
    const cbzButton = wrapper.get('[aria-label="CBZ 阅读"]')
    const pdfButton = wrapper.get('[aria-label="PDF 阅读"]')

    expect((imageButton.element as HTMLButtonElement).disabled).toBe(true)
    expect((cbzButton.element as HTMLButtonElement).disabled).toBe(true)
    expect((pdfButton.element as HTMLButtonElement).disabled).toBe(true)

    await wrapper.setProps({ imageAvailable: true, cbzAvailable: true, pdfAvailable: true })
    expect((imageButton.element as HTMLButtonElement).disabled).toBe(false)
    expect((cbzButton.element as HTMLButtonElement).disabled).toBe(false)
    expect((pdfButton.element as HTMLButtonElement).disabled).toBe(false)

    await imageButton.trigger('click')
    await cbzButton.trigger('click')
    await pdfButton.trigger('click')

    expect(wrapper.emitted('select-source')).toEqual([['download'], ['cbz'], ['pdf']])
  })
})
