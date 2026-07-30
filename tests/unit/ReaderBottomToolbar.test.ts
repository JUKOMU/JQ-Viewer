import {mount} from '@vue/test-utils'
import {afterEach, describe, expect, test, vi} from 'vitest'
import ReaderBottomToolbar from '@/components/reader/ReaderBottomToolbar.vue'
import type {PhotoMeta} from '@/services/JmcomicTypes'

const chapters: PhotoMeta[] = Array.from({length: 9}, (_, index) => ({
  id: String(index + 1),
  sortOrder: index + 1,
  title: index === 4 ? '中间章节' : `章节 ${index + 1}`,
}))

const mountToolbar = (options: {
  chapterList?: PhotoMeta[]
  currentChapterId?: string
} = {}) => mount(ReaderBottomToolbar, {
  props: {
    current: 12,
    total: 45,
    chapters: options.chapterList ?? chapters,
    currentChapterId: options.currentChapterId ?? '5',
  },
  global: {
    stubs: {
      IonIcon: true,
      IonRange: true,
    },
  },
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('ReaderBottomToolbar', () => {
  test('按章节导航行和阅读进度行展示工具栏', () => {
    const wrapper = mountToolbar()

    expect(wrapper.get('.chapter-navigation-row').text()).toContain('上一章')
    expect(wrapper.get('.chapter-navigation-row').text()).toContain('第5话')
    expect(wrapper.get('.chapter-navigation-row').text()).toContain('中间章节')
    expect(wrapper.get('.chapter-navigation-row').text()).toContain('下一章')
    expect(wrapper.get('.current-page').text()).toBe('12')
    expect(wrapper.get('.total-pages').text()).toBe('45')
  })

  test('上一章和下一章按钮均显示双箭头图标', () => {
    const wrapper = mountToolbar()

    expect(wrapper.get('[aria-label="上一章"] .chapter-step-icons').findAll('ion-icon-stub')).toHaveLength(2)
    expect(wrapper.get('[aria-label="下一章"] .chapter-step-icons').findAll('ion-icon-stub')).toHaveLength(2)
  })

  test('单章节时禁用上一章和下一章', () => {
    const wrapper = mountToolbar({chapterList: [chapters[4]]})

    expect(wrapper.get('[aria-label="上一章"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[aria-label="下一章"]').attributes('disabled')).toBeDefined()
  })

  test('上一章和下一章选择当前章节的相邻项', async () => {
    const wrapper = mountToolbar()

    await wrapper.get('[aria-label="上一章"]').trigger('click')
    await wrapper.get('[aria-label="下一章"]').trigger('click')

    expect(wrapper.emitted('select-chapter')).toEqual([['4'], ['6']])
  })

  test('章节按钮打开可滚动列表并选择章节', async () => {
    const wrapper = mountToolbar()

    await wrapper.get('.current-chapter-btn').trigger('click')

    const picker = wrapper.get('.chapter-picker')
    expect(picker.element.tagName).toBe('UL')
    expect(picker.findAll('.chapter-option')).toHaveLength(9)
    expect(picker.get('[aria-current="true"]').text()).toContain('第5话')

    await picker.findAll('.chapter-option')[6].trigger('click')

    expect(wrapper.emitted('select-chapter')).toEqual([['7']])
    expect(wrapper.find('.chapter-picker').exists()).toBe(false)
  })

  test('章节按钮在居中文本前显示双方向标并标记展开状态', async () => {
    const wrapper = mountToolbar()
    const button = wrapper.get('.current-chapter-btn')
    const text = button.get('.current-chapter-text')
    const indicator = text.get('.chapter-picker-indicator')

    expect(button.element.firstElementChild).toBe(text.element)
    expect(text.element.firstElementChild).toBe(indicator.element)
    expect(indicator.findAll('ion-icon-stub')).toHaveLength(2)
    expect(button.attributes('aria-expanded')).toBe('false')
    expect(button.classes()).not.toContain('is-open')

    await button.trigger('click')

    expect(button.attributes('aria-expanded')).toBe('true')
    expect(button.classes()).toContain('is-open')
  })

  test('章节标题为空时只显示 order 标题', async () => {
    const wrapper = mountToolbar({
      chapterList: [{id: '1', sortOrder: 1, title: ''}],
      currentChapterId: '1',
    })

    expect(wrapper.get('.current-chapter-btn').text()).toBe('第1话')
    expect(wrapper.find('.current-chapter-btn .chapter-title').exists()).toBe(false)

    await wrapper.get('.current-chapter-btn').trigger('click')
    expect(wrapper.find('.chapter-option .chapter-title').exists()).toBe(false)
  })
})
