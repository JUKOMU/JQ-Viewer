/** 返回预览网格中单个卡片的 CSS 宽度，用于按实际布局生成 PDF 缩略图。 */
export function getPreviewGridItemWidth(grid: HTMLElement | null): number {
  if (!grid) return 0

  const item = grid.querySelector<HTMLElement>('.preview-item')
  const itemWidth = item?.getBoundingClientRect().width ?? 0
  if (itemWidth > 0) return itemWidth

  const gridWidth = Math.max(grid.getBoundingClientRect().width, grid.clientWidth)
  if (!gridWidth || typeof window === 'undefined') return 0

  const style = window.getComputedStyle(grid)
  const columns = style.gridTemplateColumns.trim().split(/\s+/).filter(Boolean).length
  const gap = parseFloat(style.columnGap || style.gap || '0') || 0
  if (columns <= 0) return gridWidth

  return Math.max(1, (gridWidth - gap * (columns - 1)) / columns)
}
