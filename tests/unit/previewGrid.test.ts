import { describe, expect, test, vi } from 'vitest'
import { getPreviewGridItemWidth } from '@/utils/previewGrid'

describe('getPreviewGridItemWidth', () => {
  test('优先读取实际预览卡片宽度', () => {
    const grid = document.createElement('div')
    const item = document.createElement('div')
    item.className = 'preview-item'
    grid.append(item)
    vi.spyOn(item, 'getBoundingClientRect').mockReturnValue({ width: 224 } as DOMRect)

    expect(getPreviewGridItemWidth(grid)).toBe(224)
  })

  test('没有网格时返回零', () => {
    expect(getPreviewGridItemWidth(null)).toBe(0)
  })

  test('没有卡片时按网格轨道和间距回算宽度', () => {
    const grid = document.createElement('div')
    grid.style.gridTemplateColumns = '100px 100px 100px 100px'
    grid.style.columnGap = '8px'
    Object.defineProperty(grid, 'clientWidth', { configurable: true, value: 440 })
    vi.spyOn(grid, 'getBoundingClientRect').mockReturnValue({ width: 440 } as DOMRect)

    expect(getPreviewGridItemWidth(grid)).toBe(104)
  })
})
