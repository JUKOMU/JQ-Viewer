/* eslint-disable vue/one-component-per-file */
import { defineComponent, h } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, test, vi } from 'vitest'
import { useDesktopBackButton } from '@/composables/useDesktopBackButton'

describe('useDesktopBackButton', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  test('桌面端将 ESC 转为 backbutton 并阻止其他 ESC 监听器重复处理', () => {
    const backButton = vi.fn()
    const otherKeydown = vi.fn()
    document.addEventListener('backbutton', backButton)
    window.addEventListener('keydown', otherKeydown)
    const wrapper = mount(
      defineComponent({
        setup() {
          useDesktopBackButton(true)
          return () => h('div')
        },
      }),
    )

    const event = new KeyboardEvent('keydown', { key: 'Escape', cancelable: true })
    window.dispatchEvent(event)

    expect(backButton).toHaveBeenCalledOnce()
    expect(otherKeydown).not.toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(true)

    wrapper.unmount()
    document.removeEventListener('backbutton', backButton)
    window.removeEventListener('keydown', otherKeydown)
  })

  test('禁用时不注册桌面监听', () => {
    const backButton = vi.fn()
    document.addEventListener('backbutton', backButton)
    const wrapper = mount(
      defineComponent({
        setup() {
          useDesktopBackButton(false)
          return () => h('div')
        },
      }),
    )

    window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', cancelable: true }))
    expect(backButton).not.toHaveBeenCalled()

    wrapper.unmount()
    document.removeEventListener('backbutton', backButton)
  })
})
