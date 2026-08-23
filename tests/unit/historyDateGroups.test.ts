import { describe, expect, test } from 'vitest'
import type { BrowseHistoryItem } from '@/services/JmcomicTypes'
import { groupBrowseHistory } from '@/utils/historyDateGroups'

function localDate(
  year: number,
  month: number,
  date: number,
  hour = 12,
  minute = 0,
  second = 0,
  millisecond = 0,
): number {
  return new Date(year, month - 1, date, hour, minute, second, millisecond).getTime()
}

function makeItem(id: number, timestamp: number): BrowseHistoryItem {
  return {
    id,
    albumId: `${id}`,
    albumTitle: `本子 ${id}`,
    coverUrl: '',
    authors: '',
    chapterId: '',
    chapterTitle: '',
    timestamp,
  }
}

function groupKeyFor(timestamp: number, nowMs: number) {
  return groupBrowseHistory([makeItem(1, timestamp)], nowMs)[0]?.key
}

describe('groupBrowseHistory', () => {
  test('按固定顺序返回非空分组并保留组内输入顺序', () => {
    const now = localDate(2025, 7, 16)
    const items = [
      makeItem(1, localDate(2025, 7, 16, 10)),
      makeItem(2, localDate(2025, 7, 16, 9)),
      makeItem(3, localDate(2025, 7, 15, 12)),
      makeItem(4, localDate(2025, 7, 14, 12)),
      makeItem(5, localDate(2025, 7, 1, 12)),
      makeItem(6, localDate(2025, 4, 16, 12)),
      makeItem(7, localDate(2025, 1, 16, 12)),
      makeItem(8, localDate(2024, 12, 31, 12)),
    ]

    const groups = groupBrowseHistory(items, now)

    expect(groups.map((group) => group.key)).toEqual([
      'today',
      'yesterday',
      'thisWeek',
      'thisMonth',
      'lastThreeMonths',
      'lastSixMonths',
      'earlier',
    ])
    expect(groups.flatMap((group) => group.items.map((item) => item.id))).toEqual(
      items.map((item) => item.id),
    )
    expect(groups.find((group) => group.key === 'today')?.items.map((item) => item.id)).toEqual([
      1, 2,
    ])
  })

  test('在每个边界恰好命中较新的分组，前一毫秒落入下一个分组', () => {
    const now = localDate(2025, 7, 16, 15)
    const boundaries = [
      ['today', localDate(2025, 7, 16, 0), 'yesterday'],
      ['yesterday', localDate(2025, 7, 15, 0), 'thisWeek'],
      ['thisWeek', localDate(2025, 7, 14, 0), 'thisMonth'],
      ['thisMonth', localDate(2025, 7, 1, 0), 'lastThreeMonths'],
      ['lastThreeMonths', localDate(2025, 4, 16, 0), 'lastSixMonths'],
      ['lastSixMonths', localDate(2025, 1, 16, 0), 'thisYear'],
      ['thisYear', localDate(2025, 1, 1, 0), 'earlier'],
    ] as const

    for (const [key, start, previousKey] of boundaries) {
      expect(groupKeyFor(start, now)).toBe(key)
      expect(groupKeyFor(start - 1, now), `${key} 前一毫秒`).toBe(previousKey)
    }
  })

  test('周一优先将周日归入昨天，并支持跨月周', () => {
    const monday = localDate(2025, 3, 10)
    expect(groupKeyFor(localDate(2025, 3, 9, 23, 59), monday)).toBe('yesterday')
    expect(groupKeyFor(localDate(2025, 3, 8, 23, 59), monday)).toBe('thisMonth')

    const aprilReference = localDate(2025, 4, 2)
    expect(groupKeyFor(localDate(2025, 3, 31), aprilReference)).toBe('thisWeek')
  })

  test('按日历月计算滚动窗口并在月末夹断日期', () => {
    const may31 = localDate(2026, 5, 31)
    expect(groupKeyFor(localDate(2026, 2, 28, 0), may31)).toBe('lastThreeMonths')
    expect(groupKeyFor(localDate(2026, 2, 27, 23, 59, 59, 999), may31)).toBe('lastSixMonths')

    const leapYearMay31 = localDate(2024, 5, 31)
    expect(groupKeyFor(localDate(2024, 2, 29, 0), leapYearMay31)).toBe('lastThreeMonths')
    expect(groupKeyFor(localDate(2024, 2, 28, 23, 59, 59, 999), leapYearMay31)).toBe(
      'lastSixMonths',
    )
  })

  test('跨年时先命中滚动窗口，剩余本年数据归入今年', () => {
    const yearEnd = localDate(2025, 12, 31)
    expect(groupKeyFor(localDate(2025, 6, 30, 0), yearEnd)).toBe('lastSixMonths')
    expect(groupKeyFor(localDate(2025, 6, 29), yearEnd)).toBe('thisYear')
    expect(groupKeyFor(localDate(2024, 12, 31), yearEnd)).toBe('earlier')
  })

  test('有限未来时间归入今天，无效时间保持更早兼容行为', () => {
    const now = localDate(2025, 7, 16)
    const groups = groupBrowseHistory(
      [makeItem(1, now + 2 * 86_400_000), makeItem(2, Number.NaN)],
      now,
    )

    expect(groups.map((group) => [group.key, group.items.map((item) => item.id)])).toEqual([
      ['today', [1]],
      ['earlier', [2]],
    ])
  })

  test('日历边界不依赖固定 24 小时回推', () => {
    const afterDstReference = localDate(2024, 3, 12)
    const previousLocalDay = localDate(2024, 3, 10, 23, 30)

    expect(groupKeyFor(previousLocalDay, afterDstReference)).toBe('thisMonth')
  })
})
