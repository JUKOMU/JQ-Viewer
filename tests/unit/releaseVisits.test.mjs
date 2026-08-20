// @vitest-environment node

import { describe, expect, it } from 'vitest'

import {
  buildChartPoints,
  createVisitSample,
  formatVisitValue,
  mergeVisitHistory,
} from '../../scripts/release-visits.mjs'

function release(id, tagName, assets) {
  return {
    id,
    tag_name: tagName,
    assets,
  }
}

function asset(id, name, downloadCount) {
  return {
    id,
    name,
    download_count: downloadCount,
  }
}

describe('release visits', () => {
  it('preserves raw download counts by release and asset', () => {
    const sample = createVisitSample(
      [
        release(10, 'v1.0.0', [asset(100, 'universal.apk', 12), asset(101, 'arm64.apk', 8)]),
        release(11, 'v1.1.0', [asset(102, 'universal.apk', 25)]),
      ],
      new Date('2026-08-20T01:00:00Z'),
    )

    expect(sample).toEqual({
      date: '2026-08-20',
      releases: [
        {
          id: '10',
          tagName: 'v1.0.0',
          assets: [
            { id: '100', name: 'universal.apk', downloadCount: 12 },
            { id: '101', name: 'arm64.apk', downloadCount: 8 },
          ],
        },
        {
          id: '11',
          tagName: 'v1.1.0',
          assets: [{ id: '102', name: 'universal.apk', downloadCount: 25 }],
        },
      ],
    })
  })

  it('replaces a same-day sample and retains complete history', () => {
    const first = createVisitSample(
      [release(10, 'v1.0.0', [asset(100, 'universal.apk', 4)])],
      new Date('2026-08-19T01:00:00Z'),
    )
    const sameDay = createVisitSample(
      [release(10, 'v1.0.0', [asset(100, 'universal.apk', 9)])],
      new Date('2026-08-19T12:00:00Z'),
    )
    const nextDay = createVisitSample(
      [release(10, 'v1.0.0', [asset(100, 'universal.apk', 12)])],
      new Date('2026-08-20T01:00:00Z'),
    )

    const history = mergeVisitHistory(
      mergeVisitHistory(mergeVisitHistory(null, first), sameDay),
      nextDay,
    )

    expect(history.samples).toHaveLength(2)
    expect(history.samples.map((sample) => sample.date)).toEqual(['2026-08-19', '2026-08-20'])
    expect(history.samples[0].releases[0].assets[0].downloadCount).toBe(9)
  })

  it('uses all first-day downloads as the first increment and limits displayed points to 14 days', () => {
    const history = { schemaVersion: 1, samples: [] }
    for (let index = 0; index < 15; index += 1) {
      history.samples.push({
        date: `2026-08-${String(index + 1).padStart(2, '0')}`,
        releases: [
          {
            id: '10',
            tagName: 'v1.0.0',
            assets: [{ id: '100', name: 'universal.apk', downloadCount: 100 + index * 10 }],
          },
        ],
      })
    }

    expect(buildChartPoints({ schemaVersion: 1, samples: history.samples.slice(0, 1) })).toEqual([
      { date: '2026-08-01', increment: 100, total: 100 },
    ])
    expect(buildChartPoints(history)).toHaveLength(14)
    expect(buildChartPoints(history)[0]).toEqual({ date: '2026-08-02', increment: 10, total: 110 })
  })

  it('formats values at and above one thousand with one decimal k precision', () => {
    expect(formatVisitValue(999)).toBe('999')
    expect(formatVisitValue(1000)).toBe('1.0 k')
    expect(formatVisitValue(1450)).toBe('1.5 k')
  })
})
