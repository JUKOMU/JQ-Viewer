import { beforeEach, describe, expect, test, vi } from 'vitest'
import type { BrowseHistoryRange } from '@/services/JmcomicTypes'

const mocks = vi.hoisted(() => ({
  getBrowseHistory: vi.fn(),
  getBrowseHistoryOverview: vi.fn(),
}))

vi.mock('@/services/JmcomicService', () => ({
  JmcomicService: {
    getBrowseHistory: mocks.getBrowseHistory,
    getBrowseHistoryOverview: mocks.getBrowseHistoryOverview,
  },
}))

import { HistoryService } from '@/services/HistoryService'

const ranges: BrowseHistoryRange[] = [{ key: 'today', startInclusive: 100, endExclusive: null }]

describe('HistoryService 浏览历史', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  test('透传概览范围并保留完整返回值', async () => {
    const overview = {
      totalCount: 1,
      groupCounts: { today: 1 },
    }
    mocks.getBrowseHistoryOverview.mockResolvedValue(overview)

    await expect(HistoryService.getBrowseHistoryOverview(ranges)).resolves.toEqual(overview)
    expect(mocks.getBrowseHistoryOverview).toHaveBeenCalledWith(ranges)
  })

  test('范围分页透传上下界并在 Native 失败时返回 null', async () => {
    mocks.getBrowseHistory.mockResolvedValueOnce({ items: [], totalCount: 0 })
    const range = { startInclusive: 100, endExclusive: 200 }

    await expect(HistoryService.getBrowseHistory(50, 10, range)).resolves.toEqual({
      items: [],
      totalCount: 0,
    })
    expect(mocks.getBrowseHistory).toHaveBeenCalledWith(50, 10, range)

    mocks.getBrowseHistory.mockRejectedValueOnce(new Error('native failure'))
    await expect(HistoryService.getBrowseHistory(50, 0, range)).resolves.toBeNull()
  })

  test('概览失败时不伪装成空数据', async () => {
    mocks.getBrowseHistoryOverview.mockRejectedValueOnce(new Error('overview failure'))

    await expect(HistoryService.getBrowseHistoryOverview(ranges)).resolves.toBeNull()
  })
})
