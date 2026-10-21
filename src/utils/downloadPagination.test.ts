import { describe, expect, it } from 'vitest'
import {
  downloadRefreshGroupForStatus,
  shouldLoadDownloadNextPage,
  shouldLoadDownloadPreviousPage,
} from './downloadPagination'

describe('download pagination thresholds', () => {
  it('loads next page when the bottom sentinel is near the viewport', () => {
    expect(shouldLoadDownloadNextPage(760, 761, 600)).toBe(true)
    expect(shouldLoadDownloadNextPage(900, 901, 600)).toBe(false)
    expect(shouldLoadDownloadNextPage(-300, -299, 600)).toBe(false)
  })

  it('loads previous page only near the top', () => {
    expect(shouldLoadDownloadPreviousPage(120)).toBe(true)
    expect(shouldLoadDownloadPreviousPage(121)).toBe(false)
  })

  it('maps deletion status to the affected download group', () => {
    expect(downloadRefreshGroupForStatus('failed')).toBe('failed')
    expect(downloadRefreshGroupForStatus('completed')).toBe('completed')
  })
})
