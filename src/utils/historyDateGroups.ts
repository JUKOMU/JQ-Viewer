import type {
  BrowseHistoryGroupKey,
  BrowseHistoryItem,
  BrowseHistoryRange,
} from '@/services/JmcomicTypes'

export type BrowseGroupKey = BrowseHistoryGroupKey

export interface BrowseHistoryGroup {
  key: BrowseGroupKey
  label: string
  items: BrowseHistoryItem[]
}

export const BROWSE_GROUP_DEFINITIONS = [
  { key: 'today', label: '今天' },
  { key: 'yesterday', label: '昨天' },
  { key: 'thisWeek', label: '本周' },
  { key: 'thisMonth', label: '本月' },
  { key: 'lastThreeMonths', label: '3个月内' },
  { key: 'lastSixMonths', label: '6个月内' },
  { key: 'thisYear', label: '今年' },
  { key: 'earlier', label: '更早' },
] as const satisfies ReadonlyArray<{ key: BrowseGroupKey; label: string }>

interface BrowseGroupBoundary {
  key: Exclude<BrowseGroupKey, 'earlier'>
  startMs: number
}

export interface BrowseHistoryGroupSnapshot {
  nowMs: number
  ranges: readonly BrowseHistoryRange[]
}

function startOfLocalDay(year: number, month: number, date: number): number {
  return new Date(year, month, date).getTime()
}

function startOfRollingMonth(reference: Date, monthsAgo: number): number {
  const monthStart = new Date(reference.getFullYear(), reference.getMonth() - monthsAgo, 1)
  const lastDay = new Date(monthStart.getFullYear(), monthStart.getMonth() + 1, 0).getDate()
  const date = Math.min(reference.getDate(), lastDay)
  return startOfLocalDay(monthStart.getFullYear(), monthStart.getMonth(), date)
}

function buildBoundaries(nowMs: number): BrowseGroupBoundary[] {
  const reference = new Date(nowMs)
  const year = reference.getFullYear()
  const month = reference.getMonth()
  const date = reference.getDate()
  const todayStart = startOfLocalDay(year, month, date)
  const daysSinceMonday = (reference.getDay() + 6) % 7

  return [
    { key: 'today', startMs: todayStart },
    { key: 'yesterday', startMs: startOfLocalDay(year, month, date - 1) },
    {
      key: 'thisWeek',
      startMs: startOfLocalDay(year, month, date - daysSinceMonday),
    },
    { key: 'thisMonth', startMs: startOfLocalDay(year, month, 1) },
    { key: 'lastThreeMonths', startMs: startOfRollingMonth(reference, 3) },
    { key: 'lastSixMonths', startMs: startOfRollingMonth(reference, 6) },
    { key: 'thisYear', startMs: startOfLocalDay(year, 0, 1) },
  ]
}

function getMinimumStart(boundaries: readonly BrowseGroupBoundary[], endExclusive: number): number {
  return Math.min(...boundaries.slice(0, endExclusive).map((boundary) => boundary.startMs))
}

export function createBrowseHistorySnapshot(
  nowMs: number = Date.now(),
): BrowseHistoryGroupSnapshot {
  const candidate = new Date(nowMs).getTime()
  const snapshotNowMs = Number.isFinite(candidate) ? nowMs : Date.now()
  const boundaries = buildBoundaries(snapshotNowMs)
  const ranges = BROWSE_GROUP_DEFINITIONS.map(({ key }) => {
    if (key === 'earlier') {
      return {
        key,
        startInclusive: null,
        endExclusive: getMinimumStart(boundaries, boundaries.length),
      }
    }

    const boundaryIndex = boundaries.findIndex((boundary) => boundary.key === key)
    const startInclusive = boundaries[boundaryIndex]?.startMs ?? null
    const endExclusive = boundaryIndex > 0 ? getMinimumStart(boundaries, boundaryIndex) : null
    return { key, startInclusive, endExclusive }
  })

  return { nowMs: snapshotNowMs, ranges }
}

function getGroupKey(timestamp: number, snapshot: BrowseHistoryGroupSnapshot): BrowseGroupKey {
  if (!Number.isFinite(timestamp)) return 'earlier'

  for (const range of snapshot.ranges) {
    if (range.startInclusive !== null && timestamp < range.startInclusive) continue
    if (range.endExclusive !== null && timestamp >= range.endExclusive) continue
    return range.key
  }
  return 'earlier'
}

export function groupBrowseHistory(
  items: readonly BrowseHistoryItem[],
  nowMs: number = Date.now(),
): BrowseHistoryGroup[] {
  const snapshot = createBrowseHistorySnapshot(nowMs)
  const itemsByKey = new Map<BrowseGroupKey, BrowseHistoryItem[]>()

  for (const item of items) {
    const key = getGroupKey(item.timestamp, snapshot)
    const groupItems = itemsByKey.get(key)
    if (groupItems) groupItems.push(item)
    else itemsByKey.set(key, [item])
  }

  return BROWSE_GROUP_DEFINITIONS.flatMap(({ key, label }) => {
    const groupItems = itemsByKey.get(key)
    return groupItems ? [{ key, label, items: groupItems }] : []
  })
}
